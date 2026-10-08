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

    /**
     * Replaces a `data` or `secret` item's content and tags (`version` is the one it was based on; `conflict`
     * otherwise). Kept values (VAULT-MESSAGING 0.21.0 §10.7) go without `value`: a secret item is edited without
     * `item.reveal`. A vault that gave no `size` (before 0.21.0) cannot keep values: the app reads them
     * (`item.reveal`, never shown) and sends them all.
     */
    suspend fun update(itemId: String, version: Long, draft: ItemDraft): Long

    /**
     * Replaces a `critical` item's content (sealed) and tags with the password: one credential operation, the kept
     * values merged by the vault (§10.7 Kept values). A vault before 0.21.0 refuses fields without `value`
     * (`bad_request`); the app then opens the values with the same password and sends them all.
     */
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
     * What saving the item with [tags] would do to sharing (`item.put{dry_run: true}`, VAULT-MESSAGING 0.21.0 §10.7),
     * changing nothing: [itemId] null for a new item of [sensitivity]. Never carries the content, so a vault before
     * 0.21.0 refuses it (`bad_request`) rather than saving; the caller then falls back to the share rules.
     */
    suspend fun shareEffect(itemId: String?, version: Long?, sensitivity: Sensitivity, tags: List<String>): ShareEffect
}
