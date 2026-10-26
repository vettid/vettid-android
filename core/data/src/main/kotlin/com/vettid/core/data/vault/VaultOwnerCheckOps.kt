package com.vettid.core.data.vault

import com.vettid.core.vault.OwnerCheckPassed
import com.vettid.core.vault.OwnerCheckStatus
import com.vettid.core.vault.Settings
import com.vettid.core.vault.VaultApi
import kotlinx.serialization.json.JsonElement

/** [OwnerCheckOps] over the vault client. */
internal class VaultOwnerCheckOps(private val api: VaultApi) : OwnerCheckOps {
    override suspend fun status(): OwnerCheckStatus? = api.status().ownerCheck

    override suspend fun statusAndRecovery(): Pair<OwnerCheckStatus?, com.vettid.core.vault.RecoveryStatus?> =
        api.status().let { it.ownerCheck to it.recovery }

    override suspend fun statusRead(): StatusRead = api.status().let { StatusRead(it.ownerCheck, it.recovery, it.timeScale) }

    override suspend fun check(pin: String, password: String, hold: Boolean?, holdOffUntil: String?): OwnerCheckPassed =
        api.ownerCheck(pin, password, hold, holdOffUntil)

    override suspend fun settingsGet(): Settings = api.settingsGet()

    override suspend fun settingsSet(version: Long, set: Map<String, JsonElement>) {
        api.settingsSet(version, set)
    }
}
