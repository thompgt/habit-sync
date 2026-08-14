package dev.thompgt.habitsync.sim;

import dev.thompgt.habitsync.sync.EntityKey;
import dev.thompgt.habitsync.sync.EntityRecord;
import dev.thompgt.habitsync.sync.FieldValue;
import dev.thompgt.habitsync.sync.Hlc;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The question the whole simulator exists to ask: after everyone has stopped writing and the
 * network has healed, does every replica — and the server — hold identical state?
 *
 * <h2>Identical, not equivalent</h2>
 *
 * The comparison covers field values, the HLC that wrote each field, the tombstone flag, and
 * the lifecycle clock. Comparing only what the user can see would be a weaker and dishonestly
 * easier test: two replicas can display the same habits while disagreeing about which clock
 * last wrote a field, and that difference is invisible right up until a late write arrives and
 * they resolve it in opposite directions. A divergence that only manifests on the <em>next</em>
 * write is still a divergence, and it is the kind that reaches production.
 *
 * <p>Tombstoned entities are compared too. "Both agree it is deleted" and "one has forgotten
 * it existed" behave identically until something restores it.
 *
 * <h2>Why the server is included</h2>
 *
 * The server runs the same {@link dev.thompgt.habitsync.sync.MergeEngine}, so agreeing with it
 * ought to be implied by the replicas agreeing with each other. Checking it anyway is what
 * catches the failures that are not in merge at all — a page the log never served, a bootstrap
 * that omitted a field, a retention sweep that collected something still needed.
 */
public final class ConvergenceOracle {

    private ConvergenceOracle() {}

    /**
     * @return an empty list when everything agrees, or one entry per disagreement, ordered so
     *         that repeated runs of a seed produce byte-identical reports
     */
    public static List<String> compare(SimulatedServer server, List<Replica> replicas) {
        Map<EntityKey, EntityRecord> serverState = server.state();

        Set<EntityKey> allKeys = new TreeSet<>(
                java.util.Comparator.comparing((EntityKey key) -> key.type().name())
                        .thenComparing(key -> key.id().toString()));
        allKeys.addAll(serverState.keySet());
        for (Replica replica : replicas) {
            allKeys.addAll(replica.state().keySet());
        }

        List<String> differences = new ArrayList<>();
        for (EntityKey key : allKeys) {
            EntityRecord expected = serverState.get(key);
            for (Replica replica : replicas) {
                EntityRecord actual = replica.state().get(key);
                describeDifference(key, expected, actual, replica.nodeId()).ifPresent(differences::add);
            }
        }
        return differences;
    }

    private static java.util.Optional<String> describeDifference(
            EntityKey key, EntityRecord server, EntityRecord replica, String nodeId) {

        if (server == null && replica == null) {
            return java.util.Optional.empty();
        }
        if (server == null) {
            return java.util.Optional.of(
                    "%s holds %s which the server does not: %s".formatted(nodeId, key, render(replica)));
        }
        if (replica == null) {
            return java.util.Optional.of(
                    "%s is missing %s, which the server holds as %s".formatted(nodeId, key, render(server)));
        }

        List<String> mismatches = new ArrayList<>();
        if (server.deleted() != replica.deleted()) {
            mismatches.add("deleted: server=%s %s=%s".formatted(server.deleted(), nodeId, replica.deleted()));
        }
        if (!Objects.equals(server.lifecycleClock(), replica.lifecycleClock())) {
            mismatches.add("lifecycleClock: server=%s %s=%s"
                    .formatted(server.lifecycleClock(), nodeId, replica.lifecycleClock()));
        }

        Set<String> fields = new TreeSet<>();
        fields.addAll(server.fields().keySet());
        fields.addAll(replica.fields().keySet());
        for (String field : fields) {
            FieldValue serverValue = server.field(field);
            FieldValue replicaValue = replica.field(field);
            if (!Objects.equals(serverValue, replicaValue)) {
                mismatches.add("%s: server=%s %s=%s".formatted(field, serverValue, nodeId, replicaValue));
            }
            Hlc serverClock = server.clockOf(field);
            Hlc replicaClock = replica.clockOf(field);
            if (!Objects.equals(serverClock, replicaClock)) {
                // Equal values under different clocks still counts. The next write to this
                // field will be resolved against different provenance on the two sides.
                mismatches.add("%s@clock: server=%s %s=%s".formatted(field, serverClock, nodeId, replicaClock));
            }
        }

        if (mismatches.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of("%s diverges on %s -- %s".formatted(nodeId, key, String.join("; ", mismatches)));
    }

    private static String render(EntityRecord record) {
        Set<String> fields = new LinkedHashSet<>(record.fields().keySet());
        StringBuilder out = new StringBuilder();
        out.append(record.deleted() ? "deleted" : "live");
        for (String field : new TreeSet<>(fields)) {
            out.append(", ").append(field).append('=').append(record.field(field))
                    .append('@').append(record.clockOf(field));
        }
        return out.toString();
    }
}
