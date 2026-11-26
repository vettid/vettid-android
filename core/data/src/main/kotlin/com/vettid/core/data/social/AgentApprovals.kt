package com.vettid.core.data.social

import com.vettid.core.crypto.json.JsonArray as StrictArray
import com.vettid.core.crypto.json.JsonObject as StrictObject
import com.vettid.core.crypto.json.JsonString as StrictString
import com.vettid.core.crypto.json.JsonValue as StrictValue
import com.vettid.core.crypto.json.JsonBool as StrictBool
import com.vettid.core.data.devices.ItemWaysRules
import com.vettid.core.data.devices.RunFolder
import com.vettid.core.data.devices.RunFolders

/**
 * An agent's `item.run`, `item.proxy` and `item.get` held for approval (and, for [named], its `item.use` and `catalog` too),
 * read as ANDROID-PLAN 0.1.139 AA8 "Fail closed" says:
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

    /** AA9: `folders`' members, an entry's, an input's and `output`'s (VAULT-MESSAGING 0.93.2 §10.11). */
    private val FOLDERS = setOf("cwd", "output")
    private val ENTRY = setOf("path", "write")
    private val INPUT = setOf("path", "size")
    private val OUTPUT = setOf("folder", "names")

    /** `inputs` and `output.names`: at most 16 each; a file at most 16 MiB, all of them 64 MiB; the input paths 16,384 bytes. */
    private const val MAX_FILES = 16
    private const val MAX_FILE = 16_777_216L
    private const val MAX_FILES_TOTAL = 67_108_864L
    private const val MAX_INPUT_PATHS = 16_384
    private const val MAX_NAME_BYTES = 255

    /** A run's program is unbound, unrecorded or changed: never two of these. */
    private val PIN_STATES = setOf(RunFlag.UNBOUND_PROGRAM, RunFlag.PROGRAM_UNRECORDED, RunFlag.PROGRAM_CHANGED)

    /** A proxy's length (`body.seconds`): 60 to 3,600, 900 when absent. */
    const val MIN_SECONDS = 60
    const val MAX_SECONDS = 3_600
    const val DEFAULT_SECONDS = 900

    private val HEX64 = Regex("[0-9a-f]{64}")

    /** The members that change what approving means: on an `item.use` or a `catalog` none of them may appear (AA8). */
    private val MEANINGFUL = setOf("flags", "previous_sha256", "previous_path", "replaces", "proxy", "folders")

    /**
     * An `item.use`'s or a `catalog`'s envelope, read strictly (so no duplicate member): [named], and none of [MEANINGFUL]. Another
     * unknown member is ignored, as §6.8 step 1 says for these types.
     */
    fun plainShown(p: StrictObject): Boolean = named(p) && p["body"] is StrictObject && MEANINGFUL.none { p.has(it) }

    /**
     * (ANDROID-PLAN 0.1.145) A desktop's held block or removal: its envelope names the device and itself as AA8 "Fail closed"'s
     * 0.1.139 rule says, has a body, and carries none of the members that change what approving means (an agent's).
     */
    fun desktopShown(p: StrictObject): Boolean = plainShown(p)

    /** An `item.get`'s envelope: known members only, no `proxy` or `replaces`, and `flags` at most `history`. */
    @Suppress("ReturnCount") // each early false is one rule of AA8's list
    fun getShown(p: StrictObject): Boolean {
        if (!named(p) || p["body"] !is StrictObject) return false
        val flags = flags(p) ?: return false
        return envelope(p, allowProxy = false, allowReplaces = false) && flags.all { it == "history" } && noRunMembers(p)
    }

    /** An `item.run`, or [HeldRequest.Unshown]. */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    fun run(p: StrictObject): HeldRequest {
        if (!named(p) || !envelope(p, allowProxy = false, allowReplaces = true)) return HeldRequest.Unshown
        val body = p["body"] as? StrictObject ?: return HeldRequest.Unshown
        if (!body.members.keys.all { it in RUN_BODY }) return HeldRequest.Unshown
        val wire = flags(p) ?: return HeldRequest.Unshown
        val flags = wire.map { RunFlag.of(it) ?: return HeldRequest.Unshown }.toSet()
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
        val files = places(flags, p, body, cwd) ?: return HeldRequest.Unshown
        return HeldRequest.ItemRun(
            item, field, program, sha, argv, cwd, how, name, env, flags, prevSha, prevPath, str(p, "replaces"),
            files.cwdEntry, files.inputs, files.output,
        )
    }

    /** An `item.proxy`, or [HeldRequest.Unshown]. */
    @Suppress("ReturnCount", "CyclomaticComplexMethod") // each early return is one rule of AA8's list
    fun proxy(p: StrictObject): HeldRequest {
        if (!named(p) || !envelope(p, allowProxy = true, allowReplaces = true) || !noRunMembers(p)) return HeldRequest.Unshown
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

    /**
     * The request names its device and itself and has a time to end: a non-blank string `device_id`, a `name` the screen can
     * show (not only spaces or invisible characters once cleaned), and an `exp`
     * that reads as a time. The screen says who asks and how long it waits, and the approval ends at `exp`; without them it
     * would show "an agent" that never expires, so every agent op is deny only (AA8 "Fail closed"; owner a, GC #17671, as iOS).
     */
    private fun named(p: StrictObject): Boolean =
        !str(p, "device_id").isNullOrBlank() &&
            // The name the screen shows (cleaned as `device.list`'s are): one of only spaces or invisible characters
            // would show "An agent" with Approve offered.
            com.vettid.core.data.devices.DeviceWire.cleanName(str(p, "name")).isNotEmpty() &&
            ApprovalParser.instant(str(p, "exp")) != null

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

    private class Places(val cwdEntry: RunFolder?, val inputs: List<RunInput>, val output: RunOutput?)

    /**
     * AA9: a run's folder and files, or null (deny only). `member_folder` is exactly a `cwd` with `folders.cwd`, and
     * `member_files` exactly non-empty `inputs` or an `output` with `folders.output`, which can change files; `folders` comes
     * with one of the flags and only then. The `cwd` and the output folder lie in their entries (the vault's comparison:
     * element by element, exactly), the names are flat and not reserved, the sizes within the vault's caps. The vault
     * refuses all of these before it holds a run; the checks only refuse more.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun places(flags: Set<RunFlag>, p: StrictObject, body: StrictObject, cwd: String?): Places? {
        val inputs = if (body.has("inputs")) inputs(body["inputs"]) ?: return null else emptyList()
        val output = if (body.has("output")) body["output"] as? StrictObject ?: return null else null
        val memberFolder = RunFlag.MEMBER_FOLDER in flags
        val memberFiles = RunFlag.MEMBER_FILES in flags
        if (memberFiles != (inputs.isNotEmpty() || output != null)) return null
        // A member folder's `cwd` is clean and absolute, as the vault requires (a `..` could leave its entry).
        if (memberFolder && (cwd == null || RunFolders.problem(cwd) != null)) return null
        if (!memberFolder && !memberFiles) return if (p.has("folders")) null else Places(null, emptyList(), null)
        // From a vault that carries `run.folders`, every `cwd` is a member folder.
        if (cwd != null && !memberFolder) return null
        val folders = p["folders"] as? StrictObject ?: return null
        if (!folders.members.keys.all { it in FOLDERS }) return null
        val cwdEntry = if (folders.has("cwd")) entry(folders["cwd"]) ?: return null else null
        if (memberFolder != (cwdEntry != null)) return null
        if (cwdEntry != null && !within(cwd!!, cwdEntry.path)) return null
        val outEntry = if (folders.has("output")) entry(folders["output"]) ?: return null else null
        if ((output != null) != (outEntry != null)) return null
        val out = output?.let { o -> output(o, outEntry!!) ?: return null }
        return Places(cwdEntry, inputs, out)
    }

    /** A `folders` entry: `{path, write}`, both there, the path one the vault accepts in a binding. */
    @Suppress("ReturnCount")
    private fun entry(v: StrictValue?): RunFolder? {
        val o = v as? StrictObject ?: return null
        if (o.members.keys != ENTRY) return null
        val path = str(o, "path")?.takeIf { RunFolders.problem(it) == null } ?: return null
        val write = (o["write"] as? StrictBool)?.value ?: return null
        return RunFolder(path, write)
    }

    /** `inputs`: 1–16 of `{path, size}`, each path clean and absolute with a flat last name, the names distinct. */
    @Suppress("ReturnCount")
    private fun inputs(v: StrictValue?): List<RunInput>? {
        val items = (v as? StrictArray)?.items ?: return null
        // ANDROID-PLAN 0.1.138 AA8 "Fail closed": `inputs` without `member_files` is deny only, an empty list included.
        if (items.isEmpty() || items.size > MAX_FILES) return null
        val out = items.map { i ->
            val o = i as? StrictObject ?: return null
            if (o.members.keys != INPUT) return null
            val path = str(o, "path")?.takeIf { RunFolders.problem(it) == null } ?: return null
            val size = size(o["size"]) ?: return null
            RunInput(path, size)
        }
        if (out.sumOf { it.size } > MAX_FILES_TOTAL) return null
        if (out.sumOf { it.path.toByteArray(Charsets.UTF_8).size } > MAX_INPUT_PATHS) return null
        return out.takeIf { names(it.map { f -> f.name }) }
    }

    /** `output`: `{folder, names}`, 1–16 flat names, distinct; the folder in its entry, which can change files. */
    @Suppress("ReturnCount")
    private fun output(o: StrictObject, entry: RunFolder): RunOutput? {
        if (o.members.keys != OUTPUT || !entry.write) return null
        val folder = str(o, "folder")?.takeIf { RunFolders.problem(it) == null && within(it, entry.path) } ?: return null
        val names = (o["names"] as? StrictArray)?.items?.map { (it as? StrictString)?.value ?: return null } ?: return null
        if (names.size !in 1..MAX_FILES || !names(names)) return null
        return RunOutput(folder, names, entry)
    }

    /** Flat names (§10.11), distinct on the folder key ("No entry may be equal to, under or above another"). */
    private fun names(names: List<String>): Boolean =
        names.all { flatName(it) } && names.map { RunFolders.elementKey(it) }.toSet().size == names.size

    /**
     * A flat name: 1–255 bytes of UTF-8, no control, Cf, Zl or Zp character, no `/`, `\` or `:`, not `.` or `..`, no
     * trailing dot or space, and not a reserved Windows name ([reserved]).
     */
    fun flatName(n: String): Boolean =
        n.toByteArray(Charsets.UTF_8).size in 1..MAX_NAME_BYTES && ItemWaysRules.showable(n) &&
            n.none { it == '/' || it == '\\' || it == ':' } && n != "." && n != ".." &&
            !n.endsWith('.') && !n.endsWith(' ') && !reserved(n)

    /** §10.11's reserved names, wider than Microsoft's: judged up to the first dot, trailing spaces dropped, upper-cased. */
    private val RESERVED: Set<String> = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$"))
        for (d in listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "\u00B9", "\u00B2", "\u00B3")) {
            add("COM$d")
            add("LPT$d")
        }
    }

    /**
     * Whether [n] is a reserved name: its part before the first dot, trailing spaces dropped, upper-cased rune by rune by
     * the simple mapping (Go's `strings.ToUpper`, locale-independent), is one of [RESERVED].
     */
    fun reserved(n: String): Boolean {
        val base = n.substringBefore('.').trimEnd(' ')
        val up = StringBuilder(base.length)
        var i = 0
        while (i < base.length) {
            val cp = base.codePointAt(i)
            up.appendCodePoint(Character.toUpperCase(cp))
            i += Character.charCount(cp)
        }
        return up.toString() in RESERVED
    }

    /** [path] equals [entry] or lies under it: element by element, exactly (`/` and `\` both separate), never as a text prefix. */
    private fun within(path: String, entry: String): Boolean {
        val a = path.split('/', '\\')
        val e = entry.split('/', '\\')
        return a.size >= e.size && a.subList(0, e.size) == e
    }

    /** A file's size: a whole number as JSON writes it (no sign, fraction or exponent), at most 16 MiB. */
    private fun size(v: StrictValue?): Long? = (v as? com.vettid.core.crypto.json.JsonNumber)?.raw
        ?.takeIf { WHOLE_SIZE.matches(it) }?.toLong()?.takeIf { it <= MAX_FILE }

    private val WHOLE_SIZE = Regex("0|[1-9][0-9]{0,7}")

    private fun hex64(o: StrictObject, name: String): String? = str(o, name)?.takeIf { HEX64.matches(it) }

    /** `replaces` is absent, or a non-empty string. */
    private fun replacesOk(p: StrictObject): Boolean = !p.has("replaces") || str(p, "replaces")?.isNotEmpty() == true

    /** A whole number as JSON writes it (no sign, fraction or exponent), 60 to 3,600; else null. */
    private fun seconds(v: StrictValue?): Int? = (v as? com.vettid.core.crypto.json.JsonNumber)?.raw
        ?.takeIf { WHOLE.matches(it) }?.toInt()?.takeIf { it in MIN_SECONDS..MAX_SECONDS }

    private val WHOLE = Regex("0|[1-9][0-9]{0,3}")

    private fun str(o: StrictObject, name: String): String? = (o[name] as? StrictString)?.value
}
