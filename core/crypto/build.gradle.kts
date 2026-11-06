plugins {
    id("vettid.jvm.library")
}

dependencies {
    implementation(libs.bouncycastle.bcprov)
    // Ed25519.verifyStrict's cofactorless check (subtle.Ed25519Verify only).
    implementation(libs.tink.android)
}
