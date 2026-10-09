plugins {
    id("vettid.android.feature")
    id("vettid.hilt")
}

dependencies {
    implementation(projects.core.data)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(projects.core.testing)
    // The feed's texts from string resources, and the screen, under Robolectric.
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
