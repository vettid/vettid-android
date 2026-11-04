// Sharing the member's location (ANDROID-PLAN §4 "Location", L1a; VAULT-MESSAGING §10.16): the platform's positions behind
// PositionProvider (android.location.LocationManager: the `fused` provider of Android 12 and later, which this app's minSdk
// guarantees; no Google Play services location API), and the foreground service of type `location` that keeps sharing with
// the screen off. The logic (cadence, stopping, pausing, resuming) is LocationSharing in :core:data.
//
// In both distributions: nothing here is Google's. The manifest asks for ACCESS_COARSE_LOCATION and ACCESS_FINE_LOCATION and
// never ACCESS_BACKGROUND_LOCATION (question 24): `:app:check<Variant>BackgroundLocation` fails a build that has it.
plugins {
    id("vettid.android.library")
}

dependencies {
    // LocationRepository, ServiceState, PositionProvider: the contract with the sharing logic.
    api(projects.core.data)
    // The "Location sharing" notification channel.
    implementation(projects.core.notify)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(projects.core.testing)
}

android {
    testOptions.unitTests {
        isIncludeAndroidResources = true
        // Robolectric's API 36 runtime reaches into the JDK's file descriptor internals.
        all { it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED") }
    }
}
