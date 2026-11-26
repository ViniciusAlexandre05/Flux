// Update checks and downloads from GitHub releases, shared by Android and the PC version
// (docs/PLAN-updates.md). Pure Kotlin: java.net only, no Android imports.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.android.lint)
}

kotlin { jvmToolchain(17) }

dependencies {
    // JSON tree API only (no serialization plugin needed).
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { useJUnitPlatform() }
