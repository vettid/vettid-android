package com.vettid.app.notify

import com.vettid.core.notify.Channels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every channel the app's code posts to (or creates) is one of [Channels] (owner decision 2026-10-23: the channels
 * whose sound changed have `_v2` ids, and the old ids are deleted at start, so a notification posted to an old id
 * would never show). Reads the production sources of every module.
 */
class ChannelIdsTest {
    private val root = File("..").canonicalFile

    private val sources: List<Pair<String, String>> by lazy {
        root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".gradle" && !it.name.startsWith(".") }
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).path to it }
            .filter { (path, _) -> isProduction(path) }
            .map { (path, f) -> path to f.readText() }
            .toList()
    }

    private fun isProduction(path: String): Boolean {
        val set = Regex("""(?:^|/)src/([^/]+)/""").find(path)?.groupValues?.get(1) ?: return false
        return !set.contains("test", ignoreCase = true)
    }

    private fun idOf(name: String): String? =
        runCatching { Channels::class.java.getField(name).get(null) as? String }.getOrNull()

    @Test
    fun theSourcesAreFound() {
        val paths = sources.map { it.first }
        assertTrue(paths.any { it.endsWith("core/notify/Channels.kt") })
        assertTrue(paths.any { it.endsWith("app/notify/ReleaseUpdateNotifier.kt") })
        assertTrue(paths.any { it.endsWith("core/calls/CallService.kt") })
    }

    @Test
    fun everyChannelNamedInTheCodeExists() {
        val named = sources.flatMap { (path, text) ->
            Regex("""\bChannels\.([A-Z][A-Z0-9_]*)\b""").findAll(text).map { path to it.groupValues[1] }.toList()
        }
        assertTrue("the posting code names its channels", named.size > 10)
        named.filter { it.second != "SOUNDS" }.forEach { (path, name) ->
            val id = idOf(name)
            assertTrue("$path: Channels.$name is not a channel (id $id)", id != null && id in Channels.allIds)
        }
    }

    @Test
    fun everyBuilderAndChannelTakesAKnownId() {
        val builder = """\b(?:NotificationCompat\.Builder|Notification\.Builder)\(\s*[\w.]+\s*,\s*([^,()]+?)\s*[,)]"""
        val calls = Regex("""$builder|\bNotificationChannel\(\s*([^,()]+?)\s*,""")
        // The expressions that carry a channel id: a Channels constant, the release notice's, a LocalNotification's
        // (made by FeedNotifier and NotificationMapping from Channels constants), and Channels.ensure's own loop.
        val allowed = setOf("CHANNEL_ID", "n.channel", "id")
        val found = sources.flatMap { (path, text) ->
            calls.findAll(text).map { m -> path to (m.groupValues[1].ifEmpty { m.groupValues[2] }) }.toList()
        }
        assertTrue("the notification builders are found", found.size >= 6)
        found.forEach { (path, arg) ->
            val ok = arg in allowed || arg.removePrefix("Channels.").let { arg.startsWith("Channels.") && idOf(it) in Channels.allIds }
            assertTrue("$path: notification channel `$arg`", ok)
        }
        assertEquals(Channels.VAULT_UPDATES, ReleaseUpdateNotifier.CHANNEL_ID)
    }

    @Test
    fun noOldIdIsWrittenInTheCode() {
        Channels.RETIRED.forEach { old ->
            sources.filter { (path, _) -> path.contains("/notify/") || path.contains("/calls/") }.forEach { (path, text) ->
                val literal = "\"$old\""
                val inChannels = path.endsWith("core/notify/Channels.kt")
                assertTrue("$path writes the old channel id $literal", inChannels || !text.contains(literal))
            }
        }
    }
}
