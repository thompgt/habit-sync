plugins {
    `java-library`
}

// The simulator is a deliverable, not a test fixture, so it lives in `main` and could be
// driven from a script or a CI job as easily as from JUnit. Like sync-core it takes no
// dependencies at all: a convergence failure must be attributable to the engine, never to
// something a framework did on its way past.
//
// Java 21 rather than sync-core's 17. Nothing here is consumed by the Android client, so
// the desugaring constraint does not apply.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

dependencies {
    api(project(":sync-core"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
