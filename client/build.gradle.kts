plugins {
    `java-library`
    application
}

// The reference client: a real HTTP transport and a durable SQLite store, driving the same
// SyncEngine the Android app will.
//
// It exists because M3 is blocked on there being no Android SDK on the build machine, and
// because "offline-first tracker" is not demonstrated by a server and a library. Everything
// here except the UI is what Android needs: the transport is platform-independent, and the
// store's job is the LocalStore atomicity contract, which SQLite provides in the same shape
// Room does.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

application {
    mainClass.set("dev.thompgt.habitsync.client.HabitCli")
    // sqlite-jdbc unpacks and loads a native library, which is a restricted operation on
    // modern JVMs. Granting it explicitly keeps four lines of warning off every command's
    // output -- and the warning says it will become an error in a future release.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    api(project(":sync-core"))

    implementation(libs.jackson.databind)
    implementation(libs.sqlite.jdbc)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
