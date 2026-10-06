package com.vettid.core.data.vault

import com.vettid.core.attestation.manifest.ReleaseManifest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * A canary manifest as the member (VettID's tester) sees it before and after installing it: its serial, the
 * pinned key that signed it, the hash the requests name (§11.5) and its releases.
 */
data class CanaryManifestView(
    val serial: Long,
    val keyId: String,
    val sha256: String,
    val releases: List<ReleaseView>,
)

/**
 * The canary manifest of VAULT-RELEASES §10.1 step 9 (W10-READINESS P31/B5) on this device: a release
 * manifest signed with a pinned key but not published yet, loaded out of band, verified like the published
 * one and used instead of it only while its serial is higher ([com.vettid.core.altchan.CanaryManifests]).
 * Failures are [VaultFailure]s of kind [FailureKind.MANIFEST] with one of the `CODE_*` codes.
 */
interface CanaryManifestRepository {
    /** The installed canary manifest, or null. Cleared when the published manifest reaches its serial. */
    val canaryManifest: StateFlow<CanaryManifestView?>

    /** Verifies [served] (a served manifest document) without installing it, for the member's confirmation. */
    suspend fun checkCanaryManifest(served: ByteArray): CanaryManifestView

    /** Verifies [served] again and installs it, replacing any installed one. */
    suspend fun installCanaryManifest(served: ByteArray): CanaryManifestView

    /** Forgets the installed canary manifest (the published one is used again). */
    suspend fun removeCanaryManifest()

    companion object {
        /** Not a served manifest document, or a malformed manifest. */
        const val CODE_FORMAT = "canary_format"

        /** Signed with a key this build does not pin, or the signature does not verify. */
        const val CODE_SIGNATURE = "canary_signature"

        /** Its serial is lower than one this phone has already used. */
        const val CODE_OLDER = "canary_older"

        /** The published manifest already has this serial or a newer one. */
        const val CODE_PUBLISHED = "canary_published"

        /** Larger than a served manifest may be (§11.10.1: 90,112 bytes). */
        const val CODE_TOO_LARGE = "canary_too_large"
    }
}

/**
 * A served manifest document the app was handed (shared to it as a file, `application/json`), passed from the
 * activity to the root of the UI, which asks the member before anything is installed.
 */
class CanaryManifestInbox {
    private val flow = MutableStateFlow<ByteArray?>(null)
    val document: StateFlow<ByteArray?> = flow.asStateFlow()

    fun offer(served: ByteArray) {
        flow.value = served.copyOf()
    }

    fun consume() {
        flow.value = null
    }

    companion object {
        /**
         * Reads at most one byte more than a served manifest may have (§11.10.1, 90,112 bytes), so that a larger
         * file is refused as too large without being read to its end.
         */
        fun readBounded(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(BUFFER)
            val limit = ReleaseManifest.MAX_SERVED + 1
            while (out.size() < limit) {
                val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }

        private const val BUFFER = 8192
    }
}
