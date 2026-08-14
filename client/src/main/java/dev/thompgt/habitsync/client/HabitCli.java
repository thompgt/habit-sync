package dev.thompgt.habitsync.client;

import dev.thompgt.habitsync.sync.EntityRecord;
import dev.thompgt.habitsync.sync.EntityType;
import dev.thompgt.habitsync.sync.FieldValue;
import dev.thompgt.habitsync.sync.SyncEngine;
import dev.thompgt.habitsync.sync.SyncOutcome;
import dev.thompgt.habitsync.sync.TimeSource;
import dev.thompgt.habitsync.sync.TransportException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A small terminal client, so the offline-first claim can actually be exercised by hand.
 *
 * <p>Every command except {@code sync} works with the server unreachable, which is the point:
 * writes land in SQLite and the outbox immediately, and the network is a separate concern that
 * happens later. Stopping the server and adding a few habits, then starting it and running
 * {@code sync}, is the demonstration.
 *
 * <p>Two instances pointed at different {@code --home} directories are two devices on one
 * account, which is how the convergence behaviour can be seen without an emulator.
 *
 * <pre>
 *   habit --home ~/.habit-phone register alice@example.com hunter2hunter2 Phone
 *   habit --home ~/.habit-tablet login  alice@example.com hunter2hunter2 Tablet
 *   habit --home ~/.habit-phone add Run --target 4 --colour red
 *   habit --home ~/.habit-phone sync
 *   habit --home ~/.habit-tablet sync
 *   habit --home ~/.habit-tablet list
 * </pre>
 */
public final class HabitCli {

    private static final String DEFAULT_HOME = System.getProperty("user.home") + "/.habit-sync";
    private static final String DEFAULT_SERVER = "http://localhost:8080";

    private HabitCli() {}

