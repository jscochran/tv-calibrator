// The calibration engine: a Kotlin port of CalibrationKit (Swift), itself a port of the Mac lab tool's
// Python engine. Plain Kotlin/JVM with no Android dependencies, so its tests run on any computer.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}

// The tests read the same fixtures as the Swift tests (written by tools/make_fixtures.py).
tasks.test {
    systemProperty(
        "fixtures",
        rootProject.file("../CalibrationKit/Tests/CalibrationKitTests/Fixtures").absolutePath,
    )
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
