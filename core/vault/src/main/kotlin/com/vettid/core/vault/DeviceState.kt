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
    /**
     * (ANDROID-PLAN 0.1.80) The registration this identity last had accepted, kept while a re-register for a later recovery is
     * undecided: a re-register that fails before the vault restores it, so the handshake's `ctx` stays the `recovery_id` this
     * identity LAST registered for.
     */
    var priorRecovery: RecoveryRecord? = null,
    val outbox: MutableList<OutboxEntry> = mutableListOf(),
    /**
     * The vault has shown this app a signer on its own `device.list` entry (VAULT-MESSAGING 0.27.0 §11.7.1): the
     * unlocks stop carrying the device key's certificate chain. False again after every new attestation (a new
     * key, whose signer the vault has not shown yet) and after an unlock answered `signer_refused`.
     */
    var signerSeen: Boolean = false,
    /**
     * (VAULT-MESSAGING 0.44.0 §11.14.9) Credential operations sent and not answered yet: inner id to the request's
     * `exp` (epoch ms, the vault's clock). Kept across restarts (the outbox may still deliver one): until each is
     * answered, or the vault's clock is past its `exp` plus a minute, no PIN or password change is sent, so that a
     * version one above the change's can only come from the change.
     */
    val unsettled: MutableMap<String, Long> = mutableMapOf(),
    /**
     * (W2-1) The `wallet.sign` requests among [unsettled]: inner id to the wallet and the credential version the request was sent
     * at. Kept across restarts so that no new send is made for that wallet while a signing may still apply: it is decided by
     * `credential.version` (past the sent version, the signing applied or never will) or by its `exp` ([VaultDevice.unsettledSigns]).
     */
    val unsettledSigns: MutableMap<String, SignMark> = mutableMapOf(),
    /**
     * The outbox's entries are tracked in [unsettled]. False only in a state written by an app version before 0.45.0:
     * its outbox entries carry no `exp` and are tracked at the first load ([VaultDevice.LEGACY_UNSENT]).
     */
    var outboxTracked: Boolean = false,
    /**
     * (A6 audit N2) The outbox holds only fire-and-forget messages ([VaultDevice.OUTBOX_TYPES]); requests are never
     * written to it. False only in a state written by an app version that queued requests there: they are dropped at
     * the first load ([VaultDevice.dropLegacyRequests]).
     */
    var requestsOutOfOutbox: Boolean = false,
    /**
     * (A6 audit N1) A direct transfer to this phone (§6.7.1) that this phone started and has not finished: kept from
     * its `hs.init` until the app has taken the credential over ([VaultDevice.finishTransferIn]) or drops it (a
     * rejection, a confirmed cancel, the 11-minute deadline). With the keyring stored after `hs.resp`, a `device.paired`
     * that arrives after a restart, or after the screen stopped waiting, still completes the transfer.
     */
    var transferIn: TransferInRecord? = null,
    /**
     * A transfer to this phone was dropped while unpaired (every device-level drop records it in [VaultDevice.dropPairing])
     * and the app has not yet deleted what it held for that transfer: the epoch-1 phone secret and the attested key
     * (VAULT-MESSAGING §6.7.1, §11.14.10 rule 5; [DeviceConfig.transferDropped]). Saved in the same write as the drop, so a
     * process killed between the drop and the app's delete runs the delete again: at the next handled message, and at the
     * latest before the next scan computes any secret ([VaultDevice.startTransfer]). Null once the app's delete held.
     */
    var droppedTransfer: DroppedTransferRecord? = null,
    /**
     * (VAULT-MESSAGING 0.88.3 §11.14.10 rule 5, "A second scan of the same code"; owner decision a, GC #6474) The code a
     * scan to this phone is under way for, from before its claim is fetched until its transfer is recorded
     * ([transferIn], which then carries the code) or the scan fails (a fetch or payload check that fails, an `hs.init`
     * not sent): a scan of the same code meanwhile is refused. Cleared at load: a scan the process's end cut off recorded
     * no transfer, so nothing would be there to go back to.
     */
    var scanCode: TransferCodeRecord? = null,
)

/** A scanned transfer code's relay `r` and claim id `c` ([DeviceState.scanCode]). */
@Serializable
internal data class TransferCodeRecord(val relay: String, val claim: String)

/**
 * What a dropped transfer left for the app to delete ([DeviceState.droppedTransfer]): the Keystore aliases of the device
 * attestation keys its `hs.init`s carried ([TransferInRecord.attestKey]; empty when the app named none). The phone key is
 * not named: the app holds it, and only for a transfer ([DeviceConfig.transferDropped]).
 */
@Serializable
internal data class DroppedTransferRecord(
    val attestKeys: List<String> = emptyList(),
    /** (GC #6761 review, Low 1) The transfer's phone key ("", 1) goes too; false: a scan that made none, only [attestKeys]. */
    val phoneKey: Boolean = true,
)

/**
 * (A6 audit N1) The transfer to this phone: when its `hs.init` went out (epoch ms), the SAS once `hs.fin` was sent,
 * and, once `device.paired{transfer: true}` was applied, the member's `user_guid` from it (null from a vault release
 * before 0.17.0).
 */
