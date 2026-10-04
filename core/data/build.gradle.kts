plugins {
    id("vettid.android.library")
}

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

    // The A2 exit test runs on a phone against the local dev stack (devstack/README.md).
    androidTestImplementation(projects.core.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
