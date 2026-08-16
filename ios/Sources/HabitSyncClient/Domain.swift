import Foundation
import HabitSyncCore

/// Field names, in one place.
///
/// These strings are the register keys the merge engine resolves per field, and they are
/// shared with the JVM client — `HabitCli` writes `name`, `weeklyTarget` and `colour` on a
/// habit, and `habitId` and `at` on a completion. A typo here does not fail loudly: it
/// creates a *second*, parallel register that the other client never reads, so the edit
/// syncs perfectly and simply never appears. Hence the constants.
public enum HabitField {
    public static let name = "name"
    public static let weeklyTarget = "weeklyTarget"
    public static let colour = "colour"

    /// Fields a user may edit freely from the detail screen.
    public static let editable = [name, weeklyTarget, colour]
}

public enum CompletionField {
    public static let habitId = "habitId"
    public static let at = "at"
}

/// A habit as the app shows it: the merged registers of one `HABIT` entity, read.
///
/// A projection, not a stored row. The durable truth is the per-field registers in
/// ``LocalStore``; this is what those look like once merged, and it is rebuilt on every read
/// rather than cached, because a background sync can change any register at any moment.
public struct Habit: Identifiable, Hashable, Sendable {
    public let id: UUID
    public let name: String
    /// `nil` when never written or explicitly cleared — the two are different in the store,
    /// but a habit with no target and one whose target was cleared read the same on screen.
    public let weeklyTarget: Int?
    public let colour: String?
    public let isDeleted: Bool

    /// Reads a habit out of a merged record, or `nil` if the record is not a habit.
    ///
    /// A record with no `name` register still produces a habit — with an empty name — rather
    /// than nothing. The entity exists as far as every other device is concerned, and hiding
    /// it locally because one register has not arrived yet would make a habit created on
    /// another device flicker into existence field by field.
    public init?(record: EntityRecord) {
        guard record.type == .habit else { return nil }
        id = record.id
        name = record.field(HabitField.name)?.raw ?? ""
        weeklyTarget = record.field(HabitField.weeklyTarget)?.raw.flatMap(Int.init)
        colour = record.field(HabitField.colour)?.raw
        isDeleted = record.deleted
    }
}

/// A logged completion: an append-only fact, created once and never edited.
///
/// Genuinely incapable of conflicting with anything — two devices logging the same habit at
/// the same moment produce two entities with different client-generated ids, and both are
/// kept, which is the correct answer for "I completed this twice".
public struct Completion: Identifiable, Hashable, Sendable {
    public let id: UUID
    public let habitId: UUID
    public let at: Date

    public init?(record: EntityRecord) {
        guard record.type == .habitCompletion,
            let rawHabit = record.field(CompletionField.habitId)?.raw,
            let habitId = UUID(uuidString: rawHabit),
            let rawAt = record.field(CompletionField.at)?.raw,
            let millis = Int64(rawAt)
        else { return nil }
        id = record.id
        self.habitId = habitId
        at = Date(timeIntervalSince1970: Double(millis) / 1000)
        // Deliberately no `deleted` here: a completion the user removed is filtered out by
        // the caller, and a tombstoned append-only fact has nothing else worth reading.
    }
}

extension Habit {
    /// Completions of this habit inside `week`, for the progress a target is measured against.
    ///
    /// Counted from the completions the device already holds rather than stored on the habit.
    /// A stored counter would be a register two devices could increment concurrently, and
    /// last-writer-wins on a counter loses one of the increments — the classic reason to
    /// derive an aggregate from immutable facts instead of maintaining it.
    public func completionCount(in completions: [Completion], week: DateInterval) -> Int {
        completions.lazy.filter { $0.habitId == id && week.contains($0.at) }.count
    }
}

extension Calendar {
    /// The week containing `date`, in the user's calendar and first-weekday preference.
    ///
    /// Locale-dependent on purpose: "this week" is a human unit, and a Monday-start user
    /// being shown a Sunday-start week is wrong even though every timestamp involved is
    /// correct.
    public func weekInterval(containing date: Date = Date()) -> DateInterval {
        dateInterval(of: .weekOfYear, for: date)
            ?? DateInterval(start: date, duration: 7 * 24 * 60 * 60)
    }
}
