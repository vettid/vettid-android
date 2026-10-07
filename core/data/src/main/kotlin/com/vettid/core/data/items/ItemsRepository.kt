package com.vettid.core.data.items

import kotlinx.coroutines.flow.StateFlow

/** Whether the item list has been read since the vault opened. */
enum class ListLoad { NOT_LOADED, LOADING, LOADED, FAILED }

/**
 * The member's items (VAULT-MESSAGING §10.7; the "Vault" screens, ANDROID-PLAN 0.1.11). Every failure is a
 * [com.vettid.core.data.vault.VaultFailure]: a wrong credential password is [com.vettid.core.data.vault.FailureKind.BAD_PASSWORD],
 * its backoff [com.vettid.core.data.vault.FailureKind.BACKOFF] with `retryAfterSeconds`. Critical items are credential
 * operations (§3.5.3): their content travels sealed to a UTK, their values come back sealed to a one-time reply key.
 */
@Suppress("TooManyFunctions")
interface ItemsRepository {
    /** Every item's metadata (no values), as last listed; refreshed on `item.changed`, `item.deleted` and `tag.changed`. */
    val items: StateFlow<List<ItemSummary>>

    val load: StateFlow<ListLoad>

    /** Reads the whole list (`item.list`, pages of 500). */
    suspend fun refresh()

    /** `item.get`: a `data` item with its values; a `secret` or `critical` one without. */
    suspend fun get(itemId: String): ItemDetail

    /** `item.reveal` of a `secret` (or `data`) item: its values, audited as `item.revealed`. */
    suspend fun reveal(itemId: String): ItemDetail

    /** `item.reveal` of a `critical` item with the credential password: the values sealed to a one-time reply key, opened here. */
    suspend fun revealCritical(itemId: String, password: String): ItemDetail

    /** Creates a `data` or `secret` item; returns its id. The draft must pass [ItemChecks.check]. */
    suspend fun create(draft: ItemDraft): String

    /** Creates a `critical` item inside the credential with the password; returns its id. */
    suspend fun createCritical(draft: ItemDraft, password: String): String

    /** Replaces a `data` or `secret` item's content and tags (`version` is the one it was based on; `conflict` otherwise). */
    suspend fun update(itemId: String, version: Long, draft: ItemDraft): Long

    /** Replaces a `critical` item's content (sealed) and tags with the password. */
    suspend fun updateCritical(itemId: String, version: Long, draft: ItemDraft, password: String): Long

    /** `item.delete`; a `critical` item needs the [password]. Withdraws it from every share rule and revokes its grants. */
    suspend fun delete(itemId: String, sensitivity: Sensitivity, password: String? = null)

    /** `item.tag` (no password, for every sensitivity). */
    suspend fun setTags(itemId: String, version: Long, tags: List<String>): Long

    /**
     * `item.sensitivity`: `data` ↔ `secret` is a metadata change; to or from `critical` is a credential operation
     * ([password] required). The app warns before an item leaves `critical`.
     */
    suspend fun setSensitivity(itemId: String, version: Long, from: Sensitivity, to: Sensitivity, password: String? = null): Long

    /**
     * Hands a revealed critical item from its detail screen to its edit screen, so that editing does not open the
     * credential twice. Kept in memory only, for a short time; [takeOpened] returns it once.
     */
    fun keepOpened(detail: ItemDetail)

    fun takeOpened(itemId: String): ItemDetail?
}
