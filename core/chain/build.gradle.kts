// Wallet W0 spike (ANDROID-PLAN §4 "Wallet", question 19), second library: ACINQ bitcoin-kmp. SPIKE: not for merge.
plugins {
    id("vettid.android.library")
}

dependencies {
    // bitcoin-kmp publishes a JVM artifact (no Android one); it brings secp256k1-kmp (Kotlin) and, as ACINQ document,
    // the application picks the JNI binding: the Android one (a small C library, libsecp256k1-jni.so) here.
    implementation(libs.bitcoin.kmp)
    implementation(libs.secp256k1.jni.android)

    // JVM unit tests run on the host: the JNI binding for desktop JVMs (linux x86-64 and others) is declared first, so
    // that its loader is the one on the test classpath.
    testImplementation(libs.secp256k1.jni.jvm)
}
