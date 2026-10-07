package com.vettid.core.data.vault

import com.vettid.core.vault.NameRequest
import com.vettid.core.vault.Profile
import com.vettid.core.vault.VaultApi

/** [ProfileOps] over the vault client. */
internal class VaultProfileOps(private val api: VaultApi) : ProfileOps {
    override suspend fun profileGet(): Profile = api.profileGet()

    override suspend fun profileSet(version: Long, name: String): Long = api.profileSet(version, name = name)

    override suspend fun accountNameSet(pin: String, password: String, firstName: String, lastName: String): NameRequest =
        api.accountNameSet(pin, password, firstName, lastName)
}
