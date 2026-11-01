// Wallet W1c (ANDROID-PLAN §4 "Wallet"; VAULT-MESSAGING §10.18): the wallets of the member's vault as the app holds them in memory,
// the check of the app's own addresses against the vault's, the look-ups on the chain server (:core:chain), the used addresses
// reported back, and the backed-up flags. The vault side is `WalletApi.kt` in :core:vault, whose only caller this module is.
//
// NOT part of a production build yet: the app links this module where it links :core:chain (debug, devStack, staging; release only
// with `-PvettidWalletInRelease=true`), and `:app:check<Variant>Wallet` with `walletMarkers` fails a release APK that carries it.
plugins {
    id("vettid.android.library")
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.vault)
    implementation(projects.core.chain)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.test)
    // :core:chain's address derivation on the host JVM (see :core:chain's build file).
    testImplementation(libs.secp256k1.jni.jvm)
}
