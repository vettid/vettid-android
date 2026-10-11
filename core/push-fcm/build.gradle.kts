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
    // Proprietary (Google): named here and nowhere else, not in the version catalog, so that the `foss` distribution
    // and an F-Droid build without this module carry no trace of them. No BoM, no Google Services plugin, no
    // google-services.json; no other Firebase product (no Analytics, no Crashlytics).
    implementation("com.google.firebase:firebase-messaging:25.1.3")
    // GoogleApiAvailability (is Google Play services there and current). Already a runtime dependency of
    // firebase-messaging at this version; named so that this module can compile against it.
    implementation("com.google.android.gms:play-services-base:18.9.0")
    // A wake's collect that outlasts the push message's 20 s continues as expedited work.
    implementation(libs.androidx.work.runtime)

    testImplementation(libs.kotlinx.coroutines.test)
}
