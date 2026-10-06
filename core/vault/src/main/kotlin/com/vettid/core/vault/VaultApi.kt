package com.vettid.core.vault

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Bytes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Duration

/**
 * One function per VAULT-MESSAGING §10 type the v1 app sends (lifecycle,
 * credential, items, tags, profile, settings, share rules, connections and
 * invitations, blocks, member authentication, messaging, approvals of
 * desktop sessions, grants and critical-item uses, audit and feed), plus
 * typed Flows of the events it receives. Errors are [VaultOpException]
 * with the spec's code. Critical operations ask for the credential
 * password and run as credential operations (§3.5.3).
 */
@Suppress("TooManyFunctions")
class VaultApi(val device: VaultDevice) {
    private val cred = CredentialOps(device)

    private fun <T> JsonObject.decode(s: KSerializer<T>): T = VaultJson.decode(s, this)

    private suspend fun op(type: String, body: JsonObjectBuilder.() -> Unit = {}): JsonObject = device.op(type, buildJsonObject(body))

    // --- event flows (§9.1, §10) ---

    fun events(type: String): Flow<VaultMessage> = device.events.filter { it.type == type }

    /** `message.new`: incoming and (from other devices) outgoing messages. */
    val messages: Flow<Message> get() = events("message.new").map { it.body.decode(Message.serializer()) }

    /** `connection.request.pending`: a connection asks to be approved (its handshake has checked out). */
    val connectionRequests: Flow<IncomingRequest>
        get() = events("connection.request.pending").map { it.body.decode(IncomingRequest.serializer()) }

    /** `connection.request.outgoing`: the safety code of this vault's outgoing request (0.10.3). */
    val outgoingRequests: Flow<OutgoingRequest>
        get() = events("connection.request.outgoing").map { it.body.decode(OutgoingRequest.serializer()) }

    /** `connection.event`: added, removed, stale, rekeyed, reconnected, failed, profile. */
    val connectionEvents: Flow<ConnectionEvent> get() = events("connection.event").map { it.body.decode(ConnectionEvent.serializer()) }

    /** `sync.event`: side effects of the owner's other devices (§10.1). */
    val syncEvents: Flow<SyncEvent> get() = events("sync.event").map { SyncEvent(VaultJson.str(it.body, "kind") ?: "", it.body) }

    /** `feed.event`: new activity items. */
    val feedEvents: Flow<FeedItem> get() = events("feed.event").map { it.body.decode(FeedItem.serializer()) }

    /** `vault.held` (§3.6.3): the vault is held (or the app gated); content-free counts. */
    val held: Flow<HeldNotice> get() = events("vault.held").map { it.body.decode(HeldNotice.serializer()) }

    /** The approvals the app shows (§4 Approvals): connection requests, grants, critical-item uses, share decisions, held requests. */
    val approvals: Flow<VaultMessage>
        get() = device.events.filter { it.type in APPROVAL_TYPES }

    // --- lifecycle (§10.2) ---

    suspend fun enrollConfirm() {
        op("vault.enroll.confirm")
    }

    suspend fun status(): VaultStatusInfo = op("vault.status").decode(VaultStatusInfo.serializer())

    /** `account.get` (§10.2, §11.13, 0.15.0): the member's account snapshot from the member API, display only. */
    suspend fun accountGet(): AccountView = op("account.get").decode(AccountView.serializer())

    /** Locks the vault (it answers, then sends vault.locking). */
    suspend fun lock() {
        op("vault.lock")
    }

    /**
     * Deletes the vault (§12.5) with the PIN and the credential password
     * (the holder's credential operation). Irreversible: the UI confirms first.
     */
    suspend fun deleteVault(pin: String, password: String?) {
        val payload: JsonObjectBuilder.() -> Unit = {
            put("pin", pin)
            password?.let { put("password", it) }
        }
        val extra: JsonObjectBuilder.() -> Unit = { put("confirm", "delete my vault") }
        if (device.hasCredential()) cred.credOp("vault.delete", payload, extra = extra) else cred.sealedOp("vault.delete", payload, extra)
        device.forget()
    }

