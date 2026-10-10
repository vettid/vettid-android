// The notification modes (ANDROID-PLAN 0.1.23 D7, Notification modes): the on-phone service, the channels, the
// Mapping and Content rules, and the PushProvider interface (the FCM provider is :core:push-fcm).
plugins {
    id("vettid.android.library")
    id("vettid.hilt")
}

dependencies {
    api(projects.core.data)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.kotlinx.coroutines.test)
    // Texts from string resources under Robolectric.
    testImplementation(libs.robolectric)
}

android {
    testOptions.unitTests {
        isIncludeAndroidResources = true
        // Robolectric's API 36 runtime reaches into the JDK's file descriptor internals.
        all { it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED") }
    }
}
