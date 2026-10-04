/**
 * Attestation checks of the app (VAULT-MESSAGING 0.10.0):
 * - `nitro`: AWS Nitro attestation documents (COSE_Sign1, ES384, chain to the
 *   pinned AWS root at the document's timestamp), ported from the v1 app's
 *   NitroAttestationVerifier and aligned with vettid-vault `vms/nitro`;
 * - `manifest`: the signed release manifest (§11.10.1, manifest-by-hash
 *   0.10.0) with pinned keys A/B, and the release rules of §11.2 / §11.10.6
 *   (successor of the v1 PcrConfigManager);
 * - [EnclaveVerifier]: ETK descriptor and vault.enrolled checks (§11.2, §11.3);
 * - `android`: the app's own Android key attestation (§11.7), parsed for
 *   local checks; the enclave is the verifier.
 */
package com.vettid.core.attestation
