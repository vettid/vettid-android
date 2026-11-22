package com.vettid.core.data.devices

import com.vettid.core.crypto.json.JsonBool as StrictBool
import com.vettid.core.crypto.json.JsonObject as StrictObject
import com.vettid.core.crypto.json.JsonString as StrictString
import com.vettid.core.crypto.json.JsonValue as StrictValue
import com.vettid.core.crypto.json.StrictJson
import com.vettid.core.vault.VaultJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * How an agent may use an item (VAULT-MESSAGING §10.11 "Ways to use a secret"; ANDROID-PLAN 0.1.137 AA7): the settings
 * `leash.item.list` and `leash.item.set` carry (`item_ways`), read strictly, checked as the vault checks them, and
 * compared for a widening. Memory only; prints no values.
 */
enum class ItemWay(val wire: String) {
    USE("use"),
    RUN("run"),
    PROXY("proxy"),
    REVEAL("reveal"),
    ;

    companion object {
        fun of(wire: String): ItemWay? = entries.firstOrNull { it.wire == wire }
    }
}

/** One program of a run binding: as typed ([program]), the pin the vault recorded ([sha256], [path]: both or neither), its variables. */
data class RunProgram(val program: String, val sha256: String? = null, val path: String? = null, val env: List<String> = emptyList()) {
    override fun toString(): String = "RunProgram"
}

/** A member folder a run may use (AA9): read only unless [write]. */
data class RunFolder(val path: String, val write: Boolean) {
    override fun toString(): String = "RunFolder"
}

/**
 * The run settings. [folders] is what the vault returned (VAULT-MESSAGING 0.93.0: an empty list when none), null when the
 * answer had no `run.folders` (a vault before 0.93.0): it is sent back as read.
 */
data class RunSettings(val programs: List<RunProgram> = emptyList(), val each: Boolean = true, val folders: List<RunFolder>? = null) {
    override fun toString(): String = "RunSettings"
}

/** The API settings: 1–8 hosts, the header's name and what goes before the key. */
data class ProxySettings(val hosts: List<String>, val header: String, val prefix: String = "") {
    override fun toString(): String = "ProxySettings"
}

/**
 * One item's settings for one agent, as the app understands them. [version] is absent for an item with no settings
 * (`["use"]` only, VAULT-MESSAGING 0.91.0 "Versions"). [run] is set exactly when [ways] holds run, [proxy] likewise.
 */
data class ItemWays(
    val agentId: String,
    val itemId: String,
    val version: Long? = null,
    val ways: Set<ItemWay> = setOf(ItemWay.USE),
    val run: RunSettings? = null,
    val proxy: ProxySettings? = null,
) {
    override fun toString(): String = "ItemWays"

    /**
     * The `leash.item.set` body for these settings (AA7 "Saving"): `version` as read (absent when the item had none), ways
     * in wire order, a program's pin only as it was read (the vault records pins, never the app), folders as read.
     */
    fun setBody(): JsonObject = buildJsonObject {
        put("agent_id", agentId)
        put("item_id", itemId)
        version?.let { put("version", it) }
        put("ways", buildJsonArray { ItemWay.entries.filter { it in ways }.forEach { add(JsonPrimitive(it.wire)) } })
        run?.let { r ->
            put(
                "run",
                buildJsonObject {
                    put(
                        "programs",
                        buildJsonArray {
                            r.programs.forEach { p ->
                                add(
                                    buildJsonObject {
                                        put("program", p.program)
                                        if (p.sha256 != null && p.path != null) {
                                            put("sha256", p.sha256)
                                            put("path", p.path)
                                        }
                                        put("env", JsonArray(p.env.map { JsonPrimitive(it) }))
                                    },
                                )
                            }
                        },
                    )
                    put("each", r.each)
                    r.folders?.let { fs ->
                        put(
                            "folders",
                            JsonArray(fs.map { f -> buildJsonObject { put("path", f.path); put("write", f.write) } }),
                        )
                    }
                },
            )
        }
        proxy?.let { p ->
            put(
                "proxy",
                buildJsonObject {
                    put("hosts", JsonArray(p.hosts.map { JsonPrimitive(it) }))
                    put("header", p.header)
                    put("prefix", p.prefix)
                },
            )
        }
    }
}

