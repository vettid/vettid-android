package com.vettid.core.data.vault

import com.vettid.core.altchan.CanaryManifestException
import com.vettid.core.altchan.CanaryManifestSource
import com.vettid.core.altchan.CanaryManifests
import com.vettid.core.attestation.AttestationException
import com.vettid.core.attestation.manifest.ManifestVerifier
import com.vettid.core.attestation.manifest.ReleaseManifest
import com.vettid.core.attestation.manifest.ServedManifest
import com.vettid.core.keystore.KeystoreException
import com.vettid.core.vault.DeviceStateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

/**
 * The installed canary manifest ([CanaryManifestRepository]) and the [CanaryManifestSource] the alternate
 * channel reads it from: the served document's exact bytes in [store] (a Keystore-encrypted file in the app,
 * erased by a wipe like everything else).
 *
 * [verifier] gives the verifier of this build's pinned manifest keys (the same as the published manifest's);
 * [seen] the highest manifest serial this phone has used for its vault (0 without one); [published] the
 * published manifest's served document, or null when nothing is served or it cannot be read.
 */
class CanaryManifestStore(
    private val store: DeviceStateStore,
    private val verifier: suspend () -> ManifestVerifier,
    private val seen: () -> Long,
    private val published: suspend () -> ByteArray?,
) : CanaryManifestRepository, CanaryManifestSource {
    private val flow = MutableStateFlow<CanaryManifestView?>(null)
    override val canaryManifest: StateFlow<CanaryManifestView?> = flow.asStateFlow()

    /** Reads what is installed into [canaryManifest] (at start; a document that no longer verifies is dropped). */
    suspend fun load() {
        val b = served() ?: return
        flow.value = try {
            view(b, verifier().verify(b))
        } catch (_: AttestationException) {
            retire(b)
            null
        }
    }

    override suspend fun checkCanaryManifest(served: ByteArray): CanaryManifestView = view(served, check(served))

    @Synchronized
    private fun save(served: ByteArray, v: CanaryManifestView) {
        store.save(served)
        flow.value = v
    }

    override suspend fun installCanaryManifest(served: ByteArray): CanaryManifestView {
        val v = view(served, check(served))
        try {
            save(served.copyOf(), v)
        } catch (e: KeystoreException) {
            throw VaultFailure(FailureKind.OTHER, "keystore", cause = e)
        }
        return v
    }

    @Synchronized
    override fun served(): ByteArray? = try {
        store.load()
    } catch (_: KeystoreException) {
        null
    }

    @Synchronized
    override fun retire(served: ByteArray) {
        val cur = served() ?: return
        if (cur.contentEquals(served)) clear()
    }

    override suspend fun removeCanaryManifest() = clear()

    @Synchronized
    private fun clear() {
        runCatching { store.clear() }
        flow.value = null
    }

    @Suppress("ThrowsCount") // one VaultFailure code per refusal
    private suspend fun check(served: ByteArray): ReleaseManifest {
        if (served.size > ReleaseManifest.MAX_SERVED) throw VaultFailure(FailureKind.MANIFEST, CanaryManifestRepository.CODE_TOO_LARGE)
        val v = verifier()
        val publishedSerial = try {
            published()?.let { v.verify(it).serial }
        } catch (e: CancellationException) {
            throw e
        } catch (_: AttestationException) {
            null
        } catch (_: IOException) {
            null
        }
        return try {
            CanaryManifests.check(v, served, seen(), publishedSerial)
        } catch (e: AttestationException) {
            val code = when (e.kind) {
                AttestationException.Kind.MANIFEST_KEY, AttestationException.Kind.SIGNATURE -> CanaryManifestRepository.CODE_SIGNATURE
                else -> CanaryManifestRepository.CODE_FORMAT
            }
            throw VaultFailure(FailureKind.MANIFEST, code, cause = e)
        } catch (e: CanaryManifestException) {
            val code = when (e.reason) {
                CanaryManifestException.Reason.OLDER -> CanaryManifestRepository.CODE_OLDER
                CanaryManifestException.Reason.PUBLISHED -> CanaryManifestRepository.CODE_PUBLISHED
            }
            throw VaultFailure(FailureKind.MANIFEST, code, cause = e)
        }
    }

    /** [m] verified from [served]. */
    private fun view(served: ByteArray, m: ReleaseManifest): CanaryManifestView = CanaryManifestView(
        serial = m.serial,
        keyId = ServedManifest.parse(served).keyId,
        sha256 = m.sha256Hex,
        releases = m.releases.map { ReleaseView(it.number, it.pcr0, it.status.wire, it.endsAt, it.notes) },
    )

}
