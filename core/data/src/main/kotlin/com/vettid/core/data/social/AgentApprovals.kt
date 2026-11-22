package com.vettid.core.data.social

import com.vettid.core.crypto.json.JsonArray as StrictArray
import com.vettid.core.crypto.json.JsonObject as StrictObject
import com.vettid.core.crypto.json.JsonString as StrictString
import com.vettid.core.crypto.json.JsonValue as StrictValue
import com.vettid.core.data.devices.ItemWaysRules

/**
 * An agent's `item.run`, `item.proxy` and `item.get` held for approval, read as ANDROID-PLAN 0.1.138 AA8 "Fail closed" says:
 * from the whole `approval.pending` (or its `pending.list` entry) read strictly, so a duplicate member anywhere has already
 * made it unreadable. Anything this version does not know, or that does not fit, is [HeldRequest.Unshown] (deny only). Pure.
 */
object AgentApprovals {
    /** `approval.pending`'s members (VAULT-MESSAGING 0.93.2 §6.8 step 1); any other makes these ops deny only. */
    private val TOP = setOf(
        "approval_id", "device_id", "role", "name", "type", "body", "exp",
        "flags", "previous_sha256", "previous_path", "replaces", "proxy", "folders",
    )

    /** An `item.run` body's members (VAULT-MESSAGING's `agent.request` row). */
    private val RUN_BODY = setOf("op", "item_id", "field_id", "program", "sha256", "argv", "cwd", "how", "name", "env", "inputs", "output")

    /** An `item.proxy` body's members. */
    private val PROXY_BODY = setOf("op", "item_id", "field_id", "host", "seconds")

    /** `proxy`'s members (0.92.0). */
    private val PROXY_NOTE = setOf("host", "header", "prefix")

    /** The members only a run of AA9 (member folders and files) carries: until AA9 is built such a run is deny only. */
    private val AA9_TOP = setOf("folders")
    private val AA9_BODY = setOf("inputs", "output")
    private val AA9_FLAGS = setOf(RunFlag.MEMBER_FOLDER, RunFlag.MEMBER_FILES)

    /** A run's program is unbound, unrecorded or changed: never two of these. */
    private val PIN_STATES = setOf(RunFlag.UNBOUND_PROGRAM, RunFlag.PROGRAM_UNRECORDED, RunFlag.PROGRAM_CHANGED)

    /** A proxy's length (`body.seconds`): 60 to 3,600, 900 when absent. */
    const val MIN_SECONDS = 60
    const val MAX_SECONDS = 3_600
    const val DEFAULT_SECONDS = 900

    private val HEX64 = Regex("[0-9a-f]{64}")

    /** An `item.get`'s envelope: known members only, no `proxy` or `replaces`, and `flags` at most `history`. */
    @Suppress("ReturnCount") // each early false is one rule of AA8's list
    fun getShown(p: StrictObject): Boolean {
        if (p["body"] !is StrictObject) return false
        val flags = flags(p) ?: return false
        return envelope(p, allowProxy = false, allowReplaces = false) && flags.all { it == "history" } && noRunMembers(p)
    }

    /** An `item.run`, or [HeldRequest.Unshown]. */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    fun run(p: StrictObject): HeldRequest {
        if (!envelope(p, allowProxy = false, allowReplaces = true)) return HeldRequest.Unshown
        val body = p["body"] as? StrictObject ?: return HeldRequest.Unshown
        if (!body.members.keys.all { it in RUN_BODY }) return HeldRequest.Unshown
        val wire = flags(p) ?: return HeldRequest.Unshown
        val flags = wire.map { RunFlag.of(it) ?: return HeldRequest.Unshown }.toSet()
        if (aa9(flags, p, body)) return HeldRequest.Unshown
        // Flags that contradict: at most one of unbound, unrecorded and changed.
        if (flags.count { it in PIN_STATES } > 1) return HeldRequest.Unshown
        val changed = RunFlag.PROGRAM_CHANGED in flags
        val item = str(body, "item_id")?.takeIf { it.isNotEmpty() } ?: return HeldRequest.Unshown
        val field = str(body, "field_id")?.takeIf { it.isNotEmpty() } ?: return HeldRequest.Unshown
        val program = str(body, "program") ?: return HeldRequest.Unshown
        val sha = hex64(body, "sha256") ?: return HeldRequest.Unshown
        val argv = (body["argv"] as? StrictArray)?.items?.map { (it as? StrictString)?.value ?: return HeldRequest.Unshown }
            ?.takeIf { it.isNotEmpty() } ?: return HeldRequest.Unshown
        val cwd = if (body.has("cwd")) str(body, "cwd") ?: return HeldRequest.Unshown else null
        val how = SecretHow.of(str(body, "how")) ?: return HeldRequest.Unshown
        val name = if (body.has("name")) str(body, "name") ?: return HeldRequest.Unshown else null
        if (how != SecretHow.STDIN && name == null) return HeldRequest.Unshown
        val env = if (body.has("env")) {
            val o = body["env"] as? StrictObject ?: return HeldRequest.Unshown
            o.members.map { (k, v) -> k to ((v as? StrictString)?.value ?: return HeldRequest.Unshown) }
        } else {
            emptyList()
        }
        // previous_* come with program_changed, and only with it.
        val prevSha = if (p.has("previous_sha256")) hex64(p, "previous_sha256") ?: return HeldRequest.Unshown else null
        val prevPath = if (p.has("previous_path")) str(p, "previous_path") ?: return HeldRequest.Unshown else null
        if (changed != (prevSha != null) || changed != (prevPath != null)) return HeldRequest.Unshown
        if (!replacesOk(p)) return HeldRequest.Unshown
        return HeldRequest.ItemRun(item, field, program, sha, argv, cwd, how, name, env, flags, prevSha, prevPath, str(p, "replaces"))
    }

