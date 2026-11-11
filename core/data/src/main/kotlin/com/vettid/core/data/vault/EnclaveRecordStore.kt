package com.vettid.core.data.vault

import com.vettid.core.attestation.CheckResult
import com.vettid.core.attestation.EnclaveCheck
import com.vettid.core.attestation.EnclaveRecord
import com.vettid.core.attestation.EnclaveRecords
import com.vettid.core.attestation.EnclaveRefusal
import com.vettid.core.attestation.manifest.ReleaseStatus
import com.vettid.core.vault.DeviceStateStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * The last enclave verification's record (ANDROID-PLAN 0.1.124 §5 "What the phone keeps"): each check's result, the values the
 * attestation screen shows and when it was made, in an encrypted state file in no-backup storage; replaced by the next
 * verification, erased by the wipe with the directory. Public material only. A file that does not read is no record (the
 * screen then says the phone has not verified the enclave yet), and the next verification replaces it.
 */
class EnclaveRecordStore(private val file: DeviceStateStore) : EnclaveRecords {
    private val current = MutableStateFlow(load())

    /** The last record, or null. */
    val record: StateFlow<EnclaveRecord?> = current.asStateFlow()

    override fun record(r: EnclaveRecord) {
        current.value = r
        // Best effort: the screen shows the record in memory either way; a failed write leaves the earlier one on disk.
        runCatching { file.save(json.encodeToString(Stored.serializer(), Stored.of(r)).encodeToByteArray()) }
    }

    /** The wipe: the record goes from memory and from disk. */
    fun forget() {
        current.value = null
        file.clear()
    }

    private fun load(): EnclaveRecord? = try {
        file.load()?.let { json.decodeFromString(Stored.serializer(), it.decodeToString()).record() }
    } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") _: Exception) {
        null
    }

    /** The stored form: names and numbers only. */
    @Serializable
    internal data class Stored(
        @SerialName("at_ms") val atMs: Long,
        /** The result of each check, by [EnclaveCheck] name. */
        val results: Map<String, String>,
        @SerialName("manifest_key_id") val manifestKeyId: String? = null,
        @SerialName("manifest_serial") val manifestSerial: Long? = null,
        val pcr0: String? = null,
        val pcr1: String? = null,
        val pcr2: String? = null,
        @SerialName("attested_at_ms") val attestedAtMs: Long? = null,
        @SerialName("not_after_ms") val notAfterMs: Long? = null,
        @SerialName("release_number") val releaseNumber: Long? = null,
        @SerialName("release_status") val releaseStatus: String? = null,
        @SerialName("release_ends_at_ms") val releaseEndsAtMs: Long? = null,
        val refusal: String? = null,
    ) {
        /**
         * The record again; a stored set of results that does not read as a run (a "Passed" after a check that did not
         * pass, an unknown name) is no record, so the screen never shows a pass that was not recorded in order.
         */
        @Suppress("ReturnCount") // each way a stored record fails to read is no record
        fun record(): EnclaveRecord? {
            val rs = EnclaveCheck.entries.associateWith { c ->
                results[c.name]?.let { v -> CheckResult.entries.firstOrNull { it.name == v } } ?: return null
            }
            if (results.keys != EnclaveCheck.entries.map { it.name }.toSet()) return null
            val passed = EnclaveCheck.entries.takeWhile { rs[it] == CheckResult.PASSED }.size
            if (rs != EnclaveRecord.results(passed, failed = rs[EnclaveCheck.entries.getOrNull(passed)] == CheckResult.FAILED)) return null
            return EnclaveRecord(
                at = Instant.ofEpochMilli(atMs),
                results = rs,
                manifestKeyId = manifestKeyId,
                manifestSerial = manifestSerial,
                pcr0 = pcr0,
                pcr1 = pcr1,
                pcr2 = pcr2,
                attestedAt = attestedAtMs?.let(Instant::ofEpochMilli),
                notAfter = notAfterMs?.let(Instant::ofEpochMilli),
                releaseNumber = releaseNumber,
                releaseStatus = releaseStatus?.let { s -> ReleaseStatus.entries.firstOrNull { it.name == s } },
                releaseEndsAt = releaseEndsAtMs?.let(Instant::ofEpochMilli),
                refusal = refusal?.let { s -> EnclaveRefusal.entries.firstOrNull { it.name == s } ?: return null },
            )
        }

        override fun toString(): String = "EnclaveRecordStore.Stored"

        companion object {
            fun of(r: EnclaveRecord) = Stored(
                atMs = r.at.toEpochMilli(),
                results = EnclaveCheck.entries.associate { it.name to r.result(it).name },
                manifestKeyId = r.manifestKeyId,
                manifestSerial = r.manifestSerial,
                pcr0 = r.pcr0,
                pcr1 = r.pcr1,
                pcr2 = r.pcr2,
                attestedAtMs = r.attestedAt?.toEpochMilli(),
                notAfterMs = r.notAfter?.toEpochMilli(),
                releaseNumber = r.releaseNumber,
                releaseStatus = r.releaseStatus?.name,
                releaseEndsAtMs = r.releaseEndsAt?.toEpochMilli(),
                refusal = r.refusal?.name,
            )
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
