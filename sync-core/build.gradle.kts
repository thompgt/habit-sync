plugins {
    `java-library`
}

// sync-core is deliberately dependency-free: no Spring, no Android, no ORM.
//
// Two consumers run this exact code — the Spring Boot server and the Android client —
// which is what guarantees they can never disagree about who won a conflict. It also
// means the convergence simulator (M6) exercises the real merge logic on a plain JVM,
// with no emulator and no mocks of the code under test.
//
// Java 17 (not 21) so the same bytecode is consumable by the Android client under
// AGP 8 desugaring. Do not raise this without checking Android compatibility.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.jqwik)
    testRuntimeOnly(libs.junit.platform.launcher)
}
