// Wallet W0 spike (ANDROID-PLAN §4 "Wallet", question 19): the Bitcoin library behind the wallet, BDK. SPIKE: not for merge.
plugins {
    id("vettid.android.library")
}

dependencies {
    implementation(libs.bdk.android)
    // bdk-android asks for JNA 5.14.0; the AAR of 5.19.1 replaces it (16 KB page alignment, see docs/spikes/WALLET-W0.md).
    implementation("${libs.jna.get()}@aar")

    // JVM unit tests run on the host: bdk-jvm has the same classes plus linux-x86-64 native code; the Android AAR's
    // classes come first on the classpath only if declared first, so the host jar is declared ahead of it for tests.
    testImplementation(libs.bdk.jvm)
    testImplementation(libs.jna)
}
