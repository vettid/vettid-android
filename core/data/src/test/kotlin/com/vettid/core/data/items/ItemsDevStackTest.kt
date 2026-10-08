// Fixtures (JSON bodies for the peer) stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.items

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.Randomness
import com.vettid.core.data.vault.vaultGuard
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

        // --- VAULT-MESSAGING 0.21.0 (needs a 0.21.0 vault: VAULT_REF / VAULT_SRC) ---
        v021(c, items, sharing, rule.ruleId, allergies, login, key, seed, log)

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
        check(received.labels.map { it.label to it.kind } == listOf("Phone" to "phone")) { "received labels ${received.labels}" } // 0.21.0
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
        check(VaultJson.str(use, "kind") == "password") { "critical use without kind: $use" } // 0.21.0
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

    /** Kept values, the size, dry runs, share.pending.list, the one-call share.decide and a named limit (0.21.0). */
    @Suppress("LongMethod", "LongParameterList")
    private suspend fun v021(
        c: ExitScenario.Connected,
        items: ItemsManager,
        sharing: SharingManager,
        autoRule: String,
        allergies: String,
        login: String,
        key: String,
        seed: String,
        log: (String) -> Unit,
    ) {
        val pw = ExitScenario.PASSWORD
        // A secret item edited without item.reveal: one label renamed, every value kept.
        val loginMeta = items.get(login)
        check(loginMeta.size != null) { "item.get without size" }
        val loginDraft = ItemDraft.of(loginMeta)
        check(loginDraft.fields.all { it.kept } && ItemChecks.check(loginDraft).exact) { "draft $loginDraft" }
        check(ItemChecks.check(loginDraft).size == loginMeta.size) { "size ${ItemChecks.check(loginDraft).size} != ${loginMeta.size}" }
        items.update(login, loginMeta.version, loginDraft.copy(fields = listOf(loginDraft.fields[0].copy(label = "Bank password"), loginDraft.fields[1])))
        val loginNow = items.reveal(login)
        check(loginNow.fields[0].label == "Bank password" && loginNow.fields[0].value == FieldValue.Text("hunter2")) { "kept secret value lost" }
        check(loginNow.fields[1].value == FieldValue.Text("JBSWY3DPEHPK3PXP"))
        log("secret item edited with kept values")

        // A critical item edited in one credential operation: renamed, its value and notes kept.
        val keyMeta = items.get(key)
        val keyDraft = ItemDraft.of(keyMeta)
        check(keyDraft.fields.single().kept && keyDraft.keepNotes) { "critical draft $keyDraft" }
        items.updateCritical(key, keyMeta.version, keyDraft.copy(name = "Lease signing key"), pw)
        val keyNow = items.revealCritical(key, pw)
        check(keyNow.name == "Lease signing key" && keyNow.fields.single().value == FieldValue.Text(seed) && keyNow.notes == "for the lease") {
            "kept critical value lost"
        }
        log("critical item edited with kept values (one operation)")

        // Dry runs: a new item tagged medical gains the automatic rule; Allergies without it would leave it.
        val gain = items.shareEffect(null, null, Sensitivity.DATA, listOf("medical"))
        check(gain.shares.any { it.ruleId == autoRule && it.mode == ShareMode.AUTO && it.subject.connectionId == c.connectionId }) { "dry run $gain" }
        val a = items.get(allergies)
        val leave = items.shareEffect(allergies, a.version, Sensitivity.DATA, emptyList())
        check(leave.withdrawals.any { it.ruleId == autoRule && it.state == "included" } && leave.version == a.version) { "dry run $leave" }
        check(items.get(allergies).tags == a.tags) { "a dry run changed the item" }
        log("dry runs: gains and withdrawals")

        // share.pending.list and one share.decide with both lists.
        val ask = sharing.saveRule(RuleDraft(c.connectionId, listOf("travel"), mode = ShareMode.ASK))
        val p1 = items.create(ItemDraft("Passport", "identity_document", tags = listOf("travel"), fields = listOf(DraftField(label = "Number", kind = "text", text = "X1"))))
        val p2 = items.create(ItemDraft("Visa", "identity_document", tags = listOf("travel"), fields = listOf(DraftField(label = "Number", kind = "text", text = "V2"))))
        val decisions = com.vettid.core.data.social.SharePending.decisions(
            pendingPage = { after -> vaultGuard { c.vault.sharePendingList(after = after, limit = 1) } },
            rulePage = { after -> vaultGuard { c.vault.shareRuleList(after = after) } },
            names = emptyMap(),
            t = java.time.Instant.now(),
        )
        val d = decisions.single { it.ruleId == ask.ruleId }
        check(d.items.map { it.itemId }.toSet() == setOf(p1, p2) && d.items.all { it.name.isNotEmpty() } && d.tags == listOf("travel")) { "pending $d" }
        val decided = vaultGuard { c.vault.shareDecide(ask.ruleId, listOf(p1), listOf(p2)) }
        check((decided["included"] as JsonArray).map { (it as JsonPrimitive).content } == listOf(p1)) { "decided $decided" }
        check((decided["declined"] as JsonArray).map { (it as JsonPrimitive).content } == listOf(p2)) { "decided $decided" }
        log("share.pending.list (paged by 1) and one share.decide{include, decline}")

        // A named limit: an item over 64 KiB sent past the app's own check.
        val big = com.vettid.core.vault.ItemContent(
            name = "Too big",
            fields = (1..5).map { com.vettid.core.vault.ItemField(label = "Part $it", kind = "multiline", value = JsonPrimitive("y".repeat(14_000))) },
        )
        try {
            vaultGuard { c.vault.itemPut(big, "data") }
            error("an oversized item was accepted")
        } catch (e: com.vettid.core.data.vault.VaultFailure) {
            val l = e.limit
            check(e.kind == com.vettid.core.data.vault.FailureKind.LIMIT && l?.name == "item_size" && l.max == 65_536L && (l.size ?: 0) > 65_536) { "limit $e $l" }
        }
        log("limit item_size named, with max and size")
        items.delete(p1, Sensitivity.DATA)
        items.delete(p2, Sensitivity.DATA)
    }

    private companion object {
        const val TIMEOUT_MS = 600_000L
    }
}
