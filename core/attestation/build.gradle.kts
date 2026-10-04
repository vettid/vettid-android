plugins {
    id("vettid.android.library")
}

// The §16 vectors live once, in :core:crypto's test resources; the host
// (unit) tests read them from the classpath.
androidComponents {
    onVariants { variant ->
        variant.hostTests.values.forEach { it.sources.resources?.addStaticSourceDirectory("../crypto/src/test/resources") }
    }
}

dependencies {
    implementation(projects.core.crypto)
    // ASN.1 parsing of the Android key attestation extension (bcprov, as in :core:crypto).
    implementation(libs.bouncycastle.bcprov)
    testImplementation(libs.bouncycastle.bcpkix)
}
