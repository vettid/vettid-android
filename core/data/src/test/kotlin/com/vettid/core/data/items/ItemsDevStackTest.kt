// Fixtures (JSON bodies for the peer) stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.items

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.Randomness
import com.vettid.core.testing.DevStack
import com.vettid.core.testing.ExitScenario
import com.vettid.core.testing.TestAndroidAttester
import com.vettid.core.vault.DeviceSecrets
import com.vettid.core.vault.InMemoryDeviceStateStore
import com.vettid.core.vault.VaultJson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The A5 flows (ANDROID-PLAN §6: items, tags, share rules, grants and critical-item approvals "against the dev stack
 * and a second vault") through the app's own repositories, after the A2 exit scenario connected the app's vault to
 * the vaultctl peer. Skipped when the stack is not running (CI): `devstack/devstack.sh up`, then
 * `./gradlew :core:data:testDebugUnitTest --tests '*ItemsDevStackTest*'`.
 */
class ItemsDevStackTest {
    @Test
    fun itemsSharingGrantsAndACriticalUse() = runBlocking {
        val stack = DevStack()
        assumeTrue("dev stack not running", stack.reachable())
        withTimeout(TIMEOUT_MS) {
            ExitScenario(stack, TestAndroidAttester(), DeviceSecrets.generate(), InMemoryDeviceStateStore()) { println("a2: $it") }
                .run(this) { c -> scenario(stack, c) }
        }
    }

    @Suppress("LongMethod")
    private suspend fun kotlinx.coroutines.CoroutineScope.scenario(stack: DevStack, c: ExitScenario.Connected) {
        val log: (String) -> Unit = { println("a5: $it") }
        val pw = ExitScenario.PASSWORD
        val items = ItemsManager(this, ops = { VaultItemsOps(c.vault) })
        val sharing = SharingManager(ops = { VaultSharingOps(c.vault) }, onItemsChanged = { items.refresh() })

        // --- items of each sensitivity (§10.7) ---
        val allergies = items.create(
            ItemDraft("Allergies", "medical", "allergies", Sensitivity.DATA, listOf("Medical"), listOf(DraftField(label = "Allergies", kind = "multiline", text = "Penicillin\nPeanuts"))),
        )
        val login = items.create(
            ItemDraft("Bank login", "login", "login", Sensitivity.SECRET, emptyList(), listOf(DraftField(label = "Password", kind = "password", text = "hunter2"), DraftField(label = "Code", kind = "otp", text = "JBSWY3DPEHPK3PXP"))),
        )
        val seed = Base64s.encodeStd(Randomness.bytes(32))
        val key = items.createCritical(
            ItemDraft("Signing key", "crypto_wallet", "signing_key", Sensitivity.CRITICAL, listOf("keys"), listOf(DraftField(label = "Key", kind = "password", text = seed))),
            pw,
        )
        items.refresh()
        check(items.items.value.map { it.itemId }.containsAll(listOf(allergies, login, key))) { "list ${items.items.value}" }
        check(items.get(allergies).fields.single().value == FieldValue.Text("Penicillin\nPeanuts"))
        val hidden = items.get(login)
        check(!hidden.revealed && hidden.fields.all { it.value == null }) { "a secret item came with values" }
        check(items.reveal(login).fields[0].value == FieldValue.Text("hunter2"))
        val opened = items.revealCritical(key, pw)
        check(opened.fields.single().value == FieldValue.Text(seed)) { "critical value differs" }
        log("data, secret and critical items stored; secret revealed; critical opened")

        // A critical edit is sealed again with the password; data ↔ secret needs none.
        val v = items.updateCritical(key, opened.version, ItemDraft.of(opened).copy(notes = "for the lease"), pw)
        check(items.revealCritical(key, pw).notes == "for the lease")
        val s1 = items.setSensitivity(allergies, items.get(allergies).version, Sensitivity.DATA, Sensitivity.SECRET)
        items.setSensitivity(allergies, s1, Sensitivity.SECRET, Sensitivity.DATA)
        log("critical item updated (v$v), standard ↔ secret moved")

        // --- tags (§10.8) ---
        val tags = sharing.refreshTags()
        check(tags.tags.any { it.tag == "medical" && it.items == 1 }) { "tags $tags" }
        check(sharing.renameTag("medical", "health", dryRun = true).items == 1)
        log("tag registry: ${tags.tags.map { it.tag }}")

        // --- a share rule for the peer (§10.12): preview, then automatic ---
        val draft = RuleDraft(c.connectionId, listOf("medical"), mode = ShareMode.AUTO)
        val preview = sharing.preview(draft)
        check(preview.matches.any { it.itemId == allergies }) { "preview $preview" }
        val rule = sharing.saveRule(draft)
        check(rule.connectionId == c.connectionId && rule.mode == ShareMode.AUTO)
        check(sharing.rules(c.connectionId).any { it.ruleId == rule.ruleId })
        val given = sharing.grants().given.filter { it.connectionId == c.connectionId }
        check(given.any { it.itemRef == allergies }) { "given $given" }
        log("rule ${rule.ruleId} shares Allergies with the peer automatically")

        // --- the peer shares with the app; the app fetches it (grant.fetch → grant.value, sealed to this fetch) ---
        val peerItem = VaultJson.str(
            stack.peerRequest("item.put", buildJsonObject {
                put("name", "Practice address")
                put("category", "contact")
                putJsonArray("tags") { add(JsonPrimitive("patients")) }
                putJsonArray("fields") {
                    add(buildJsonObject {
                        put("label", "Phone")
                        put("kind", "phone")
                        put("value", "+49 30 1234567")
                    })
                }
            }),
            "item_id",
        )!!
        stack.peerRequest("share.rule.set", buildJsonObject {
            putJsonObject("subject") { put("connection_id", c.peerConnectionId) }
            putJsonArray("tags") { add(JsonPrimitive("patients")) }
            put("mode", "auto")
        })
        c.vault.awaitEvent("grant.event") { VaultJson.str(it, "event") == "shared" }
        val received = sharing.grants().received.first { it.connectionId == c.connectionId }
        val fetched = sharing.fetchShared(received.grantId)
        check(fetched is FetchOutcome.Shared && fetched.content.name == "Practice address" && fetched.content.fields.single().value == FieldValue.Text("+49 30 1234567")) {
            "fetched $fetched"
        }
        log("peer item $peerItem shared with the app and fetched")

        // --- a one-off request from the peer, answered with one of the app's items (grant.decide answers) ---
        stack.peerRequest("grant.request", buildJsonObject {
            put("connection_id", c.peerConnectionId)
            putJsonArray("items") {
                add(buildJsonObject {
                    put("kind", "category")
                    put("ref", "login")
                    put("label", "Your bank login")
                })
            }
            put("reason", "A5 test")
        })
        val pending = c.vault.awaitEvent("grant.pending")
        val requestId = VaultJson.str(pending, "request_id")!!
        val decided = c.vault.grantDecide(
            requestId, true, items = listOf(0),
            answers = buildJsonArray { add(buildJsonObject { put("index", 0); put("item_id", login) }) },
            uses = 2,
        )
        check((decided["grants"] as JsonArray).size == 1) { "decided $decided" }
        log("grant request $requestId answered with the bank login")

        // --- a critical-item use by the peer, approved with the password (§10.13) ---
        sharing.saveRule(RuleDraft(c.connectionId, listOf("keys"), mode = ShareMode.AUTO))
        val fieldId = items.get(key).fields.single().fieldId!!
        val payload = Base64s.encodeStd("sign this lease".toByteArray())
        stack.peerRequest("critical-secret-use.request", buildJsonObject {
            put("connection_id", c.peerConnectionId)
            put("item_id", key)
            put("field_id", fieldId)
            put("operation", "sign")
            put("payload", payload)
            put("context", "A5 test")
        })
        val use = c.vault.awaitEvent("critical-secret-use.pending")
        val sha = Bytes.sha256(Base64s.decodeStd(VaultJson.str(use, "payload")!!))
        check(Base64s.encodeStd(sha) == VaultJson.str(use, "payload_sha256")) { "payload hash" }
        val status = c.vault.criticalUseApprove(pw, VaultJson.str(use, "request_id")!!, sha)
        check(status == "ok") { "critical use: $status" }
        log("critical use approved: $status")

        // --- deleting: a critical item needs the password ---
        items.delete(key, Sensitivity.CRITICAL, pw)
        items.delete(allergies, Sensitivity.DATA)
        items.refresh()
        check(items.items.value.none { it.itemId == key || it.itemId == allergies })
        log("deleted")
    }

    private companion object {
        const val TIMEOUT_MS = 600_000L
    }
}