    /**
     * The daily owner check (§3.6.1): the PIN and the credential password sealed together to one UTK, with the
     * blob (a credential operation: the CEK rotates and the new blob is kept and acked). [hold] `false` with an
     * optional [holdOffUntil] (RFC 3339, at most 30 days ahead) turns the hold off (§3.6.7); `true` turns it on.
     */
    suspend fun ownerCheck(pin: String, password: String, hold: Boolean? = null, holdOffUntil: String? = null): OwnerCheckPassed =
        cred.credOp(TYPE_OWNER_CHECK, {
            put("pin", pin)
            put("password", password)
            hold?.let { put("hold", it) }
            holdOffUntil?.let { put("hold_off_until", it) }
        }).first.decode(OwnerCheckPassed.serializer())

    // --- credential (§10.6) ---

    /** Creates the Protean Credential under [password] and keeps the blob (enrollment, §3.5.7). */
    suspend fun credentialCreate(password: String) {
        cred.sealedOp("credential.create", { put("password", password) })
    }

    suspend fun credentialFetch() = cred.fetch()

    suspend fun credentialVersion(): CredentialInfo = op("credential.version").decode(CredentialInfo.serializer())

    /** Opens the unlock window (§3.5.3); returns its expiry. */
    suspend fun credentialUnlock(password: String): String =
        VaultJson.str(cred.credOp("credential.unlock", { put("password", password) }).first, "expires_at") ?: ""

    suspend fun credentialLock() {
        op("credential.lock")
    }

    /** Rotates the credential key (and the vault's ik and kem, §3.4). */
    suspend fun credentialRotate(password: String) {
        cred.credOp("credential.rotate", { put("password", password) })
    }

    suspend fun credentialChangePassword(password: String, newPassword: String) {
        cred.credOp("credential.password.change", {
            put("password", password)
            put("new_password", newPassword)
        })
    }

    // No `credential.delete` (VAULT-MESSAGING 0.15.2): a credential goes only with the vault (`vault.delete`);
    // starting over with a new one is a recovery's `credential.reset`.

    /** A recovering app authenticates with the password and takes over the credential (§11.11.5). */
    suspend fun credentialRecover(password: String) {
        cred.sealedOp("credential.recover", { put("password", password) })
        device.endRecovery()
    }

    /**
     * The holder's new credential (§3.5.5, 0.15.2): with the blob, the PIN, the current password and the new one
     * sealed to one UTK. The old credential and every critical item are destroyed; checked like an owner check
     * (`bad_pin`, `bad_password` are failed checks) and it starts the owner-check clock afresh.
     */
    suspend fun credentialResetHolder(pin: String, password: String, newPassword: String) {
        cred.credOp("credential.reset", {
            put("pin", pin)
            put("password", password)
            put("new_password", newPassword)
        })
    }

    /**
     * After a direct transfer (§6.7.1 step 5): fetches the blob the vault kept
     * (`credential.get`), confirms it (`credential.ack`) and fills the UTK pool
     * (`credential.utk.get`).
     */
    suspend fun credentialTakeOver() {
        cred.fetch()
        cred.keep(op("credential.utk.get"))
    }

    // --- direct transfer, the old app's side (§6.7.1, §10.3) ---

    /** `device.transfer.create`: the QR link (pairing kind `p`, TTL 10 minutes) for the new phone. */
    suspend fun transferCreate(): TransferOffer = op("device.transfer.create").let { o ->
        TransferOffer(
            VaultJson.str(o, "transfer_id") ?: throw VaultStateException("transfer without id"),
            VaultJson.str(o, "link") ?: throw VaultStateException("transfer without link"),
            VaultJson.str(o, "exp"),
        )
    }

    /** Waits for `device.transfer.pending{transfer_id, name, sas}`: the new app's handshake checked out. */
    suspend fun awaitTransferPending(transferId: String, timeout: Duration): TransferPending =
        device.awaitEvent("device.transfer.pending", timeout) { VaultJson.str(it, "transfer_id") == transferId }.body.let {
            TransferPending(transferId, VaultJson.str(it, "name") ?: "", VaultJson.str(it, "sas") ?: "")
        }

    /**
     * `device.transfer.approve` with the blob and, sealed to a UTK, the PIN and the
     * password. `{}` means the transfer is complete: this app has been removed.
     */
    suspend fun transferApprove(transferId: String, pin: String, password: String) {
        cred.credOp(
            "device.transfer.approve",
            {
                put("password", password)
                put("pin", pin)
            },
            extra = { put("transfer_id", transferId) },
        )
    }

