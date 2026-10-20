package com.vettid.core.data.vault

import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * The `ref`s of release R's recovery entries as the vault writes them (VAULT-MESSAGING 0.49.1 §10.9, §11.14.11), read
 * for the event titles (ANDROID-PLAN 0.1.52 §4 "Event titles"). Every reader returns null for a `ref` that does not read,
 * so that the title falls back to its generic form.
 */
object RecoveryRefs {
    /** Recovery attempts per generation (§11.14.4). */
    const val ATTEMPTS = 10

    /** `recovery.attempt`: `<generation>/<n>/<outcome>/counter`, the outcome `success` or `not_success`. */
    data class Attempt(val generation: Long, val n: Int, val success: Boolean)

    @Suppress("ReturnCount")
    fun attempt(ref: String?): Attempt? {
        val p = ref?.split('/')?.takeIf { it.size == ATTEMPT_PARTS && it[ATTEMPT_PARTS - 1] == SOURCE_COUNTER } ?: return null
        val g = p[0].toLongOrNull()?.takeIf { it >= 1 } ?: return null
        val n = p[1].toIntOrNull()?.takeIf { it in 1..ATTEMPTS } ?: return null
        val success = when (p[2]) {
            "success" -> true
            "not_success" -> false
            else -> return null
        }
        return Attempt(g, n, success)
    }

    /** `recovery.attempts_used`: `<generation>/<used>`; the number used. */
    @Suppress("ReturnCount")
    fun attemptsUsed(ref: String?): Int? {
        val p = ref?.split('/')?.takeIf { it.size == 2 } ?: return null
        if ((p[0].toLongOrNull() ?: 0) < 1) return null
        return p[1].toIntOrNull()?.takeIf { it in 1..ATTEMPTS }
    }

    /**
     * `recovery.generation_closed`: `<generation>/<reason>`, with `/unconfirmed` after `delete` when the counter did not
     * confirm the close. [reason] is as written (`pin`, `password`, `reset`, `backup_off`, `exhausted`, `delete`, `stale`,
     * or one a newer vault adds).
     */
    data class Closure(val generation: Long, val reason: String, val unconfirmed: Boolean)

    @Suppress("ReturnCount")
    fun closure(ref: String?): Closure? {
        val p = ref?.split('/') ?: return null
        val unconfirmed = p.size == CLOSURE_PARTS_UNCONFIRMED && p[2] == UNCONFIRMED
        if (p.size != 2 && !unconfirmed) return null
        val g = p[0].toLongOrNull()?.takeIf { it >= 1 } ?: return null
        return p[1].takeIf { it.isNotEmpty() }?.let { Closure(g, it, unconfirmed) }
    }

    /**
     * `recovery.open`: `<recovery_id>/<outcome>`, or (VAULT-MESSAGING 0.50.0) `<recovery_id>/<outcome>/<name>`, the
     * details a host can drop (§11.14.11). [phoneName] is the attempting phone's **self-reported** name (base64url in
     * the `ref`), null when the `ref` has none or it does not decode; it never affects urgency or any decision.
     */
    data class Open(val recoveryId: String, val outcome: String, val phoneName: String? = null)

    @Suppress("ReturnCount")
    fun open(ref: String?): Open? {
        val p = ref?.split('/')?.takeIf { it.size == 2 || it.size == OPEN_PARTS_NAMED } ?: return null
        if (p[0].isEmpty() || p[1].isEmpty()) return null
        return Open(p[0], p[1], p.getOrNull(2)?.let { phoneName(it) })
    }

