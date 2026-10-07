plugins {
    id("vettid.android.library")
}

// kotlinx.serialization for the small local records (the account file).
apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

android {
    // The test APK bundles bcprov, bcutil and bcpkix (the TEST attestation CA), which share these files.
    packaging {
        resources {
            excludes += listOf("META-INF/LICENSE.md", "META-INF/NOTICE.md", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }
}

dependencies {
    api(projects.core.vault)
    api(projects.core.keystore)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.test)
    // The A5 items scenario against the local dev stack (skipped without it).
    testImplementation(projects.core.testing)

    // The A2 exit test runs on a phone against the local dev stack (devstack/README.md).
    androidTestImplementation(projects.core.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
