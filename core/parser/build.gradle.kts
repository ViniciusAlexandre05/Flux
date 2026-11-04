plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.android.lint)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core:model"))
    // XmlPullParser API: provided by the Android framework at runtime, so compileOnly here.
    compileOnly(libs.xmlpull)
    testImplementation(libs.xmlpull)
    testImplementation(libs.kxml2)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Fixtures live in <repo>/samples. Declared as inputs so adding/removing a sample re-runs the
    // tests instead of reusing a cached result (samples/real/ is local-only and may be missing).
    systemProperty("samples.dir", rootProject.file("samples").absolutePath)
    inputs.files(rootProject.fileTree("samples")).withPropertyName("samples")
}
