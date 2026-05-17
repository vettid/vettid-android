// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    id("com.google.dagger.hilt.android") version "2.56.2" apply false
    id("com.google.devtools.ksp") version "2.1.20-1.0.32" apply false
    // Dependency hygiene — #118 / L54. `./gradlew dependencyUpdates`
    // reports modules with newer releases on Maven Central. Run
    // periodically to catch security CVEs published against bundled
    // libraries (Tink, Bouncy Castle, OkHttp, Retrofit, etc.) before
    // they reach a release branch. The plugin only reports; it does
    // not modify versions.
    id("com.github.ben-manes.versions") version "0.51.0"
}

// Filter dependencyUpdates to stable releases only. The plugin's
// default behavior reports alpha/beta/RC versions as "updates",
// which is noisy on Android (Compose + Gradle plugin both ship
// frequent pre-releases). Treat a candidate as "stable" only if its
// version string contains a release keyword (RELEASE / FINAL / GA),
// has a 1.2.3 shape, or matches a numeric pattern — anything tagged
// alpha/beta/rc/m1/snapshot is rejected as non-stable.
tasks.named("dependencyUpdates", com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask::class.java).configure {
    rejectVersionIf {
        val v = candidate.version.lowercase()
        listOf("alpha", "beta", "rc", "m1", "snapshot", "preview", "dev", "eap").any { v.contains(it) }
    }
    // Plain-text report next to the build dir — easy to diff against
    // the prior run when triaging a CVE-driven bump.
    outputFormatter = "plain"
}
