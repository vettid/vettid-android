# Android Release CI

Two GitHub Actions workflows drive builds for this repo:

| Workflow | File | Trigger | Secrets? | Output |
|---|---|---|---|---|
| **Android CI** | `.github/workflows/android-ci.yml` | every push / PR | none | unit tests, lint, debug APK + reports |
| **Android Release** | `.github/workflows/android-release.yml` | tag `v*` (publishes a Release) or manual run (artifact only) | signing keystore | signed `vettid-app-v<version>.apk` + `android-checksums.txt` |

The signing config in `app/build.gradle.kts` already reads the keystore from
environment variables, so CI only has to decode the keystore and set them.

## One-time setup (needs repo admin + `gh auth login`)

Authenticate the CLI first (interactive — run it yourself):

```bash
gh auth login
```

### 1. Load the signing secrets

Run from the repo root, with the release keystore file handy. `gh secret set`
reads the value from stdin so nothing lands in your shell history.

```bash
REPO=vettid/vettid-android

# The keystore itself, base64-encoded (binary -> text for the secret store):
base64 -w0 release.keystore | gh secret set VETTID_KEYSTORE_BASE64 --repo "$REPO"

# Passwords / alias (you'll be prompted; paste, then Ctrl-D):
gh secret set VETTID_KEYSTORE_PASSWORD --repo "$REPO"
gh secret set VETTID_KEY_ALIAS        --repo "$REPO"
gh secret set VETTID_KEY_PASSWORD     --repo "$REPO"

# Optional runtime cert-pin (SECURITY #49). Compute it from the keystore:
keytool -list -v -keystore release.keystore -alias <your-alias> \
  | awk '/SHA256:/ { print $2 }' | tr -d ':' \
  | gh secret set VETTID_SIGNING_CERT_SHA256 --repo "$REPO"
```

Verify they're all set:

```bash
gh secret list --repo vettid/vettid-android
```

### 2. Cut a release

Bump `versionName` (and `versionCode`) in `app/build.gradle.kts`, commit, then
tag — the tag is what publishes the Release:

```bash
git tag v1.0.50
git push origin v1.0.50
```

The workflow builds `assembleProductionRelease`, renames the APK to
`vettid-app-v1.0.50.apk`, writes `android-checksums.txt`, and creates the
GitHub Release with both attached.

### Test runs without publishing

Use the manual trigger to build a signed APK without creating a Release — the
APK is uploaded as a run artifact you can download from the Actions tab:

```bash
gh workflow run "Android Release" --repo vettid/vettid-android
gh run watch --repo vettid/vettid-android   # follow the latest run
```

## Notes & guardrails

- **Debug-signing guard:** the release job fails if the produced APK is
  debug-signed (which happens when a signing secret is missing/wrong), so a
  silently-unsigned build can never reach a Release.
- **`automation` flavor is never released** — `app/build.gradle.kts` throws on
  `assembleAutomationRelease` (it sets `SKIP_ATTESTATION=true`). CI only builds
  the `production` flavor.
- **Fork-PR safety:** the CI workflow uses no secrets; the release workflow
  only runs on tags/dispatch in this repo, so signing material is never exposed
  to PRs from forks.
- **Supply chain:** the workflows use only first-party actions (`actions/*`,
  `gradle/actions`) plus the runner's built-in `gh`. For stricter posture,
  pin those actions to commit SHAs instead of `@v4`.
- **The Android release is a GitHub artifact, not an AWS deploy.** Testers
  sideload the APK from the Releases page. The AWS/enclave deploy is a separate
  pipeline in `vettid-dev`.
