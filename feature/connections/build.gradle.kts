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
}
