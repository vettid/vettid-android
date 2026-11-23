package com.vettid.core.data.devices

import java.text.Normalizer

/** Why a folder can't be added (ANDROID-PLAN 0.1.138 AA9 "Add a folder"), each with its own text. */
enum class FolderProblem { NOT_FULL_PATH, WHOLE_DRIVE, DUPLICATE, NESTED, TOO_MANY, DOTS_ONLY }

/**
 * The run binding's folders (VAULT-MESSAGING 0.93.2 §10.11 "Member folders for runs"; ANDROID-PLAN 0.1.138 AA9), checked as
 * the vault checks them, so a folder the app accepts is one the vault accepts. Pure.
 */
object RunFolders {
    private const val MAX_BYTES = 4096
    private const val BACKSLASH = '\\'

    /** A drive path's root, `X:\` or `X:/`, is this long. */
    private const val DRIVE_ROOT = 3

    /** An empty, `.` or `..` element: the path is not clean ("Give a full path."). */
    private val DOT_ELEMENTS = setOf("", ".", "..")

    /**
     * Why [path] can't be a folder entry, or null: as a run's `cwd` ("Program strings": at most 4,096 bytes, no control,
     * Cf, Zl or Zp character, absolute, clean, no `:` after the root), not the root itself, and (0.93.1) no element made
     * only of dots and spaces. A `.` or `..` element is "Give a full path." (AA9), the other dots-and-spaces ones their own.
     */
    @Suppress("ReturnCount")
    fun problem(path: String): FolderProblem? {
        if (path.isEmpty() || path.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return FolderProblem.NOT_FULL_PATH
        if (!ItemWaysRules.showable(path)) return FolderProblem.NOT_FULL_PATH
        if (!ItemWaysRules.isAbsolute(path)) return FolderProblem.NOT_FULL_PATH
        val rest = if (path[0] == '/') path.substring(1) else path.substring(DRIVE_ROOT)
        if (rest.isEmpty()) return FolderProblem.WHOLE_DRIVE
        val elements = rest.split('/', BACKSLASH)
        if (elements.any { it in DOT_ELEMENTS || it.contains(':') }) return FolderProblem.NOT_FULL_PATH
        if (elements.any { e -> e.all { it == '.' || it == ' ' } }) return FolderProblem.DOTS_ONLY
        return null
    }

    /** Why [path] can't be added to [folders]: its own problem, the same folder or one inside another (by [key]), the limit. */
    fun addProblem(folders: List<RunFolder>, path: String): FolderProblem? {
        problem(path)?.let { return it }
        val k = key(path)
        return when {
            folders.any { key(it.path) == k } -> FolderProblem.DUPLICATE
            folders.any { nested(key(it.path), k) } -> FolderProblem.NESTED
            folders.size >= ItemWaysRules.MAX_FOLDERS -> FolderProblem.TOO_MANY
            else -> null
        }
    }

    /** Folders as the vault accepts them in a binding: at most 16, each well formed, none equal to, under or above another. */
    fun valid(folders: List<RunFolder>): Boolean {
        if (folders.size > ItemWaysRules.MAX_FOLDERS || folders.any { problem(it.path) != null }) return false
        val keys = folders.map { key(it.path) }
        return keys.indices.none { i -> keys.indices.any { j -> i < j && (keys[i] == keys[j] || nested(keys[i], keys[j])) } }
    }

    /** One of [a] and [b] lies under the other (strictly), element by element. */
    private fun nested(a: List<String>, b: List<String>): Boolean {
        val (short, long) = if (a.size <= b.size) a to b else b to a
        return short.size < long.size && long.subList(0, short.size) == short
    }

    /**
     * The key entries are compared by (0.93.2): the root (`/` or the drive, folded), then each element as
     * `NFD(fold(upper(NFD(e))))` with its trailing dots and spaces dropped. `/` and `\` both separate.
     */
    fun key(path: String): List<String> {
        val parts = path.split('/', BACKSLASH)
        return parts.mapIndexed { i, e -> if (i == 0 && e.isEmpty()) "/" else elementKey(e) }
    }

    /** `NFD(fold(upper(NFD(e))))`, trailing dots and spaces dropped; `fold(upper(r))` is Go's own ([FolderFold]). */
    fun elementKey(e: String): String {
        val d = Normalizer.normalize(e, Normalizer.Form.NFD)
        val b = StringBuilder(d.length)
        var i = 0
        while (i < d.length) {
            val cp = d.codePointAt(i)
            b.appendCodePoint(FolderFold.key(cp))
            i += Character.charCount(cp)
        }
        return Normalizer.normalize(b, Normalizer.Form.NFD).trimEnd('.', ' ')
    }
}
