plugins {
    id("vettid.android.compose")
}

dependencies {
    // QrCode: ZXing's encoder draws the invitation code (VAULT-MESSAGING §6.4).
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
}
