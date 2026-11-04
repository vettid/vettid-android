package com.vettid.core.data.location

import com.vettid.core.data.KeystoreFileStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * What the app keeps on disk of its location sharing (ANDROID-PLAN §4 "Local state"): the outgoing `share_id`s with their
 * `connection_id` and `expires_at`, and nothing else. **No position, and no precision, interval or name:** these three are all
 * that is needed to resume or stop sending (the rest is read again from `location.share.list`). `LocationPrivacyTest` holds
 * the schema to strings and one number.
 */
@Serializable
data class StoredShare(
    @SerialName("share_id") val shareId: String,
    @SerialName("connection_id") val connectionId: String,
    @SerialName("expires_at_ms") val expiresAtMs: Long,
) {
    override fun toString(): String = "StoredShare"
}

/** Where the [StoredShare]s are kept (the app's encrypted settings); cleared when they end. */
interface ShareStore {
    /** The stored shares; empty when there are none or the file cannot be read (a leftover that cannot be read is dropped). */
    fun load(): List<StoredShare>

    fun save(shares: List<StoredShare>)
}

/** [ShareStore] in a Keystore-encrypted file (`location-shares.bin` in the app's no-backup directory). */
class KeystoreShareStore(private val file: KeystoreFileStore) : ShareStore {
    override fun load(): List<StoredShare> = try {
        file.load()?.let { json.decodeFromString(ListSerializer(StoredShare.serializer()), it.decodeToString()) }.orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    override fun save(shares: List<StoredShare>) {
        if (shares.isEmpty()) {
            file.clear()
        } else {
            file.save(json.encodeToString(ListSerializer(StoredShare.serializer()), shares).encodeToByteArray())
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
