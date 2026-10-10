// The FCM push provider (ANDROID-PLAN 0.1.23, Notification modes 4): the only module that will hold Firebase. Until
// VAULT-MESSAGING specifies `push.register` (N2) and the gateway has FCM credentials (N4) it is a stub that reports
// "not available yet", with no Firebase library, no google-services.json and no Google Services plugin, so that every
// build (CI included) is as before.
plugins {
    id("vettid.android.library")
}

dependencies {
    implementation(projects.core.notify)
    implementation(libs.kotlinx.coroutines.core)
}
