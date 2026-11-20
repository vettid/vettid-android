// Root project. Build configuration lives in the convention plugins under
// build-logic/ (vettid.android.application, .library, .compose, .feature, .hilt, .detekt).

// Robolectric's Android jar (213 MB) is resolved by Gradle, not by Robolectric's own downloader, and the tests run offline
// against it. CI's runs failed twice on that downloader (an IOException in MavenArtifactFetcher, every Robolectric class
// failing in classMethod; run 41354790443): Gradle retries the download and keeps the jar in its cache, which CI saves.
val robolectricAndroidAll: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    robolectricAndroidAll(libs.robolectric.android.all)
}

tasks.register<Sync>("robolectricJars") {
    from(robolectricAndroidAll)
    into(layout.buildDirectory.dir("robolectric-jars"))
}

subprojects {
    tasks.withType<Test>().configureEach {
        dependsOn(":robolectricJars")
        systemProperty("robolectric.offline", "true")
        systemProperty("robolectric.dependency.dir", rootProject.layout.buildDirectory.dir("robolectric-jars").get().asFile.absolutePath)
    }
}

// The dependency scan's listing (.github/workflows/deps-scan.yml): with -PvettidScanAll=true every resolvable configuration
// of every project is locked, and `lockAllForScan --write-locks` resolves them all, so their lockfiles name every test and
// build dependency for osv-scanner to list. Only the scan's throwaway checkout does this: the committed lock is app's
// release and staging runtime classpaths (app/build.gradle.kts).
if (providers.gradleProperty("vettidScanAll").orNull == "true") {
    allprojects {
        configurations.configureEach {
            if (isCanBeResolved) resolutionStrategy.activateDependencyLocking()
        }
        tasks.register("lockAllForScan") {
            notCompatibleWithConfigurationCache("resolves every configuration at execution")
            doLast {
                // Gradle writes no lock for a configuration with an unresolved dependency: name each, so a gap in the
                // listing shows in the job's log.
                project.configurations.filter { it.isCanBeResolved }.forEach { c ->
                    val unresolved = c.incoming.resolutionResult.allDependencies
                        .count { it is org.gradle.api.artifacts.result.UnresolvedDependencyResult }
                    if (unresolved > 0) logger.warn("not listed: ${project.path} ${c.name} ($unresolved unresolved)")
                }
            }
        }
    }
}