    /** `device.transfer.reject`: cancels the transfer before or after the scan. */
    suspend fun transferReject(transferId: String) {
        op("device.transfer.reject") { put("transfer_id", transferId) }
    }

    /** Answers a clone alarm (§3.5.9): [mine] = "that was me". Returns the alarm's new state. */
    suspend fun credentialAlarmConfirm(alarmId: String, mine: Boolean): String? = VaultJson.str(
        op("credential.alarm.confirm") {
            put("alarm_id", alarmId)
            put("mine", mine)
        },
        "state",
    )

    /** Changes the vault PIN (§10.6). */
    suspend fun pinChange(pin: String, newPin: String) {
        op("pin.change") {
            put("pin", pin)
            put("new_pin", newPin)
        }
    }

    // --- items (§10.7) ---

    private fun JsonObjectBuilder.content(c: ItemContent) {
        put("name", c.name)
        c.category?.let { put("category", it) }
        c.template?.let { put("template", it) }
        c.fields?.let { fs -> put("fields", VaultJson.json.encodeToJsonElement(ListSerializer(ItemField.serializer()), fs)) }
        c.notes?.let { put("notes", it) }
    }

    private fun JsonObjectBuilder.tags(tags: List<String>) = putJsonArray("tags") { tags.forEach { add(JsonPrimitive(it)) } }

    /** Creates ([itemId] null) or replaces a `data` or `secret` item; [tags] null leaves them as they are. */
    suspend fun itemPut(
        content: ItemContent,
        sensitivity: String? = null,
        tags: List<String>? = null,
        itemId: String? = null,
        version: Long? = null,
    ): ItemRef =
        op("item.put") {
            itemId?.let { put("item_id", it) }
            version?.let { put("version", it) }
            sensitivity?.let { put("sensitivity", it) }
            tags?.let { tags(it) }
            content(content)
        }.decode(ItemRef.serializer())

    /** Creates or replaces a `critical` item with the credential password; the content and id travel UTK-sealed (§10.7). */
    suspend fun itemPutCritical(
        password: String,
        content: ItemContent,
        tags: List<String>? = null,
        itemId: String? = null,
        version: Long? = null,
    ): ItemRef =
        cred.credOp(
            "item.put",
            {
                put("password", password)
                put("item", buildJsonObject { content(content) })
                itemId?.let { put("item_id", it) }
            },
            extra = {
                put("sensitivity", "critical")
                version?.let { put("version", it) }
                tags?.let { tags(it) }
            },
        ).first.decode(ItemRef.serializer())

    suspend fun itemGet(itemId: String): Item = op("item.get") { put("item_id", itemId) }.decode(Item.serializer())

    /** A `secret` (or `data`) item with its values; [fields] narrows it. */
    suspend fun itemReveal(itemId: String, fields: List<String>? = null): Item = op("item.reveal") {
        put("item_id", itemId)
        fields?.let { f -> putJsonArray("fields") { f.forEach { add(JsonPrimitive(it)) } } }
    }.decode(Item.serializer())

    /**
     * A `critical` item's values, sealed by the vault to a one-time reply key
     * (§3.5.4): `{"fields": [{field_id, value}], "notes"?}`. Wipe it after use.
     */
    suspend fun itemRevealCritical(password: String, itemId: String): Revealed {
        val (o, rk, id) = cred.credOp(
            "item.reveal",
            {
                put("password", password)
                put("item_id", itemId)
            },
            reply = true,
            extra = { put("item_id", itemId) },
        )
        val sealed = VaultJson.str(o, "values_sealed") ?: throw VaultStateException("item.reveal without values_sealed")
        return Revealed(o, cred.open(rk!!, id, sealed))
    }

    suspend fun itemList(
        tags: List<String>? = null,
        match: String? = null,
        category: String? = null,
        sensitivity: String? = null,
        after: String? = null,
        limit: Int? = null,
    ): ItemPage =
        op("item.list") {
            tags?.let { tags(it) }
            match?.let { put("match", it) }
            category?.let { put("category", it) }
            sensitivity?.let { put("sensitivity", it) }
            after?.let { put("after", it) }
            limit?.let { put("limit", it) }
        }.decode(ItemPage.serializer())

