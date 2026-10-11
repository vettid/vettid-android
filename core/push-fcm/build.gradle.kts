// The FCM push provider (ANDROID-PLAN 0.1.30, Notification modes 4): the only module that holds Firebase. Only
// firebase-messaging: no BoM, no Google Services plugin, no google-services.json, no Analytics or Crashlytics.
// Firebase is initialised at runtime from the options the app passes in (Gradle properties, README "Google push");
// without them the provider reports "not available yet" and nothing of Firebase runs.
plugins {
    id("vettid.android.library")
    id("vettid.hilt")
}

dependencies {
    implementation(projects.core.notify)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.firebase.messaging)
    implementation(libs.play.services.base)
    // A wake's collect that outlasts the push message's 20 s continues as expedited work.
    implementation(libs.androidx.work.runtime)

    testImplementation(libs.kotlinx.coroutines.test)
}
