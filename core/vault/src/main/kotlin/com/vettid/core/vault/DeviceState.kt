package com.vettid.core.vault

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The device's persistent state (secret: it holds session keys). Stored only
 * through a [DeviceStateStore], encrypted at rest by the app (`:core:data`).
 * The device's own private keys are not in it: they come from the Keystore
 * as [DeviceSecrets].
 */
@Serializable
internal data class DeviceState(
    val role: String,
    val name: String,
    val relayUrl: String,
    var vaultId: String? = null,
    var deviceId: String? = null,
    var vault: VaultRecord? = null,
    /** Relay msg_ids seen (16 days, §8.2), with when. */
    val seen: MutableMap<String, Long> = mutableMapOf(),
    /** Inner ids of the vault's messages seen (16 days, §8.2). */
    val seenInner: MutableMap<String, Long> = mutableMapOf(),
    /** Expiry of the standing token last given to the vault (epoch ms). */
    var issuedExpMs: Long = 0,
    /** The alternate-channel state ([com.vettid.core.altchan.AltState] JSON). */
    var alt: String? = null,
    var credential: CredentialCopy? = null,
    val utks: MutableList<UtkRecord> = mutableListOf(),
    var recovery: RecoveryRecord? = null,
    val outbox: MutableList<OutboxEntry> = mutableListOf(),
)

/** What the device knows about its vault. Keys are standard base64. */
@Serializable
internal data class VaultRecord(
    var ik: String,
    var kem: String,
    var relayUrl: String,
    var mailbox: String,
    var relayPk: String,
    var token: String = "",
    var tokenExpMs: Long = 0,
    /** The keyring's export (base64 of its JSON). */
    var sessions: String = "",
    var suite: Int = 0,
)

/** This app's copy of the Protean Credential (§3.5): the sealed blob, useless without the vault's CEK and the password. */
@Serializable
internal data class CredentialCopy(val blob: String, val version: Long)

/** An issued one-time transaction key (§3.5.4). */
@Serializable
internal data class UtkRecord(val id: String, val ek: String, val expiresAtMs: Long)

/** Set while this app recovers a vault (§11.11). */
@Serializable
internal data class RecoveryRecord(val recoveryId: String, val requestId: String = "")

/** A deposit not yet answered 201 by the relay (§8.3: the entry goes once the relay accepted it). */
@Serializable
internal data class OutboxEntry(
    val id: String,
    val type: String,
    val relayUrl: String,
    val mailbox: String,
    val token: String,
    val envelope: String,
    var attempts: Int = 0,
)

internal val stateJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Where the device state is kept. [save] must be durable before it returns (ack after persist, §8.3). */
interface DeviceStateStore {
    fun load(): ByteArray?

    fun save(state: ByteArray)

    fun clear()
}

/** In memory only (tests). */
class InMemoryDeviceStateStore : DeviceStateStore {
    @Volatile
    private var b: ByteArray? = null

    override fun load(): ByteArray? = b?.copyOf()

    override fun save(state: ByteArray) {
        b = state.copyOf()
    }

    override fun clear() {
        b = null
    }
}