    suspend fun itemTag(itemId: String, version: Long, tags: List<String>): Long = VaultJson.long(
        op("item.tag") {
            put("item_id", itemId)
            put("version", version)
            tags(tags)
        },
        "version",
    ) ?: 0

    /** Changes sensitivity; to or from `critical` needs the password (apps warn before an item leaves critical). */
    suspend fun itemSensitivity(itemId: String, version: Long, sensitivity: String, password: String? = null): Long {
        val o = if (password == null) {
            op("item.sensitivity") {
                put("item_id", itemId)
                put("version", version)
                put("sensitivity", sensitivity)
            }
        } else {
            cred.credOp(
                "item.sensitivity",
                {
                    put("password", password)
                    put("item_id", itemId)
                },
                extra = {
                    put("item_id", itemId)
                    put("version", version)
                    put("sensitivity", sensitivity)
                },
            ).first
        }
        return VaultJson.long(o, "version") ?: 0
    }

    /** Deletes an item; a critical item needs the password. */
    suspend fun itemDelete(itemId: String, password: String? = null) {
        if (password == null) {
            op("item.delete") { put("item_id", itemId) }
        } else {
            cred.credOp(
                "item.delete",
                {
                    put("password", password)
                    put("item_id", itemId)
                },
                extra = { put("item_id", itemId) },
            )
        }
    }

    // --- tags, profile, settings (§10.8) ---

    suspend fun tagList(after: String? = null, limit: Int? = null): TagPage = op("tag.list") {
        after?.let { put("after", it) }
        limit?.let { put("limit", it) }
    }.decode(TagPage.serializer())

    suspend fun tagSet(
        version: Long,
        tag: String,
        color: String? = null,
        icon: String? = null,
        description: String? = null,
    ): Long = VaultJson.long(
        op("tag.set") {
            put("version", version)
            put("tag", tag)
            color?.let { put("color", it) }
            icon?.let { put("icon", it) }
            description?.let { put("description", it) }
        },
        "version",
    ) ?: 0

    suspend fun tagDelete(version: Long, tag: String, dryRun: Boolean = false): JsonObject = op("tag.delete") {
        put("version", version)
        put("tag", tag)
        if (dryRun) put("dry_run", true)
    }

    suspend fun tagMerge(version: Long, from: List<String>, into: String, dryRun: Boolean = false): JsonObject = op("tag.merge") {
        put("version", version)
        putJsonArray("from") { from.forEach { add(JsonPrimitive(it)) } }
        put("into", into)
        if (dryRun) put("dry_run", true)
    }

    suspend fun profileGet(): Profile = op("profile.get").decode(Profile.serializer())

    /** [photo] "" removes it. */
    suspend fun profileSet(version: Long, name: String? = null, photo: String? = null): Long = VaultJson.long(
        op("profile.set") {
            put("version", version)
            name?.let { put("name", it) }
            photo?.let { put("photo", it) }
        },
        "version",
    ) ?: 0

    suspend fun settingsGet(): Settings = op("settings.get").decode(Settings.serializer())

    suspend fun settingsSet(version: Long, set: Map<String, JsonElement>): Long = VaultJson.long(
        op("settings.set") {
            put("version", version)
            put("set", JsonObject(set))
        },
        "version",
    ) ?: 0

    // --- share rules (§10.12) ---

    /** Creates or replaces a share rule ([rule] as §10.12: subject, tags, match, mode, ...); returns the rule or a dry run's matches. */
    suspend fun shareRuleSet(rule: JsonObject): JsonObject = device.op("share.rule.set", rule)

    suspend fun shareRuleList(connectionId: String? = null, agentId: String? = null): JsonObject = op("share.rule.list") {
        connectionId?.let { put("connection_id", it) }
        agentId?.let { put("agent_id", it) }
    }

    suspend fun shareRuleDelete(ruleId: String) {
        op("share.rule.delete") { put("rule_id", ruleId) }
    }

    /** Includes or declines the items a rule asks about (`share.pending`). */
    suspend fun shareDecide(ruleId: String, items: List<String>, approve: Boolean): JsonObject = op("share.decide") {
        put("rule_id", ruleId)
        putJsonArray("items") { items.forEach { add(JsonPrimitive(it)) } }
        put("approve", approve)
    }

    // --- connections (§10.4) ---