    /**
     * The name's UTF-8 from base64url (no padding), cleaned for display (ANDROID-PLAN 0.1.52 §4 "Event titles"): without
     * control characters, Unicode format characters (category Cf: direction controls, ALM, zero-width characters,
     * U+FEFF), line and paragraph separators, U+FFFD and the quotation marks “ ” " (a name must not close the quote and
     * fake a sentence after it); trimmed, and at most 64 code points. Null if nothing is left.
     */
    private fun phoneName(b64: String): String? {
        val bytes = try {
            Base64.getUrlDecoder().decode(b64)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val kept = String(bytes, Charsets.UTF_8).codePoints().filter { !hidden(it) }.toArray()
        val trimmed = String(kept, 0, kept.size).trim()
        val capped = trimmed.codePoints().limit(MAX_NAME_CODE_POINTS.toLong()).toArray()
        return String(capped, 0, capped.size).trim().ifEmpty { null }
    }

    private fun hidden(cp: Int): Boolean {
        val type = Character.getType(cp)
        return Character.isISOControl(cp) || cp == REPLACEMENT || cp in QUOTES ||
            type == Character.FORMAT.toInt() || type == Character.LINE_SEPARATOR.toInt() ||
            type == Character.PARAGRAPH_SEPARATOR.toInt()
    }

    private const val REPLACEMENT = 0xFFFD

    /** “ ” and ". */
    private val QUOTES = setOf(0x201C, 0x201D, 0x22)

    /** The open outcomes that counted an attempt; `busy` and `wrong_again` did not, so no attempt shows them. */
    val COUNTED = setOf("wrong", "attempt_lost", "quiet", "wait", "exhausted", "closed")

    /** How far an open's time may be from its attempt's (the enclave's clock against the counter's). */
    val WINDOW: Duration = Duration.ofMinutes(10)

    /**
     * The outcome of each `not_success` attempt among [entries] (ANDROID-PLAN 0.1.52 §4 "Event titles"): taken in order
     * of `at`, the nearest `recovery.open` within [WINDOW] whose outcome counted an attempt, each open used once. The
     * result maps an attempt's `seq` to its outcome; an attempt without a match is absent.
     */
    fun outcomes(entries: List<AuditRecord>): Map<Long, String> = matches(entries).mapValues { it.value.outcome }

    /** As [outcomes], with the whole matched open (its outcome and the phone's self-reported name). */
    fun matches(entries: List<AuditRecord>): Map<Long, Open> {
        val succeeded = succeeded(entries)
        val attempts = entries.filter { e ->
            val a = attempt(e.ref)
            e.kind == KIND_ATTEMPT && e.at != null && a?.success == false && (a.generation to a.n) !in succeeded
        }
            .sortedWith(compareBy<AuditRecord> { it.at }.thenBy { it.seq })
        val opens = entries.mapNotNull { e ->
            val o = open(e.ref)?.takeIf { e.kind == KIND_OPEN && it.outcome in COUNTED }
            if (o != null && e.at != null) Triple(e.seq, e.at, o) else null
        }.sortedWith(compareBy<Triple<Long, Instant, Open>> { it.second }.thenBy { it.first })
        val used = HashSet<Long>()
        val out = HashMap<Long, Open>()
        for (a in attempts) {
            val best = a.at?.let { at ->
                opens.filter { it.first !in used }
                    .map { it to Duration.between(it.second, at).abs() }
                    .filter { (_, d) -> d <= WINDOW }
                    .minByOrNull { (_, d) -> d }
            } ?: continue
            used += best.first.first
            out[a.seq] = best.first.third
        }
        return out
    }

    /**
     * Whether [entry], an attempt first written as `not_success`, was written again as `success` among [entries] (the
     * vault rewrites an attempt the counter later shows succeeded, §11.14.11): History then says "Later successful.".
     */
    fun laterSuccessful(entry: AuditRecord, entries: List<AuditRecord>): Boolean {
        val a = attempt(entry.ref)?.takeIf { entry.kind == KIND_ATTEMPT && !it.success } ?: return false
        return (a.generation to a.n) in succeeded(entries)
    }

    private fun succeeded(entries: List<AuditRecord>): Set<Pair<Long, Int>> =
        entries.filter { it.kind == KIND_ATTEMPT }.mapNotNull { attempt(it.ref) }.filter { it.success }
            .mapTo(HashSet()) { it.generation to it.n }

    const val KIND_ATTEMPT = "recovery.attempt"
    const val KIND_CLOSED = "recovery.generation_closed"

    /** A next generation only already closed (the harmless case; hidden). */
    const val REASON_STALE = "stale"

    /**
     * (VAULT-MESSAGING 0.50.0, owner decision of 2026-10-20) A next generation that already showed used attempts: a host
     * may have served an older copy of the recovery record. Shown, and routed as an urgent recovery alarm.
     */
    const val REASON_ROLLBACK = "rollback"

    /** Whether [kind] with [ref] is a `rollback` closure. */
    fun rollback(kind: String, ref: String?): Boolean = kind == KIND_CLOSED && closure(ref)?.reason == REASON_ROLLBACK
    const val KIND_OPEN = "recovery.open"
    private const val ATTEMPT_PARTS = 4
    private const val OPEN_PARTS_NAMED = 3
    private const val MAX_NAME_CODE_POINTS = 64
    private const val CLOSURE_PARTS_UNCONFIRMED = 3
    private const val SOURCE_COUNTER = "counter"
    private const val UNCONFIRMED = "unconfirmed"
}
