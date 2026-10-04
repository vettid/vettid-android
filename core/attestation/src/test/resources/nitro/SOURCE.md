# Nitro attestation fixture

`aws-attestation-2025-09-16.b64` is copied verbatim from vettid-vault
`vms/nitro/testdata/`. It is a real AWS Nitro Enclaves attestation document
(base64), produced by the NSM of an enclave on `i-02447a481344e9ec0` in
us-east-1 on 2025-09-16: public data (PCRs, an ephemeral public key,
user_data, the AWS certificate chain), originally from the test fixtures of
tkhq/rust-sdk (`proofs/src/lib.rs`, Apache-2.0, https://github.com/tkhq/rust-sdk).
It shows the NSM's actual encoding: an untagged COSE_Sign1 whose payload is
an indefinite-length CBOR map, with `nonce` as null.
