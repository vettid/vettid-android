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
    // SecretFieldAutofillTest: Compose semantics and the host view under Robolectric.
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
}

android {
    testOptions.unitTests {
        isIncludeAndroidResources = true
        // Robolectric's API 36 runtime reaches into the JDK's file descriptor internals.
        all { it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED") }
    }
}
