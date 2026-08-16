import HabitSyncCore
import XCTest

@testable import HabitSyncClient

/// The projection from replicated registers to what a screen shows.
final class DomainTests: XCTestCase {

    private let clock = Hlc(physicalMillis: 1_000, logical: 0, nodeId: "device-a")

    private func habitRecord(_ fields: [String: FieldValue], deleted: Bool = false) -> EntityRecord {
        EntityRecord(
            type: .habit, id: UUID(), fields: fields,
            fieldClocks: fields.mapValues { _ in clock },
            deleted: deleted, lifecycleClock: deleted ? clock : nil)
    }

    func testReadsTheFieldsTheCliWrites() throws {
        let habit = try XCTUnwrap(
            Habit(record: habitRecord([
                "name": .of("Run"), "weeklyTarget": .of(Int64(4)), "colour": .of("red"),
            ])))

        XCTAssertEqual(habit.name, "Run")
        XCTAssertEqual(habit.weeklyTarget, 4)
        XCTAssertEqual(habit.colour, "red")
        XCTAssertFalse(habit.isDeleted)
    }

    /// A habit whose name register has not arrived yet is still a habit. Hiding it until every
    /// field turns up would make a habit created on another device appear field by field.
    func testAHabitWithNoNameYetStillExists() throws {
        let habit = try XCTUnwrap(Habit(record: habitRecord(["colour": .of("blue")])))
        XCTAssertEqual(habit.name, "")
    }

    func testAClearedFieldReadsAsAbsent() throws {
        let habit = try XCTUnwrap(Habit(record: habitRecord(["name": .of("Run"), "colour": .null])))
        XCTAssertNil(habit.colour)
    }

    func testARecordOfAnotherTypeIsNotAHabit() {
        let completion = EntityRecord(
            type: .habitCompletion, id: UUID(), fields: [:], fieldClocks: [:],
            deleted: false, lifecycleClock: nil)
        XCTAssertNil(Habit(record: completion))
    }

    func testACompletionNeedsBothItsFieldsToBeReadable() {
        let habitId = UUID()
        let complete = EntityRecord(
            type: .habitCompletion, id: UUID(),
            fields: ["habitId": .of(habitId), "at": .of(Int64(1_700_000_000_000))],
            fieldClocks: ["habitId": clock, "at": clock], deleted: false, lifecycleClock: nil)
        XCTAssertEqual(Completion(record: complete)?.habitId, habitId)

        // Half a completion is not one. It would otherwise land in the list with an epoch date
        // or a nil habit, which reads as data corruption to the user.
        let partial = EntityRecord(
            type: .habitCompletion, id: UUID(),
            fields: ["habitId": .of(habitId)], fieldClocks: ["habitId": clock],
            deleted: false, lifecycleClock: nil)
        XCTAssertNil(Completion(record: partial))
    }

    /// Weekly progress is counted from completions rather than stored, so it cannot lose an
    /// increment the way a shared counter register would.
    func testWeeklyProgressCountsOnlyThisWeeksCompletionsOfThisHabit() throws {
        let habit = try XCTUnwrap(Habit(record: habitRecord(["name": .of("Run")])))
        let week = Calendar.current.weekInterval(containing: Date())

        func completion(of habitId: UUID, at date: Date) -> Completion {
            Completion(
                record: EntityRecord(
                    type: .habitCompletion, id: UUID(),
                    fields: ["habitId": .of(habitId), "at": .of(date)],
                    fieldClocks: ["habitId": clock, "at": clock],
                    deleted: false, lifecycleClock: nil))!
        }

        let completions = [
            completion(of: habit.id, at: week.start.addingTimeInterval(60)),
            completion(of: habit.id, at: week.start.addingTimeInterval(3_600)),
            completion(of: habit.id, at: week.start.addingTimeInterval(-3_600)),
            completion(of: UUID(), at: week.start.addingTimeInterval(120)),
        ]

        XCTAssertEqual(habit.completionCount(in: completions, week: week), 2)
    }
}