    /** The relay's limits: invite lifetimes above `open_token_max_lifetime_seconds` or `claim_ttl_seconds` are not offered (§6.4). */
    suspend fun relayLimits() = device.relayLimits()

    /** [ttlSeconds]: 600, 3600, 86400 or 604800. */
    suspend fun inviteCreate(
        ttlSeconds: Int = INVITE_TTL_DEFAULT): Invite = op("connection.invite.create",
    ) { put("ttl_seconds", ttlSeconds) }.decode(Invite.serializer())

    suspend fun inviteList(): JsonObject = op("connection.invite.list")

    suspend fun inviteCancel(inviteId: String) {
        op("connection.invite.cancel") { put("invite_id", inviteId) }
    }

    /**
     * Accepts an invitation (the bare payload, §6.4): an outgoing request in
     * state `waiting`, whose safety code follows in `connection.request.outgoing`
     * (0.10.3). A vault already connected to the inviter, or with an outgoing
     * request to it, answers `exists` with `{connection_id}` ([VaultOpException.body]).
     */
    suspend fun inviteAccept(link: String): AcceptedInvite =
        op("connection.invite.accept") { put("link", link) }.decode(AcceptedInvite.serializer())

    /** Approves an incoming request (the inviter's side) after comparing the safety code. */
    suspend fun connectionApprove(pendingId: String) {
        op("connection.approve") { put("pending_id", pendingId) }
    }

    /** Declines an incoming request (any state; since 0.10.5 the vault tells the peer when it can, §6.4). */
    suspend fun connectionDecline(pendingId: String) {
        op("connection.decline") { put("pending_id", pendingId) }
    }

    /** Approves this vault's outgoing request (the accepter's side, 0.10.2); `bad_request` while it is `waiting`. */
    suspend fun outgoingApprove(connectionId: String) {
        op("connection.approve") { put("connection_id", connectionId) }
    }

    /** Declines this vault's outgoing request, in any state, `waiting` included (0.10.4). */
    suspend fun outgoingDecline(connectionId: String) {
        op("connection.decline") { put("connection_id", connectionId) }
    }

    /**
     * Pending incoming and outgoing requests with their safety codes (§10.4):
     * `{incoming: [...], outgoing: [...]}`, entries as [IncomingRequest] and [OutgoingRequest].
     */
    suspend fun requestList(): JsonObject = op("connection.request.list")

    suspend fun connectionList(): List<Connection> =
        VaultJson.decode(ListSerializer(Connection.serializer()), op("connection.list")["connections"] ?: JsonArray(emptyList()))

    suspend fun connectionGet(
        connectionId: String): Connection = op("connection.get",
    ) { put("connection_id", connectionId) }.decode(Connection.serializer())

    /** The owner's own metadata; at least one change; "" clears alias or note. Returns the new version. */
    suspend fun connectionUpdate(
        connectionId: String,
        version: Long,
        alias: String? = null,
        note: String? = null,
        tags: List<String>? = null,
        favorite: Boolean? = null,
        archived: Boolean? = null,
    ): Long = VaultJson.long(
        op("connection.update") {
            put("connection_id", connectionId)
            put("version", version)
            alias?.let { put("alias", it) }
            note?.let { put("note", it) }
            tags?.let { tags(it) }
            favorite?.let { put("favorite", it) }
            archived?.let { put("archived", it) }
        },
        "version",
    ) ?: 0

    suspend fun connectionRemove(connectionId: String) {
        op("connection.remove") { put("connection_id", connectionId) }
    }

    /** Blocks a connection or a pending request's sender; returns the block id. */
    suspend fun blockAdd(connectionId: String? = null, pendingId: String? = null, note: String? = null): String = VaultJson.str(
        op("block.add") {
            connectionId?.let { put("connection_id", it) }
            pendingId?.let { put("pending_id", it) }
            note?.let { put("note", it) }
        },
        "block_id",
    ) ?: ""

    suspend fun blockRemove(blockId: String) {
        op("block.remove") { put("block_id", blockId) }
    }

    suspend fun blockList(): JsonObject = op("block.list")

    /** Asks a connection's member to prove presence (§10.4); returns the request id. */
    suspend fun authenticateRequest(connectionId: String, context: String? = null): String = VaultJson.str(
        op("connection.authenticate.request") {
            put("connection_id", connectionId)
            context?.let { put("context", it) }
        },
        "request_id",
    ) ?: ""