/**
 * An `item_ways` as read. [ways] is set when the app understands all of it, which is when it may be edited (AA7 "Fail
 * closed"); [readOnly] otherwise. [raw] is the answer as read (decoded leniently), which a read-only page's narrowing
 * save sends back with one way taken out ([ItemWaysRules.withoutWay]); [rawWays] are the ways it lists, as wire strings,
 * so such a page can still offer to turn each off.
 */
data class ItemWaysRead(
    val agentId: String,
    val itemId: String,
    val ways: ItemWays?,
    val raw: JsonObject,
    val rawWays: List<String>,
) {
    val readOnly: Boolean get() = ways == null

    /** The vault's answer held `run.folders` (VAULT-MESSAGING 0.93.0): AA9's folders settings may be shown. */
    val foldersKnown: Boolean get() = ways?.run?.folders != null

    override fun toString(): String = "ItemWaysRead"
}

/** Why a program can't be added (AA7 "Add a program"), each with its own text. */
enum class ProgramProblem { NOT_PATH_OR_NAME, CHARACTERS, TOO_LONG, PATH_PARTS, NETWORK_PATH, TILDE, DUPLICATE, TOO_MANY }

/** Why a variable name can't be added (AA7 "Add a variable"). */
enum class VariableProblem { FORM, TOO_MANY }

/** Why a host can't be added (AA7 "Add a host"). */
enum class HostProblem { NOT_A_HOST, IP_ADDRESS, LOCAL, WILDCARD, DUPLICATE, TOO_MANY }

/**
 * The checks of VAULT-MESSAGING §10.11, as the vault makes them (vettid-vault `features/itemspec`), so that a setting the
 * app accepts is one the vault accepts; the vault decides, and its `bad_request` is shown when the two differ. The
 * variable names the vault refuses by name (code-loading and traffic names, `VETTID_IN`, `VETTID_OUT`) are not copied
 * here (AA7: "The app does not keep its own copy of that list").
 */
@Suppress("TooManyFunctions")
object ItemWaysRules {
    const val MAX_PROGRAMS = 16
    const val MAX_PROGRAM_BYTES = 4096
    const val MAX_ENV = 32
    const val MAX_HOSTS = 8
    const val MAX_HEADER = 64
    const val MAX_PREFIX = 32
    const val MAX_FOLDERS = 16
    private const val MAX_DNS_NAME = 253
    private const val MAX_LABEL = 63
    private const val MAX_PORT = 65535
    private const val MAX_PORT_DIGITS = 5
    private const val SHA256_HEX = 64
    private const val BACKSLASH = '\\'

    /** A drive path's root, `X:\` or `X:/`, is this long. */
    private const val DRIVE_ROOT = 3
    private val ENV_NAME = Regex("^[A-Z_][A-Z0-9_]{0,63}$")
    private val HEADER_CHARS = Regex("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$")
    private val REFUSED_HEADERS = setOf(
        "connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "proxy-connection", "te", "trailer",
        "transfer-encoding", "upgrade", "host", "content-length", "cookie",
    )

    /** Absolute as the vault reads it: `/` then a name (never `//`), or a drive path `X:\` or `X:/` (never `X:\\`). */
    fun isAbsolute(p: String): Boolean =
        if (p.startsWith("/")) !p.startsWith("//") else isDrivePath(p)

    /** `X:\` or `X:/` and a name after it, never `X:\\` (a drive path's root followed by another separator). */
    private fun isDrivePath(p: String): Boolean {
        val root = p.length >= DRIVE_ROOT && p[0].isAsciiLetter() && p[1] == ':' && p[2].isSeparator()
        return root && !(p.length > DRIVE_ROOT && p[DRIVE_ROOT].isSeparator())
    }


    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    private fun Char.isSeparator() = this == '/' || this == BACKSLASH

    private val REFUSED_TYPES = setOf(
        Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(),
        Character.PARAGRAPH_SEPARATOR.toInt(), Character.SURROGATE.toInt(),
    )

    /** A path's last element (after the last `/` or `\`). */
    fun lastElement(p: String): String = p.substring(maxOf(p.lastIndexOf('/'), p.lastIndexOf(BACKSLASH)) + 1)

