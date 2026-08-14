rootProject.name = "habit-sync"

// The Android client lives in `android/` and is added here once the Android SDK is
// available on the build machine. Keeping it out of the settings file means the
// server and sync-core build cleanly on any plain JDK — including CI.
include("sync-core")
include("server")

// The M6 convergence simulator. Pure JVM, no Docker and no network, so it runs
// everywhere sync-core does.
include("simulator")

// The JVM reference client: real HTTP, real durable storage. Unblocks the end-to-end
// offline -> reconnect -> converge loop without an Android SDK, and is the module the
// Android client will reuse everything but the UI from.
include("client")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
    }
}
