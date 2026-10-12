// Calls (CALLS-PLAN 0.1.1 §4, §6.4): the media stack behind an interface. C0 SPIKE: this module holds only what the
// debug-only spike screen needs (docs/calls/C0-FRAME-FORMAT.md, the PR "Spike: calls C0"). Used by BOTH distributions:
// libwebrtc is BSD-3-Clause (with the patent grant), the frame cryptor is LiveKit's (Apache-2.0); nothing of Google
// Play services or Firebase is in the artifact (`:app:checkFossReleaseApk` reads its classes and its native libraries).
import java.security.MessageDigest

plugins {
    id("vettid.android.library")
}

// The media stack is a prebuilt binary from Maven Central (four native libraries in an AAR). A pinned version is not
// a pinned file: the build refuses any other bytes than the ones that were inspected for the spike.
val webrtcAarSha256 = "0a1627b1a48c2bc17d9a40d62fc47bd45166f44a311e95917f147c402de379b0"
val webrtcAar = configurations.detachedConfiguration(
    dependencies.create(libs.webrtc.sdk.get().let { "${it.module}:${it.version}@aar" }),
).apply { isTransitive = false }

val checkWebrtcArtifact = tasks.register("checkWebrtcArtifact") {
    val files = webrtcAar.incoming.files
    val expected = webrtcAarSha256
    inputs.files(files)
    inputs.property("sha256", expected)
    val marker = layout.buildDirectory.file("checks/webrtc-aar.sha256")
    outputs.file(marker)
    doLast {
        val aar = files.singleFile
        val digest = MessageDigest.getInstance("SHA-256")
        aar.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == expected) { "${aar.name}: sha256 $actual, expected $expected (the pinned media stack changed)" }
        marker.get().asFile.writeText("$actual\n")
    }
}
tasks.named("preBuild") { dependsOn(checkWebrtcArtifact) }

dependencies {
    // libwebrtc with the built-in frame cryptor (CALLS-PLAN D2, option A).
    implementation(libs.webrtc.sdk)
    // The artifact's POM declares no dependencies, but its classes carry androidx.annotation annotations.
    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
