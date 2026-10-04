plugins {
    id("vettid.jvm.library")
}

// The relay client (RELAY-PROTOCOL 0.5.0) is pure Kotlin/JVM: OkHttp for HTTP
// and WebSocket, coroutines, and :core:crypto for Ed25519. Its unit tests
// include the protocol's §9 vectors.
dependencies {
    api(projects.core.crypto)
    api(libs.okhttp)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