    /** Signs a challenge with the credential key (the unlock window must be open: credentialUnlock first). */
    suspend fun authenticateApprove(requestId: String) {
        op("connection.authenticate.approve") { put("request_id", requestId) }
    }

    suspend fun authenticateDeny(requestId: String) {
        op("connection.authenticate.deny") { put("request_id", requestId) }
    }

    suspend fun authenticateList(): JsonObject = op("connection.authenticate.list")

    // --- messaging (§10.5) ---

    /** Sends [text] (1 byte to 16 KiB) to a connection. */
    suspend fun messageSend(connectionId: String, text: String): SentMessage = op("message.send") {
        put("connection_id", connectionId)
        put("text", text)
    }.decode(SentMessage.serializer())

    /** History, oldest first ([limit] 1–500). */
    suspend fun messageList(connectionId: String, limit: Int? = null): List<Message> = VaultJson.decode(
        ListSerializer(Message.serializer()),
        op("message.list") {
            put("connection_id", connectionId)
            limit?.let { put("limit", it) }
        }["messages"] ?: JsonArray(emptyList()),
    )

    suspend fun messageGet(connectionId: String, messageId: String): Message = op("message.get") {
        put("connection_id", connectionId)
        put("message_id", messageId)
    }.decode(Message.serializer())

    /** Marks a message read; the vault sends a read receipt. */
    suspend fun messageRead(connectionId: String, messageId: String) {
        op("message.read") {
            put("connection_id", connectionId)
            put("message_id", messageId)
        }
    }

    /** Deletes a message locally (the peer keeps its copy). */
    suspend fun messageDelete(connectionId: String, messageId: String) {
        op("message.delete") {
            put("connection_id", connectionId)
            put("message_id", messageId)
        }
    }

    // --- approvals: devices and sessions (§6.8, §10.3), grants (§10.12), critical-item uses (§10.13) ---

    suspend fun deviceList(): JsonObject = op("device.list")

    suspend fun sessionApprove(requestId: String, seconds: Int? = null): JsonObject = op("device.session.approve") {
        put("request_id", requestId)
        seconds?.let { put("seconds", it) }
    }

    suspend fun sessionDeny(requestId: String) {
        op("device.session.deny") { put("request_id", requestId) }
    }

    /** Decides a desktop's step-up request or an agent's referred request (`approval.pending`); returns its outcome. */
    suspend fun approvalDecide(approvalId: String, approve: Boolean): String? = VaultJson.str(
        op("approval.decide") {
            put("approval_id", approvalId)
            put("approve", approve)
        },
        "result",
    )

    /** Decides a connection's grant request (`grant.pending`): [items] the indexes approved, [answers] chosen items. */
    suspend fun grantDecide(
        requestId: String,
        approve: Boolean,
        items: List<Int>? = null,
        answers: JsonArray? = null,
        uses: Int? = null,
        expiresIn: Int? = null,
    ): JsonObject =
        op("grant.decide") {
            put("request_id", requestId)
            put("approve", approve)
            items?.let { i -> putJsonArray("items") { i.forEach { add(JsonPrimitive(it)) } } }
            answers?.let { put("answers", it) }
            uses?.let { put("uses", it) }
            expiresIn?.let { put("expires_in", it) }
        }

    suspend fun grantList(): JsonObject = op("grant.list")

    suspend fun grantRevoke(grantId: String) {
        op("grant.revoke") { put("grant_id", grantId) }
    }

    /**
     * Consents to one use of a critical item by a connection with the
     * password (§10.13): the sealed payload binds the password to this
     * request and to the payload's SHA-256 shown to the member. Returns the status.
     */
    suspend fun criticalUseApprove(password: String, requestId: String, payloadSha256: ByteArray): String? {
        val (o, _, _) = cred.credOp(
            "critical-secret-use.approve",
            {
                put("password", password)
                put("request_id", requestId)
                put("payload_sha256", Base64s.encodeStd(payloadSha256))
            },
            extra = { put("request_id", requestId) },
        )
        return VaultJson.str(o, "status")
    }

    suspend fun criticalUseDeny(requestId: String) {
        op("critical-secret-use.deny") { put("request_id", requestId) }
    }

    suspend fun criticalUseList(): JsonObject = op("critical-secret-use.list")