    /** An `item.proxy`, or [HeldRequest.Unshown]. */
    @Suppress("ReturnCount", "CyclomaticComplexMethod") // each early return is one rule of AA8's list
    fun proxy(p: StrictObject): HeldRequest {
        if (!envelope(p, allowProxy = true, allowReplaces = true) || !noRunMembers(p)) return HeldRequest.Unshown
        val body = p["body"] as? StrictObject ?: return HeldRequest.Unshown
        if (!body.members.keys.all { it in PROXY_BODY }) return HeldRequest.Unshown
        // Any flag on a proxy.
        if (flags(p)?.isEmpty() != true) return HeldRequest.Unshown
        val item = str(body, "item_id")?.takeIf { it.isNotEmpty() } ?: return HeldRequest.Unshown
        val field = str(body, "field_id")?.takeIf { it.isNotEmpty() } ?: return HeldRequest.Unshown
        val seconds = if (body.has("seconds")) seconds(body["seconds"]) ?: return HeldRequest.Unshown else DEFAULT_SECONDS
        // Shown only from a well-formed `proxy` member.
        val note = p["proxy"] as? StrictObject ?: return HeldRequest.Unshown
        if (!note.members.keys.all { it in PROXY_NOTE }) return HeldRequest.Unshown
        val host = str(note, "host")?.takeIf { it == ItemWaysRules.normalizeHost(it) && ItemWaysRules.hostProblem(it) == null }
            ?: return HeldRequest.Unshown
        val header = str(note, "header")?.takeIf { ItemWaysRules.headerValid(it) } ?: return HeldRequest.Unshown
        val prefix = str(note, "prefix")?.takeIf { ItemWaysRules.prefixValid(it) } ?: return HeldRequest.Unshown
        if (!replacesOk(p)) return HeldRequest.Unshown
        return HeldRequest.ItemProxy(item, field, host, header, prefix, seconds, str(p, "replaces"))
    }

    /** Known top-level members only; `proxy` and `replaces` only where they belong. */
    private fun envelope(p: StrictObject, allowProxy: Boolean, allowReplaces: Boolean): Boolean =
        p.members.keys.all { it in TOP } && (allowProxy || !p.has("proxy")) && (allowReplaces || !p.has("replaces"))

    /** No member a run alone carries. */
    private fun noRunMembers(p: StrictObject): Boolean =
        !p.has("previous_sha256") && !p.has("previous_path") && !p.has("folders")

    /** `flags` as wire strings: absent is none; not a list of distinct strings is null. */
    private fun flags(p: StrictObject): List<String>? {
        if (!p.has("flags")) return emptyList()
        val names = (p["flags"] as? StrictArray)?.items?.map { it as? StrictString }?.takeIf { l -> l.all { it != null } }
        return names?.map { it!!.value }?.takeIf { it.toSet().size == it.size }
    }

    /** Until AA9 (#15812) is built, a run with member folders or files is not shown. */
    private fun aa9(flags: Set<RunFlag>, p: StrictObject, body: StrictObject): Boolean =
        flags.any { it in AA9_FLAGS } || p.members.keys.any { it in AA9_TOP } || body.members.keys.any { it in AA9_BODY }

    private fun hex64(o: StrictObject, name: String): String? = str(o, name)?.takeIf { HEX64.matches(it) }

    /** `replaces` is absent, or a non-empty string. */
    private fun replacesOk(p: StrictObject): Boolean = !p.has("replaces") || str(p, "replaces")?.isNotEmpty() == true

    /** A whole number as JSON writes it (no sign, fraction or exponent), 60 to 3,600; else null. */
    private fun seconds(v: StrictValue?): Int? = (v as? com.vettid.core.crypto.json.JsonNumber)?.raw
        ?.takeIf { WHOLE.matches(it) }?.toInt()?.takeIf { it in MIN_SECONDS..MAX_SECONDS }

    private val WHOLE = Regex("0|[1-9][0-9]{0,3}")

    private fun str(o: StrictObject, name: String): String? = (o[name] as? StrictString)?.value
}
