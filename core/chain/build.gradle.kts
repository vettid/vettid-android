// Wallet W1 (ANDROID-PLAN §4 "Wallet", question 19): the Bitcoin side of the wallet on ACINQ bitcoin-kmp, behind this module's
// boundary (nothing outside :core:chain sees a bitcoin-kmp type). The phone is watch-only: public keys only, no signing here.
plugins {
    id("vettid.android.library")
}

dependencies {
    // bitcoin-kmp publishes a JVM artifact (no Android one); it brings secp256k1-kmp (Kotlin) and, as ACINQ document,
    // the application picks the JNI binding: the Android one (a small C library, libsecp256k1-jni.so) here.
    implementation(libs.bitcoin.kmp)
    implementation(libs.secp256k1.jni.android)
    // Reading the vault's `wallet.psbt.inspect` summary (JSON values only; no serialization plugin).
    implementation(libs.kotlinx.serialization.json)

    // JVM unit tests run on the host: the JNI binding for desktop JVMs (linux x86-64 and others) is declared first, so
    // that its loader is the one on the test classpath.
    testImplementation(libs.secp256k1.jni.jvm)
}
