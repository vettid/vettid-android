plugins {
    id("vettid.android.library")
}

// TEST ONLY: helpers for tests against the local dev stack (devstack/README.md):
// the dev stack's endpoints and control API, the A2 exit scenario, a
// software attester under the dev enclave's TEST Android attestation CA, and
// in-memory fakes of the repositories for ViewModel tests.
// Only test configurations (and the debug-only devStack build) depend on this module.
dependencies {
    api(projects.core.vault)
    api(projects.core.data)
    api(libs.bouncycastle.bcprov)
    api(libs.bouncycastle.bcpkix)
}
