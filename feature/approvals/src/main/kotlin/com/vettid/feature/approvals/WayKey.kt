package com.vettid.feature.approvals

import java.security.MessageDigest

/**
 * The gate's re-arm key (AA8): a SHA-256 of everything a run or a proxy shows, each part length-prefixed, so any change of
 * what is shown (and nothing else) gives another key. Not `hashCode()`, which can collide. Pure.
 */
internal object WayKey {
    fun of(held: HeldShow): String {
        val parts: List<String?> = when (held) {
            is HeldShow.Run -> held.run.let { r ->
                listOf("run", held.item, held.field, r.itemId, r.fieldId, r.program, r.sha256, r.cwd, r.how.wire, r.name,
                    r.previousSha256, r.previousPath, r.replaces) +
                    r.argv.map { "argv:$it" } + r.env.flatMap { (k, v) -> listOf("env:$k", v) } + r.flags.map { "flag:${it.wire}" }.sorted()
            }
            is HeldShow.Proxy -> held.proxy.let { p ->
                listOf("proxy", held.item, held.field, p.itemId, p.fieldId, p.host, p.header, p.prefix, p.seconds.toString(), p.replaces)
            }
            else -> listOf(held.javaClass.name)
        }
        val d = MessageDigest.getInstance("SHA-256")
        parts.forEach { s ->
            val b = (s ?: "\u0000null").toByteArray(Charsets.UTF_8)
            d.update(b.size.toString().toByteArray())
            d.update(':'.code.toByte())
            d.update(b)
        }
        return d.digest().joinToString("") { "%02x".format(it) }
    }
}
