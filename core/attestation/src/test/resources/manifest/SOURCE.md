# Staging manifest fixture

`staging-pcr-manifest-serial1.json` is copied verbatim from the vettid.org
repository, `vault/staging/pcr-manifest.json`: the document served at
`https://staging.vettid.org/.well-known/vettid/pcr-manifest.json` (serial 1,
staging release S1 active), signed by the staging channel's key A
(`key_id` e9b3a403423120ac, `ManifestKeys.STAGING`). Public data.
`ManifestKeysTest` verifies it under the staging pin with the app's own
`ManifestVerifier`, and checks that the production pins reject it.
