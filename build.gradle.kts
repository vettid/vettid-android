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