    /** No control, format (Cf), line or paragraph separator character: what the vault refuses in a path. */
    fun showable(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (Character.getType(cp) in REFUSED_TYPES) return false
            i += Character.charCount(cp)
        }
        return true
    }

    /** After the root, no empty element, no `.` or `..` element and no `:` (vettid-vault `cleanPath`, the root itself refused). */
    private fun cleanPath(p: String): Boolean {
        val rest = if (p[0] == '/') p.substring(1) else p.substring(DRIVE_ROOT)
        if (rest.isEmpty()) return false
        return rest.split('/', BACKSLASH).none { it.isEmpty() || it == "." || it == ".." || it.contains(':') }
    }

    /**
     * The key two programs are compared by (the no-two-match rule, 0.85.3): without case and without a final `.exe`, a
     * drive path's `/` as `\`, trailing dots and spaces of the last element dropped.
     */
    fun programKey(p: String): String {
        val k = simpleLower(p)
        val i = maxOf(k.lastIndexOf('/'), k.lastIndexOf(BACKSLASH))
        var dir = if (i >= 0) k.substring(0, i + 1) else ""
        var last = if (i >= 0) k.substring(i + 1) else k
        if (dir.length >= DRIVE_ROOT && dir[0] in 'a'..'z' && dir[1] == ':') dir = dir.replace('/', BACKSLASH)
        last = last.trimEnd(' ', '.').removeSuffix(".exe").trimEnd(' ', '.')
        return dir + last
    }

    /** Lower case rune by rune with the simple mapping, as Go's `strings.ToLower` (no final sigma, `İ` one rune). */
    private fun simpleLower(s: String): String {
        val b = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            b.appendCodePoint(Character.toLowerCase(cp))
            i += Character.charCount(cp)
        }
        return b.toString()
    }

    /** Why [program] can't be a binding's program, or null when it can ("Program strings"). */
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun programProblem(program: String): ProgramProblem? {
        if (program.toByteArray(Charsets.UTF_8).size > MAX_PROGRAM_BYTES) return ProgramProblem.TOO_LONG
        if (program.isEmpty()) return ProgramProblem.NOT_PATH_OR_NAME
        if (!showable(program)) return ProgramProblem.CHARACTERS
        if (program.startsWith("//") || program.startsWith("$BACKSLASH$BACKSLASH")) return ProgramProblem.NETWORK_PATH
        if (isAbsolute(program)) {
            if (!cleanPath(program)) return ProgramProblem.PATH_PARTS
            if (lastElement(program).trimEnd(' ', '.').isEmpty()) return ProgramProblem.NOT_PATH_OR_NAME
        } else {
            if (program.contains('/') || program.contains(BACKSLASH)) return ProgramProblem.NOT_PATH_OR_NAME
            if (program.contains(':')) return ProgramProblem.PATH_PARTS
            if (program.trim(' ', '.').isEmpty()) return ProgramProblem.NOT_PATH_OR_NAME
        }
        // An 8.3 short name (BASH~1.EXE) would read on the phone as another program (owner decision a, #14596).
        if (lastElement(program).contains('~')) return ProgramProblem.TILDE
        return null
    }

    /** Why [program] can't be added to [programs]: its own problem, a duplicate by the no-two-match rule, or the limit. */
    fun addProgramProblem(programs: List<RunProgram>, program: String): ProgramProblem? = when {
        programProblem(program) != null -> programProblem(program)
        duplicates(programs.map { it.program }, program) -> ProgramProblem.DUPLICATE
        programs.size >= MAX_PROGRAMS -> ProgramProblem.TOO_MANY
        else -> null
    }

    /**
     * Whether [program] collides with one of [existing] (0.85.3): equal keys, or a bare name equal to an absolute
     * entry's last element (either way round).
     */
    fun duplicates(existing: List<String>, program: String): Boolean {
        val k = programKey(program)
        return existing.any { e ->
            programKey(e) == k ||
                isAbsolute(program) && !isAbsolute(e) && programKey(lastElement(program)) == programKey(e) ||
                !isAbsolute(program) && isAbsolute(e) && programKey(lastElement(e)) == k
        }
    }

    /** Why [name] can't be added to [env] (the form only; the vault refuses named ones with `bad_request`). */
    fun variableProblem(env: List<String>, name: String): VariableProblem? = when {
        !ENV_NAME.matches(name) -> VariableProblem.FORM
        name !in env && env.size >= MAX_ENV -> VariableProblem.TOO_MANY
        else -> null
    }

    /** A host as typed, lowercased (AA7: "The app lowercases it"). */
    fun normalizeHost(h: String): String = h.trim().lowercase()

    /** The key two hosts are compared by: the default port made explicit. */
    fun hostKey(h: String): String = if (h.lastIndexOf(':') < 0) "$h:443" else h

    /** Why [host] (already lowercased) is not one §10.11 allows, or null. */
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun hostProblem(host: String): HostProblem? {
        if (host.contains('*')) return HostProblem.WILDCARD
        if (host.contains('[') || host.count { it == ':' } > 1) return HostProblem.IP_ADDRESS
        var name = host
        val colon = host.lastIndexOf(':')
        if (colon >= 0) {
            name = host.substring(0, colon)
            val port = host.substring(colon + 1)
            if (!portValid(port)) return HostProblem.NOT_A_HOST
        }
        if (name.isEmpty() || name.length > MAX_DNS_NAME) return HostProblem.NOT_A_HOST
        val labels = name.split('.')
        val last = labels.last()
        if (last.isNotEmpty() && (last.all { it in '0'..'9' } || last.startsWith("0x"))) return HostProblem.IP_ADDRESS
        if (labels.size < 2 || last == "localhost") return HostProblem.LOCAL
        val ok = labels.all { l ->
            l.isNotEmpty() && l.length <= MAX_LABEL && l[0] != '-' && l.last() != '-' &&
                l.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
        }
        return if (ok) null else HostProblem.NOT_A_HOST
    }

    /** A port of 1–65,535, written plainly (no sign, no leading zero). */
    private fun portValid(port: String): Boolean =
        port.length in 1..MAX_PORT_DIGITS && port[0] != '0' && port.all { it in '0'..'9' } && port.toInt() <= MAX_PORT

    /** Why [host] can't be added to [hosts]: its own problem, a duplicate (by [hostKey]) or the limit. */
    fun addHostProblem(hosts: List<String>, host: String): HostProblem? = when {
        hostProblem(host) != null -> hostProblem(host)
        hosts.any { hostKey(it) == hostKey(host) } -> HostProblem.DUPLICATE
        hosts.size >= MAX_HOSTS -> HostProblem.TOO_MANY
        else -> null
    }

    /**
     * A header that can carry the key: a token of 1–64 characters, not hop-by-hop, `Host`, `Content-Length`,
     * `Transfer-Encoding` or `Cookie`.
     */
    fun headerValid(h: String): Boolean =
        h.length in 1..MAX_HEADER && HEADER_CHARS.matches(h) && h.lowercase() !in REFUSED_HEADERS

    /** What goes before the key: 0–32 printable ASCII characters. */
    fun prefixValid(p: String): Boolean = p.length <= MAX_PREFIX && p.all { it in ' '..'~' }

    private fun isSha256(s: String): Boolean = s.length == SHA256_HEX && s.all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * Whether [next] allows more than [cur] (VAULT-MESSAGING §10.11 "Widening needs the member", as vettid-vault's
     * `Widens`): a way, a program or a host added, programs emptied, a variable added to a program, Ask me before each run
     * turned off, the header's name changed (not only its case), a folder added or its write turned on (AA9). Binding an
     * unbound item narrows unless a program lists a variable or each is off; a full path that replaces a name narrows
     * only when its last part is exactly that name and it adds no variable.
     */
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount")
    fun widens(cur: ItemWays?, next: ItemWays): Boolean {
        val curWays = cur?.ways ?: setOf(ItemWay.USE)
        if (next.ways.any { it !in curWays }) return true
        val nr = next.run
        val cr = cur?.run
        if (nr != null && cr != null) {
            if (nr.programs.isEmpty() && cr.programs.isNotEmpty()) return true
            val unbound = cr.programs.isEmpty()
            for (p in nr.programs) {
                val q = cr.programs.firstOrNull { it.program == p.program }
                    ?: when {
                        unbound -> RunProgram("")
                        isAbsolute(p.program) ->
                            cr.programs.firstOrNull { !isAbsolute(it.program) && it.program == lastElement(p.program) }
                                ?: return true
                        else -> return true
                    }
                if (p.env.any { it !in q.env }) return true
            }
            if (!nr.each && cr.each) return true
            for (f in nr.folders.orEmpty()) {
                val g = cr.folders.orEmpty().firstOrNull { it.path == f.path }
                if (g == null || f.write && !g.write) return true
            }
        }
        val np = next.proxy
        val cp = cur?.proxy
        if (np != null && cp != null) {
            if (!np.header.equals(cp.header, ignoreCase = true)) return true
            val have = cp.hosts.map { hostKey(it) }.toSet()
            if (np.hosts.any { hostKey(it) !in have }) return true
        }
        return false
    }

    /**
     * Reads `leash.item.list`'s answer (`{items: [<item_ways>]}`) strictly. An answer that does not read strictly (a
     * duplicate member anywhere) is read leniently, and every item in it is read only; an item of another agent than
     * [agentId] too. Null when the answer is not a list of objects at all (it is never "no items").
     */
    @Suppress("ReturnCount") // each early null is an answer that is not a list of objects
    fun list(answer: ByteArray, agentId: String? = null): List<ItemWaysRead>? {
        val lenient = runCatching { VaultJson.parseObject(answer) }.getOrNull() ?: return null
        val items = (lenient["items"] as? JsonArray)?.map { it as? JsonObject ?: return null } ?: return null
        val strict = runCatching { StrictJson.parseObject(answer).array("items") }.getOrNull()?.takeIf { it.size == items.size }
        return items.mapIndexed { i, raw ->
            val r = read(raw, strict?.get(i) as? StrictObject)
            if (agentId != null && r.agentId != agentId) r.copy(ways = null) else r
        }
    }

    /** Reads one `<item_ways>` answer (`leash.item.set`'s) strictly, as [list] reads each item. */
    fun one(answer: ByteArray): ItemWaysRead? {
        val raw = runCatching { VaultJson.parseObject(answer) }.getOrNull() ?: return null
        val strict = runCatching { StrictJson.parseObject(answer) }.getOrNull()
        return read(raw, strict)
    }

    private fun read(raw: JsonObject, strict: StrictObject?): ItemWaysRead {
        val agent = VaultJson.str(raw, "agent_id").orEmpty()
        val item = VaultJson.str(raw, "item_id").orEmpty()
        val rawWays = (raw["ways"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        // The strict reading counts only when it is of the same item the lenient one names.
        val ways = strict?.let { runCatching { parse(it) }.getOrNull() }?.takeIf { it.agentId == agent && it.itemId == item }
        return ItemWaysRead(agent, item, ways, raw, rawWays)
    }

    private val TOP = setOf("agent_id", "item_id", "version", "ways", "run", "proxy", "updated_at")
    private val RUN = setOf("programs", "each", "folders")
    private val PROGRAM = setOf("program", "sha256", "path", "env")
    private val FOLDER = setOf("path", "write")
    private val PROXY = setOf("hosts", "header", "prefix")

    private class Unreadable : Exception()

    private fun check(ok: Boolean) {
        if (!ok) throw Unreadable()
    }

    private fun StrictObject.only(names: Set<String>) = check(members.keys.all { it in names })

    private fun StrictValue.str(): String = (this as? StrictString)?.value ?: throw Unreadable()

    /**
     * The settings when the app understands all of them (AA7 "Fail closed"), else [Unreadable]: an unknown member at any
     * level, a way the app does not know, `run` or `proxy` present without its way or the reverse, a malformed value,
     * and (a version without AA9) a non-empty `run.folders`.
     */
    @Suppress("CyclomaticComplexMethod")
    private fun parse(o: StrictObject): ItemWays {
        o.only(TOP)
        val agent = o.string("agent_id")
        val item = o.string("item_id")
        check(agent.isNotEmpty() && item.isNotEmpty())
        val version = o.optUint("version", 1, StrictJson.MAX_SAFE_INTEGER)
        o.optString("updated_at")
        val wayList = o.array("ways").map { ItemWay.of(it.str()) ?: throw Unreadable() }
        val ways = wayList.toSet()
        check(ways.size == wayList.size && ItemWay.USE in ways)
        check(o.has("run") == (ItemWay.RUN in ways) && o.has("proxy") == (ItemWay.PROXY in ways))
        val run = o.optObj("run")?.let { runSettings(it) }
        val proxy = o.optObj("proxy")?.let { proxySettings(it) }
        return ItemWays(agent, item, version, ways, run, proxy)
    }

    @Suppress("ThrowsCount") // each malformed value is one Unreadable, as the vault refuses each
    private fun runSettings(r: StrictObject): RunSettings {
        r.only(RUN)
        val arr = r.array("programs")
        check(arr.size <= MAX_PROGRAMS)
        val programs = arr.map { v ->
            val p = v as? StrictObject ?: throw Unreadable()
            p.only(PROGRAM)
            val program = p.string("program")
            check(programProblem(program) == null)
            val sha = p.optString("sha256")
            val path = p.optString("path")
            check((sha == null) == (path == null) && (sha == null || isSha256(sha) && isAbsolute(path!!)))
            val env = p.optArray("env").orEmpty().map { it.str() }
            check(env.size <= MAX_ENV && env.all { ENV_NAME.matches(it) } && env.toSet().size == env.size)
            RunProgram(program, sha, path, env)
        }
        val keys = programs.map { programKey(it.program) }
        check(keys.toSet().size == keys.size)
        // A bare name equal to an absolute entry's last element (the vault's second rule).
        check(
            programs.none { p ->
                isAbsolute(p.program) &&
                    programs.any { !isAbsolute(it.program) && programKey(it.program) == programKey(lastElement(p.program)) }
            },
        )
        val each = if (r.has("each")) (r["each"] as? StrictBool)?.value ?: throw Unreadable() else true
        check(each || programs.isNotEmpty())
        val folders = r.optArray("folders")?.map { v ->
            val f = v as? StrictObject ?: throw Unreadable()
            f.only(FOLDER)
            RunFolder(f.string("path"), (f["write"] as? StrictBool)?.value ?: throw Unreadable())
        }
        // A version without AA9 (ANDROID-PLAN 0.1.137 AA7 "Fail closed"): a folder it can't show is read only; an empty
        // list is sent back as read.
        check(folders.isNullOrEmpty())
        return RunSettings(programs, each, folders)
    }

    private fun proxySettings(p: StrictObject): ProxySettings {
        p.only(PROXY)
        val hosts = p.array("hosts").map { it.str() }
        check(hosts.size in 1..MAX_HOSTS && hosts.all { hostProblem(it) == null })
        check(hosts.map { hostKey(it) }.toSet().size == hosts.size)
        val header = p.string("header")
        check(headerValid(header))
        val prefix = p.optString("prefix") ?: ""
        check(prefixValid(prefix))
        return ProxySettings(hosts, header, prefix)
    }

    /**
     * A read-only page's save of [way] turned off (AA7 "Fail closed"): the settings exactly as read, with the way taken
     * out of `ways` and its member removed; every other member, unknown ones included, as read. It only narrows. Null
     * when [way] can't be turned off (`use`, or `ways` that does not read as a list of strings).
     */
    fun withoutWay(read: ItemWaysRead, way: String): JsonObject? {
        val ways = read.raw["ways"] as? JsonArray
        val listed = ways != null && ways.all { it is JsonPrimitive && it.isString }
        if (way == ItemWay.USE.wire || way !in read.rawWays || !listed) return null
        val out = read.raw.toMutableMap<String, JsonElement>()
        out["ways"] = JsonArray(ways!!.filter { (it as JsonPrimitive).content != way })
        // The way's own member (run, proxy, or an unknown way's), never one of the item's own members.
        if (way !in TOP || way == ItemWay.RUN.wire || way == ItemWay.PROXY.wire) out.remove(way)
        out.remove("updated_at")
        return JsonObject(out)
    }
}
