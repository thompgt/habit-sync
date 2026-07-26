rootProject.name = "habit-sync"

// The Android client lives in `android/` and is added here once the Android SDK is
// available on the build machine. Keeping it out of the settings file means the
// server and sync-core build cleanly on any plain JDK — including CI.
include("sync-core")
include("server")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
    }
}