@Serializable
internal data class TransferInRecord(
    val startedMs: Long,
    val sas: String? = null,
    val paired: Boolean = false,
    val userGuid: String? = null,
    /**
     * The Keystore alias of the device attestation key this transfer's `hs.init` carried, as the app named it
     * ([VaultDevice.startTransfer]'s `attestedKey`); null when it named none. Deleted with the transfer when it is dropped.
     */
    val attestKey: String? = null,
    /**
     * The scanned code's relay `r` and claim id `c` (owner decision a, GC #6474; vettid.org#558): a scan of the same code
     * while this transfer is open is refused before the claim fetch and before any drop. Written with the transfer
     * itself, so a restart that finds the transfer finds them too; null in a record written before them.
     */
    val codeRelay: String? = null,
    val codeClaim: String? = null,
    /**
     * (#344's blind reviews, item 2) This transfer's scan made the phone key ("", 1) its `hs.init`'s secret came from:
     * false when it made none (a bundle before release R), so that its drop never deletes a ("", 1) key it did not make.
     * True in a record written before this field (as those drops did).
     */
    val phoneKey: Boolean = true,
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
    /**
     * The vault's rekeys this device answered with `hs.resp` whose `hs.fin` has not arrived (§6.5): kept across a
     * restart, as the vault keeps its `st.Awaiting`, so that the `hs.fin` still finds its handshake. Secret, like
     * [sessions].
     */
    var awaiting: MutableList<AwaitingRekey> = mutableListOf(),
    /**
     * (0.87.0, VAULT-MESSAGING §6.3.1 "Responder") The device's confirmed epoch with its vault (the id, standard base64): the
     * newest in which the vault sent it anything but `hs.fin`, while that is not the current epoch. Null: the current epoch.
     * Optional, as a state written before release 29 has none. Cleared by a dropped pairing and by a new pairing.
     */
    var confirmed: String? = null,
    /**
     * When the device last queued `hs.confirm` (epoch ms): it queues one again 12 days later while its current epoch is not
     * its confirmed one (0.88.1, "`hs.confirm` again"). Not scaled.
     */
    var confirmAtMs: Long? = null,
)

/**
 * A responder awaiting `hs.fin` ([com.vettid.core.crypto.session.Responder.export], base64) and when it began; (VAULT-MESSAGING
 * §6.3: one `hs.resp` per `hs.init`) the inner id of the `hs.init` it answers and the `hs.resp` it
 * deposited (base64), so that a redelivered `hs.init` gets the same bytes again. Both absent in a record written before.
 */
@Serializable
internal data class AwaitingRekey(val createdMs: Long, val state: String, val initId: String? = null, val resp: String? = null)

/** This app's copy of the Protean Credential (§3.5): the sealed blob, useless without the vault's CEK and the password. */
@Serializable
internal data class CredentialCopy(
    val blob: String,
    val version: Long,
    /**
     * The credential key's public key (§10.6 `key`) under which [blob] was issued, when an answer named it (a rotation,
     * a reset, or `credential.version` right after a fetch); null: not known, and then only the version is compared.
     */
    val key: String? = null,
)

/** An issued one-time transaction key (§3.5.4). */
@Serializable
internal data class UtkRecord(val id: String, val ek: String, val expiresAtMs: Long)

/**
 * Set while this app recovers a vault (§11.11). [credentialBackup]: the registered app's unlock result's
 * `credential_backup` (0.10.6, §11.11.5 step 1), null until that unlock or from a vault that does not send it.
 */
@Serializable
internal data class RecoveryRecord(val recoveryId: String, val requestId: String = "", val credentialBackup: Boolean? = null)

/**
 * (ANDROID-PLAN 0.1.80) What a re-register does to an identity's registration: the one accepted before stays until the new
 * one is, and a failed attempt restores it, so the handshake's `ctx` is the `recovery_id` the identity LAST registered for.
 */
internal data class RegistrationState(val recovery: RecoveryRecord?, val prior: RecoveryRecord?) {
    fun begin(next: RecoveryRecord) = RegistrationState(next, prior ?: recovery)

    fun drop() = RegistrationState(prior, null)

    fun confirm() = copy(prior = null)
}

/** A deposit not yet answered 201 by the relay (§8.3: the entry goes once the relay accepted it). */
@Serializable
internal data class OutboxEntry(
    val id: String,
    val type: String,
    val relayUrl: String,
    val mailbox: String,
    var token: String,
    val envelope: String,
    var attempts: Int = 0,
    /**
     * (GC #6847; VAULT-MESSAGING §8.6, RELAY-PROTOCOL §7.1) The token whose quota refused this entry (`quota_exceeded`,
     * `quota: token`): it is never sent under it again, and waits for another standing token for the vault.
     */
    var quotaToken: String? = null,
    /**
     * The inner id of the vault's message this entry answers (its `re`): set for the answer to the vault's own
     * `relay.token.refresh`, null for what the device sends of its own accord (its own refresh). Absent from an entry an earlier
     * version saved, which then reads as the device's own (the worst that does is hold back one own refresh until it has gone).
     */
    val re: String? = null,
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

/** A `wallet.sign` sent and not decided: its wallet and the credential version it was sent at. */
@Serializable
data class SignMark(val walletId: String, val version: Long) {
    override fun toString(): String = "SignMark"
}
