// swift-tools-version: 5.9
import PackageDescription

// The iOS client is a SwiftPM package plus a thin app shell (see App/ and project.yml).
//
// The split mirrors the JVM side deliberately: HabitSyncCore is the Swift port of
// `sync-core` and, like it, has no I/O, no SQLite and no UIKit — which is what lets the
// merge and convergence tests run on a plain toolchain with no simulator. HabitSyncClient
// is the port of `client/`: the two seams (LocalStore, Transport) filled in with SQLite
// and URLSession. HabitSyncUI holds the SwiftUI screens, as a library rather than as app
// sources so it can be previewed and tested without the app target.
let package = Package(
    name: "HabitSync",
    platforms: [
        // SwiftUI Observation (@Observable) and the Swift concurrency features the sync
        // worker relies on. The server sets no floor of its own; this is the app's.
        .iOS(.v17),
        // macOS is here for the test suite, not as a shipping target: it means
        // `swift test` works from a terminal without booting a simulator.
        .macOS(.v14),
    ],
    products: [
        .library(name: "HabitSyncCore", targets: ["HabitSyncCore"]),
        .library(name: "HabitSyncClient", targets: ["HabitSyncClient"]),
        .library(name: "HabitSyncUI", targets: ["HabitSyncUI"]),
    ],
    targets: [
        .target(name: "HabitSyncCore"),
        .target(name: "HabitSyncClient", dependencies: ["HabitSyncCore"]),
        .target(name: "HabitSyncUI", dependencies: ["HabitSyncClient"]),
        .testTarget(name: "HabitSyncCoreTests", dependencies: ["HabitSyncCore"]),
        .testTarget(name: "HabitSyncClientTests", dependencies: ["HabitSyncClient"]),
    ]
)
