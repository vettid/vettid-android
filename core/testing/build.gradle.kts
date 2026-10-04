plugins {
    id("vettid.android.library")
}

// TEST ONLY: helpers for tests against the local dev stack (devstack/README.md):
// the dev stack's endpoints and control API, the A2 exit scenario, and a
// software attester under the dev enclave's TEST Android attestation CA.
// Only test configurations depend on this module; the app never does.
dependencies {
    api(projects.core.vault)
    api(libs.bouncycastle.bcprov)
    api(libs.bouncycastle.bcpkix)
}
