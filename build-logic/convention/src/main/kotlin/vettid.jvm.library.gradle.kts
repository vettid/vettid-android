// Pure Kotlin/JVM library: code that must not depend on Android (the
// protocol crypto in :core:crypto). Android modules depend on it as a jar;
// its unit tests run with `test` (CI runs them next to testDebugUnitTest).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
    id("vettid.detekt")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(providers.gradleProperty("warningsAsErrors").map { it.toBoolean() }.orElse(false))
    }
}

tasks.withType<Test>().configureEach {
    // Shared machine: one test JVM, bounded heap.
    maxParallelForks = 1
    maxHeapSize = "1g"
}

dependencies {
    "testImplementation"(libs.findLibrary("junit").get())
}
