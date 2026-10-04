package com.vettid.core.testing

import com.vettid.core.altchan.AltChannelFlow
import com.vettid.core.altchan.Attester
import com.vettid.core.altchan.MemberApiClient
import com.vettid.core.altchan.MemberAuth
import com.vettid.core.altchan.Slot
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.vault.DeviceConfig
import com.vettid.core.vault.DeviceSecrets
import com.vettid.core.vault.DeviceStateStore
import com.vettid.core.vault.ItemContent
import com.vettid.core.vault.ItemField
import com.vettid.core.vault.VaultApi
import com.vettid.core.vault.VaultDevice
import com.vettid.core.vault.VaultJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.Duration

/**
 * TEST ONLY. The phase A2 exit scenario (ANDROID-PLAN §6) against the local
 * dev stack: enroll a vault through the member API (PIN; credential password),
 * lock and unlock it, create a critical item and read it back with the
 * password, connect to the vaultctl peer through an invitation, and exchange
 * a message both ways. Shared by the instrumented test on the phone and the
 * JVM test on the host.
 */
class ExitScenario(
    private val stack: DevStack,
    private val attester: Attester,
    private val secrets: DeviceSecrets,
    private val store: DeviceStateStore,
    private val log: (String) -> Unit,
) {
    val guid = "android-" + Ulid.new().lowercase()

    @Suppress("LongMethod")
    suspend fun run(scope: CoroutineScope) {
        val trust = stack.trust()
        val cfg = DeviceConfig(
            name = "Pixel test", relayUrl = stack.relayUrl, http = stack.http, store = store, trust = trust,
            pollWait = Duration.ofSeconds(10),
        )
        val device = VaultDevice.create(cfg, secrets)
        device.start(scope)
        val vault = VaultApi(device)
        val member = MemberApiClient(stack.apiBase, stack.manifestUrl, stack.http, MemberAuth.Bearer(guid))
        val flow = AltChannelFlow(member, trust)
        try {
            // --- enroll (§11.3): PIN, then the first handshake and the credential password ---
            val enrolled = flow.enroll(device, guid, PIN, attester)
            check(enrolled.ok) { "enroll refused: ${enrolled.code}" }
            log("enrolled vault ${enrolled.vaultId} on ${enrolled.instanceId}")
            device.awaitEnrolled()
            device.completeEnrollment()
            log("paired as device ${device.deviceId}")
            vault.credentialCreate(PASSWORD)
            vault.enrollConfirm()
            val st = vault.status()
            check(st.vaultId == enrolled.vaultId && !st.provisional) { "status $st" }
            log("credential created (version ${device.credentialVersion}), vault confirmed")

            // --- lock, then unlock with the PIN (§11.4) ---
            check(flow.lock(enrolled.vaultId).status == Slot.DONE)
            waitFor("vault locked") { member.vaultStatus()?.state == "locked" }
            val u = flow.unlock(device, guid, PIN, attester)
            check(u.ok) { "unlock refused: ${u.result.code}" }
            log("unlocked: state_seq ${u.result.stateSeq}, release ${u.result.releaseNumber}")
            check(vault.status().vaultId == enrolled.vaultId)

            // --- a critical item (§10.7): a credential operation with the password ---
            val phrase = "abandon ability able about above absent absorb abstract absurd abuse access accident"
            val ref = vault.itemPutCritical(
                PASSWORD,
                ItemContent(
                    "Wallet phrase",
                    category = "crypto_wallet",
                    fields = listOf(ItemField(label = "Phrase", kind = "password", value = JsonPrimitive(phrase))),
                ),
                tags = listOf("wallet"),
            )
            val item = vault.itemGet(ref.itemId)
            check(item.sensitivity == "critical" && item.fields.single().value == null) { "item.get exposed a critical value" }
            val revealed = vault.itemRevealCritical(PASSWORD, ref.itemId)
            val value = revealed.json()["fields"]!!.jsonArray.single().jsonObject["value"]!!
            revealed.wipe()
            check(value == JsonPrimitive(phrase)) { "revealed value differs" }
            log("critical item ${ref.itemId} stored and revealed (credential version ${device.credentialVersion})")

            // --- connect to the vaultctl peer through an invitation (§6.4) ---
            val invite = vault.inviteCreate()
            val accepted = stack.peerRequest("connection.invite.accept", buildJsonObject { put("link", invite.link) })
            val peerConn = (accepted["connection_id"] as JsonPrimitive).content
            val pending = vault.awaitEvent("connection.request.pending")
            log("connection request, SAS ${VaultJson.str(pending, "sas")}")
            vault.connectionApprove(VaultJson.str(pending, "pending_id")!!)
            val added = vault.awaitEvent("connection.event") { VaultJson.str(it, "event") == "added" }
            val conn = VaultJson.str(added, "connection_id")!!
            stack.peerEvent("connection.event", mapOf("event" to "added"))
            log("connected: $conn (peer side $peerConn)")

            // --- messages both ways (§10.5) ---
            val out = "hello from the Android app ${Ulid.new()}"
            vault.messageSend(conn, out)
            val got = stack.peerEvent("message.new", mapOf("text" to out))
            check(VaultJson.str(got, "direction") == "in")
            log("peer received: $out")
            val back = "hello from vaultctl ${Ulid.new()}"
            stack.peerRequest("message.send", buildJsonObject {
                put("connection_id", peerConn)
                put("text", back)
            })
            val inbound = vault.awaitEvent("message.new") { VaultJson.str(it, "text") == back }
            check(VaultJson.str(inbound, "direction") == "in" && VaultJson.str(inbound, "connection_id") == conn)
            check(vault.messageList(conn).map { it.text }.containsAll(listOf(out, back)))
            log("app received: $back")
        } finally {
            device.stop()
        }
    }

    private suspend fun waitFor(what: String, ok: suspend () -> Boolean) {
        repeat(WAIT_ROUNDS) {
            if (ok()) return
            delay(WAIT_MS)
        }
        error("timed out waiting for $what")
    }

    companion object {
        const val PIN = "97531086"
        const val PASSWORD = "correct horse battery staple (test)"
        private const val WAIT_ROUNDS = 120
        private const val WAIT_MS = 500L
    }
}
