// The call screens (CALLS-PLAN 0.1.1 §6.4, phase C1): outgoing, incoming, in call, the microphone prompt, and the
// test screen of the two-phone tests. Like :core:calls, NOT part of a production build yet: the app links this
// module into its debug, devStack and staging builds only (`:app:check<Variant>Calls`).
plugins {
    id("vettid.android.feature")
    id("vettid.hilt")
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.calls)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // The texts from string resources under Robolectric.
    testImplementation(libs.robolectric)
}

android {
    testOptions.unitTests {
        isIncludeAndroidResources = true
        // Robolectric's API 36 runtime reaches into the JDK's file descriptor internals.
        all { it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED") }
    }
}