    /**
     * An incoming request with its payload, to show it again (§10.13, 0.10.2): the
     * `critical-secret-use.pending` body. The caller checks SHA-256(payload)
     * against `payload_sha256` before showing it or offering the approval.
     */
    suspend fun criticalUseGet(requestId: String): JsonObject = op("critical-secret-use.get") { put("request_id", requestId) }

    // --- audit and feed (§10.9) ---

    suspend fun auditList(
        connectionId: String? = null,
        kinds: List<String>? = null,
        beforeSeq: Long? = null,
        afterSeq: Long? = null,
        limit: Int? = null,
    ): AuditPage {
        val type = if (connectionId != null && afterSeq == null) "connection.audit.list" else "audit.list"
        return op(type) {
            connectionId?.let { put("connection_id", it) }
            kinds?.let { k -> putJsonArray("kinds") { k.forEach { add(JsonPrimitive(it)) } } }
            beforeSeq?.let { put("before_seq", it) }
            afterSeq?.let { put("after_seq", it) }
            limit?.let { put("limit", it) }
        }.decode(AuditPage.serializer())
    }

    suspend fun feedList(status: String? = null, afterSeq: Long? = null, limit: Int? = null): FeedPage = op("feed.list") {
        status?.let { put("status", it) }
        afterSeq?.let { put("after_seq", it) }
        limit?.let { put("limit", it) }
    }.decode(FeedPage.serializer())

    suspend fun feedGet(itemId: String): FeedItem = op("feed.get") { put("item_id", itemId) }.decode(FeedItem.serializer())

    suspend fun feedUpdate(itemId: String, status: String? = null, priority: String? = null): FeedItem = op("feed.update") {
        put("item_id", itemId)
        status?.let { put("status", it) }
        priority?.let { put("priority", it) }
    }.decode(FeedItem.serializer())

    suspend fun feedDelete(itemId: String) {
        op("feed.delete") { put("item_id", itemId) }
    }

    /** Waits for the next event of [type] whose body matches. */
    suspend fun awaitEvent(
        type: String,
        timeout: Duration = Duration.ofSeconds(AWAIT_S),
        match: (JsonObject) -> Boolean = { true },
    ): JsonObject =
        device.awaitEvent(type, timeout, match).body

    companion object {
        const val INVITE_TTL_DEFAULT = 3600

        /**
         * The owner check's message type (§3.6.1, §10.2; renamed from `vault.owner_check` in VAULT-MESSAGING 0.15.2
         * to fit §5.3's `type` grammar). Settings keys, error code, feed kinds and `owner_check` members keep
         * their underscores.
         */
        const val TYPE_OWNER_CHECK = "vault.owner-check"
        private const val AWAIT_S = 90L
        val APPROVAL_TYPES = setOf(
            "connection.request.pending", "connection.request.outgoing", "grant.pending", "critical-secret-use.pending",
            "share.pending", "approval.pending",
            "device.session.pending", "connection.authenticate.pending", "credential.alarm",
        )

        /**
         * Checks that audit entries chain (§10.9): each entry's `hash` is
         * SHA-256("vettid/vms/2/audit" || prev || seq || at_ms || lp(kind) ||
         * lp(connection_id) || lp(device_id) || lp(ref) || lp(direction)) and
         * `prev` is the previous entry's hash. [entries] oldest first.
         */
        fun auditChains(entries: List<AuditEntry>, atMillis: (String) -> Long): Boolean {
            var prev: ByteArray? = null
            for (e in entries) {
                val p = Base64s.decodeStd(e.prev)
                if (prev != null && !Bytes.constantTimeEquals(prev, p)) return false
                fun lp(s: String?): ByteArray {
                    val b = (s ?: "").toByteArray()
                    return Bytes.concat(Bytes.uintBE(b.size.toLong(), 2), b)
                }
                val h = Bytes.sha256(
                    "vettid/vms/2/audit".toByteArray(), p, Bytes.uintBE(e.seq, 8), Bytes.uintBE(atMillis(e.at), 8),
                    lp(e.kind), lp(e.connectionId), lp(e.deviceId), lp(e.ref), lp(e.direction),
                )
                if (!Bytes.constantTimeEquals(h, Base64s.decodeStd(e.hash))) return false
                prev = h
            }
            return true
        }
    }
}
