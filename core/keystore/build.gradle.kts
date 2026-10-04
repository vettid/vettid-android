plugins {
    id("vettid.android.library")
}

dependencies {
    implementation(projects.core.crypto)

    androidTestImplementation(projects.core.attestation)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