    public static void main(String[] args) {
        List<String> arguments = new java.util.ArrayList<>(List.of(args));
        Path home = Path.of(takeOption(arguments, "--home").orElse(DEFAULT_HOME));
        String server = takeOption(arguments, "--server").orElse(DEFAULT_SERVER);

        if (arguments.isEmpty()) {
            usage();
            return;
        }

        try {
            run(home, server, arguments);
        } catch (TransportException e) {
            // Retryability is the useful half of the message: "try again" and "this will never
            // work" call for completely different reactions from a person at a terminal.
            System.err.println(
                    (e.isRetryable() ? "Sync failed (retryable): " : "Sync failed permanently: ") + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println(e.getMessage());
            System.exit(2);
        }
    }

    private static void run(Path home, String server, List<String> arguments) throws TransportException {
        String command = arguments.remove(0);
        Path sessionPath = home.resolve("session.json");
        Path databasePath = home.resolve("habits.db");

        switch (command) {
            case "register" -> {
                String email = required(arguments, 0, "email");
                String password = required(arguments, 1, "password");
                String deviceName = arguments.size() > 2 ? arguments.get(2) : "Reference client";
                Session session = HttpTransport.register(server, email, password, deviceName);
                SessionFile.write(sessionPath, session, email);
                System.out.println("Registered " + email + " as device " + session.deviceId());
            }
            case "login" -> {
                String email = required(arguments, 0, "email");
                String password = required(arguments, 1, "password");
                String deviceName = arguments.size() > 2 ? arguments.get(2) : "Reference client";
                // Reuse the saved device id if this home directory has one. Taking a new id
                // would fragment this device's causal history across two HLC node identities.
                UUID existing = SessionFile.read(sessionPath).map(SessionFile.Stored::deviceId).orElse(null);
                Session session = HttpTransport.login(server, email, password, deviceName, existing);
                SessionFile.write(sessionPath, session, email);
                System.out.println("Logged in as " + email + ", device " + session.deviceId());
            }
            default -> withDevice(home, server, sessionPath, databasePath, command, arguments);
        }
    }

    private static void withDevice(
            Path home, String server, Path sessionPath, Path databasePath, String command, List<String> arguments)
            throws TransportException {

        SessionFile.Stored stored = SessionFile.read(sessionPath)
                .orElseThrow(() -> new IllegalStateException(
                        "No session in " + home + ". Run `register` or `login` first."));
        Session session = SessionFile.toSession(stored);

        try (SqliteLocalStore store = new SqliteLocalStore(databasePath.toString());
                HttpTransport transport = new HttpTransport(server, session)) {

            // forDevice restores the clock from the store. A device that started from zero on
            // every launch would reissue timestamps it had already used.
            SyncEngine engine = SyncEngine.forDevice(session.nodeId(), store, transport, TimeSource.system());

            switch (command) {
                case "add" -> add(engine, arguments);
                case "rename" -> rename(engine, store, arguments);
                case "set" -> set(engine, store, arguments);
                case "delete" -> delete(engine, store, arguments);
                case "done" -> complete(engine, store, arguments);
                case "list" -> list(store);
                case "status" -> status(store);
                case "sync" -> sync(engine, session);
                default -> {
                    System.err.println("Unknown command: " + command);
                    usage();
                }
            }
            // Refresh rotation may have replaced both tokens during the sync. Not saving them
            // would present an already-rotated refresh token next time, which this server
            // treats as theft and answers by revoking every session on the account.
            SessionFile.write(sessionPath, session, stored.email());
        }
    }

    // ------------------------------------------------------------- commands

    private static void add(SyncEngine engine, List<String> arguments) {
        String name = required(arguments, 0, "habit name");
        Map<String, FieldValue> fields = new LinkedHashMap<>();
        fields.put("name", FieldValue.of(name));
        takeOption(arguments, "--target").ifPresent(value -> fields.put("weeklyTarget", FieldValue.of(Long.parseLong(value))));
        takeOption(arguments, "--colour").ifPresent(value -> fields.put("colour", FieldValue.of(value)));

        UUID id = UUID.randomUUID();
        engine.upsert(EntityType.HABIT, id, fields);
        // No network was touched. That is the whole design, not an optimisation.
        System.out.println("Added " + name + " (" + shortId(id) + "), queued for the next sync");
    }

    private static void rename(SyncEngine engine, SqliteLocalStore store, List<String> arguments) {
        UUID id = resolve(store, required(arguments, 0, "habit id"));
        String name = required(arguments, 1, "new name");
        // A single-field write, so a rename here and a target change elsewhere both survive.
        engine.upsert(EntityType.HABIT, id, "name", FieldValue.of(name));
        System.out.println("Renamed " + shortId(id) + " to " + name);
    }

    /**
     * Writes one field of a habit, with {@code -} meaning "clear it".
     *
     * <p>The clear is not a convenience. A cleared field and a never-written one are different
     * states everywhere in this system, and without a way to produce the first from the
     * command line the distinction is only ever exercised by tests — which is exactly how it
     * comes to be broken by an innocent-looking serialisation change.
     */
    private static void set(SyncEngine engine, SqliteLocalStore store, List<String> arguments) {
        UUID id = resolve(store, required(arguments, 0, "habit id"));
        String field = required(arguments, 1, "field name");
        String raw = required(arguments, 2, "value (or - to clear)");

        FieldValue value = "-".equals(raw) ? FieldValue.NULL : FieldValue.of(raw);
        // One field per op, so this and a concurrent rename on another device both survive.
        engine.upsert(EntityType.HABIT, id, field, value);
        System.out.println("Set " + field + " = " + (value.isNull() ? "(cleared)" : raw) + " on " + shortId(id));
    }

    private static void delete(SyncEngine engine, SqliteLocalStore store, List<String> arguments) {
        UUID id = resolve(store, required(arguments, 0, "habit id"));
        engine.delete(EntityType.HABIT, id);
        System.out.println("Deleted " + shortId(id));
    }

    private static void complete(SyncEngine engine, SqliteLocalStore store, List<String> arguments) {
        UUID habitId = resolve(store, required(arguments, 0, "habit id"));
        UUID completionId = UUID.randomUUID();
        // An append-only fact: created once, never edited, and genuinely incapable of
        // conflicting with anything.
        engine.upsert(
                EntityType.HABIT_COMPLETION,
                completionId,
                Map.of(
                        "habitId", FieldValue.of(habitId.toString()),
                        "at", FieldValue.of(System.currentTimeMillis())));
        System.out.println("Logged a completion for " + shortId(habitId));
    }

    private static void list(SqliteLocalStore store) {
        List<EntityRecord> habits = store.visibleRecords().stream()
                .filter(record -> record.type() == EntityType.HABIT)
                .sorted(Comparator.comparing(record -> text(record, "name")))
                .toList();

        if (habits.isEmpty()) {
            System.out.println("No habits yet. Try `add Run --target 4`.");
            return;
        }

        long completions = store.visibleRecords().stream()
                .filter(record -> record.type() == EntityType.HABIT_COMPLETION)
                .count();

        for (EntityRecord habit : habits) {
            System.out.printf(
                    "%-10s %-24s target %-4s %s%n",
                    shortId(habit.id()), text(habit, "name"), text(habit, "weeklyTarget"), text(habit, "colour"));
        }
        System.out.println(habits.size() + " habits, " + completions + " completions logged");
    }

    private static void status(SqliteLocalStore store) {
        System.out.println("watermark:    " + store.watermark());
        System.out.println("pending ops:  " + store.pendingOpCount());
        System.out.println("clock:        " + store.lastClock().map(Object::toString).orElse("(none yet)"));
        System.out.println("entities:     " + store.allRecords().size() + " (" + store.visibleRecords().size() + " visible)");
    }

    private static void sync(SyncEngine engine, Session session) throws TransportException {
        SyncOutcome outcome = engine.sync();
        System.out.printf(
                "Synced as %s: %d ops acknowledged, %d changes applied, watermark %d%s%n",
                session.deviceId(),
                outcome.opsAcknowledged(),
                outcome.changesApplied(),
                outcome.watermark(),
                outcome.resynced() ? " (server demanded a full resync)" : "");

        if (outcome.moreRemaining()) {
            System.out.println("More remains; run sync again.");
        }
        // Losses are reported rather than swallowed. Silent data loss is a bug; visible loss
        // under a documented rule is the trade-off last-writer-wins asks for.
        for (var conflict : outcome.lostLocalWrites()) {
            System.out.println("  overwritten: " + conflict);
        }
        int unreported = outcome.conflictsObserved() - outcome.conflicts().size();
        if (unreported > 0) {
            System.out.println("  ...and " + unreported + " more conflicts not listed");
        }
    }

    // -------------------------------------------------------------- helpers

    /**
     * Resolves a short id prefix to a habit, as printed by {@code list}.
     *
     * <p>Ambiguity is an error rather than a first match. Silently picking one of two habits
     * whose ids share a prefix would delete the wrong one, and the user would have no way to
     * tell it had happened.
     */
    private static UUID resolve(SqliteLocalStore store, String prefix) {
        List<EntityRecord> matches = store.visibleRecords().stream()
                .filter(record -> record.type() == EntityType.HABIT)
                .filter(record -> record.id().toString().startsWith(prefix))
                .toList();

        if (matches.isEmpty()) {
            throw new IllegalArgumentException("No habit matches " + prefix);
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException(
                    "%s matches %d habits; use more characters".formatted(prefix, matches.size()));
        }
        return matches.get(0).id();
    }

    private static String text(EntityRecord record, String field) {
        FieldValue value = record.field(field);
        return value == null || value.isNull() ? "-" : value.raw();
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private static String required(List<String> arguments, int index, String what) {
        if (arguments.size() <= index) {
            throw new IllegalArgumentException("Missing " + what);
        }
        return arguments.get(index);
    }

    /** Removes {@code --name value} from the argument list and returns the value. */
    private static Optional<String> takeOption(List<String> arguments, String name) {
        int index = arguments.indexOf(name);
        if (index < 0) {
            return Optional.empty();
        }
        if (index + 1 >= arguments.size()) {
            throw new IllegalArgumentException(name + " needs a value");
        }
        arguments.remove(index);
        return Optional.of(arguments.remove(index));
    }

    private static void usage() {
        System.out.println(
                """
                habit [--home DIR] [--server URL] COMMAND

                  register EMAIL PASSWORD [DEVICE]   create an account (password: 12+ characters)
                  login    EMAIL PASSWORD [DEVICE]   sign in, reusing this home's device id
                  add NAME [--target N] [--colour C] create a habit
                  rename ID NAME                     rename one
                  set ID FIELD VALUE                 write one field ('-' clears it)
                  delete ID                          tombstone one
                  done ID                            log a completion
                  list                               show habits as this device sees them
                  status                             watermark, outbox depth, clock
                  sync                               push the outbox and pull what is missing

                Everything but `sync` works offline. Point two --home directories at one
                account to watch two devices converge.
                """);
    }
}
