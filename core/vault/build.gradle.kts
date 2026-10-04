plugins {
    id("vettid.android.library")
}

// kotlinx.serialization for message bodies and the persisted device state
// (the plugin comes with build-logic's classpath).
apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

dependencies {
    api(projects.core.crypto)
    api(projects.core.relay)
    api(projects.core.altchan)
    api(projects.core.attestation)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(projects.core.testing)
}
