// Wallet W1 (ANDROID-PLAN §4 "Wallet", question 19): the Bitcoin side of the wallet on ACINQ bitcoin-kmp, behind this module's
// boundary (nothing outside :core:chain sees a bitcoin-kmp type). The phone is watch-only: public keys only, no signing here.
//
// NOT part of a production build yet: the app links this module into its debug, devStack and staging builds only
// (app/build.gradle.kts, `vettidWalletInRelease`), and `:app:check<Variant>Wallet` fails a release APK that carries any of it.
import java.security.MessageDigest

plugins {
    id("vettid.android.library")
}

// A pinned version is not a pinned file: the build refuses any other bytes than the ones that were inspected (the same rule as
// :core:calls). The four runtime artifacts, by file name; the native libraries inside the AAR are pinned again in the APK
// (app/build.gradle.kts, WalletApkCheck).
val bitcoinKmpVersion = libs.versions.bitcoinKmp.get()
val secp256k1Version = libs.versions.secp256k1Kmp.get()
val chainArtifacts = mapOf(
    "bitcoin-kmp-jvm-$bitcoinKmpVersion.jar" to "581d9285427fc218c675cfccd0b41956a37e277cd4ac31d70eabf72e539a8ea9",
    "secp256k1-kmp-jvm-$secp256k1Version.jar" to "7422220226231cdf1676702661fe50b7615393fce842411813732f6bf67330bd",
    "secp256k1-kmp-jni-common-$secp256k1Version.jar" to "1bbb5bf58ecfa5a2ff499f621721387ef9b1df62b0786f5c3799c3d90410feba",
    "secp256k1-kmp-jni-android-$secp256k1Version.aar" to "a4d75fc5221aee205de869c495983699a943e44ee0331142494296303037e090",
)
val chainArtifactFiles = configurations.detachedConfiguration(
    dependencies.create("fr.acinq.bitcoin:bitcoin-kmp-jvm:$bitcoinKmpVersion@jar"),
    dependencies.create("fr.acinq.secp256k1:secp256k1-kmp-jvm:$secp256k1Version@jar"),
    dependencies.create("fr.acinq.secp256k1:secp256k1-kmp-jni-common:$secp256k1Version@jar"),
    dependencies.create("fr.acinq.secp256k1:secp256k1-kmp-jni-android:$secp256k1Version@aar"),
).apply { isTransitive = false }

val checkChainArtifacts = tasks.register("checkChainArtifacts") {
    val files = chainArtifactFiles.incoming.files
    val expected = chainArtifacts
    inputs.files(files)
    inputs.property("sha256", expected.toString())
    val marker = layout.buildDirectory.file("checks/chain-artifacts.sha256")
    outputs.file(marker)
    doLast {
        val actual = files.files.associate { f ->
            val digest = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            f.name to digest.digest().joinToString("") { "%02x".format(it) }
        }
        check(actual == expected) { "bitcoin-kmp artifacts are $actual, expected $expected (the pinned library changed)" }
        marker.get().asFile.writeText(actual.entries.sortedBy { it.key }.joinToString("\n") { "${it.value}  ${it.key}" } + "\n")
    }
}
tasks.named("preBuild") { dependsOn(checkChainArtifacts) }

dependencies {
    // bitcoin-kmp publishes a JVM artifact (no Android one); it brings secp256k1-kmp (Kotlin) and, as ACINQ document,
    // the application picks the JNI binding: the Android one (a small C library, libsecp256k1-jni.so) here.
    implementation(libs.bitcoin.kmp)
    implementation(libs.secp256k1.jni.android)
    // Reading the vault's `wallet.psbt.inspect` summary (JSON values only; no serialization plugin).
    implementation(libs.kotlinx.serialization.json)

    // JVM unit tests run on the host: the JNI binding for desktop JVMs (linux x86-64 and others) is declared first, so
    // that its loader is the one on the test classpath.
    testImplementation(libs.secp256k1.jni.jvm)
}
