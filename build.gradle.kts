plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

subprojects {
    apply(plugin = "java")

    // Repositories are declared once in settings.gradle.kts under PREFER_SETTINGS.
    // Re-declaring them here is ignored and warns on every build.

    // Every module compiles with `-Werror`. Sync bugs hide in the warnings you learn
    // to ignore, so we don't allow any to accumulate.
    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(
            listOf("-Xlint:all", "-Xlint:-processing", "-Werror", "-parameters")
        )
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
