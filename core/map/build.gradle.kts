// The embedded map of the position screen (ANDROID-PLAN §4 "Location", L2; owner decision 2026-11-05: OpenFreeMap is the default
// tile server). The only module that depends on MapLibre Native (BSD-2-Clause, the OpenGL ES build): no Google code, so the `foss`
// build works as `full` does (`:app:checkFossReleaseApk`). The SDK's location component is never used; the position is drawn
// here. Everything the map is told is in memory ([MapContent]); the SDK's ambient cache is held to 0 bytes and its logging is off
// ([MapSdk], held by MapSdkTest and MapPrivacyTest). Native code stays out of unit tests behind [MapSurface].
plugins {
    id("vettid.android.compose")
}

dependencies {
    implementation(libs.maplibre.sdk)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
}
