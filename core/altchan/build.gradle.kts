plugins {
    id("vettid.android.library")
}

dependencies {
    api(projects.core.crypto)
    api(projects.core.attestation)
    implementation(projects.core.keystore)
    // RequestSigner: a member-API request is signed afresh for every transport attempt (A6 audit N4).
    implementation(projects.core.relay)
    api(libs.okhttp)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
