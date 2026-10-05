plugins {
    id("vettid.android.compose")
}

dependencies {
    // QrCode: ZXing's encoder draws the invitation code (VAULT-MESSAGING §6.4).
    implementation(libs.zxing.core)
    // QrScanner: CameraX frames decoded by ZXing (invitations, the transfer and recovery codes).
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    testImplementation(libs.junit)
}
