// Wallet screens (ANDROID-PLAN §4 "Wallet", W1c-2). NOT part of a production build yet: linked where :core:wallet is (debug,
// devStack, staging; release only with `-PvettidWalletInRelease=true`), and `:app:check<Variant>Wallet` fails a release APK that
// carries a class of it. The main app reaches it only through `walletDestinations()` (app/src/wallet).
plugins {
    id("vettid.android.feature")
    id("vettid.hilt")
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.wallet)
    implementation(projects.core.chain)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.secp256k1.jni.jvm)
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
