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
        // The expressions that carry a channel id: a Channels constant, a LocalNotification's (made by FeedNotifier and
        // NotificationMapping from Channels constants), Channels.ensure's own loop, and a file's own `CHANNEL_ID`
        // constant when that is a Channels constant (the release notice's).
        val allowed = setOf("n.channel", "id")
        val ownConstant = Regex("""\bconst val CHANNEL_ID\s*=\s*Channels\.([A-Z][A-Z0-9_]*)\b""")
        fun known(arg: String) = arg.startsWith("Channels.") && idOf(arg.removePrefix("Channels.")) in Channels.allIds
        val found = sources.flatMap { (path, text) ->
            calls.findAll(text).map { m -> path to (m.groupValues[1].ifEmpty { m.groupValues[2] }) }.toList()
        }
        assertTrue("the notification builders are found", found.size >= 6)
        found.forEach { (path, arg) ->
            val text = sources.first { it.first == path }.second
            val own = ownConstant.find(text)?.groupValues?.get(1)
            val ok = arg in allowed || known(arg) || (arg == "CHANNEL_ID" && own != null && known("Channels.$own"))
            assertTrue("$path: notification channel `$arg`", ok)
        }
        assertEquals(Channels.VAULT_UPDATES, ReleaseUpdateNotifier.CHANNEL_ID)
    }

    /** Owner decision 2026-10-23: Missed calls is a channel of its own, posted to by the feed's `call.missed`. */
    @Test
    fun missedCallsIsAChannel() {
        assertEquals("missed_calls_v2", Channels.MISSED_CALLS)
        assertTrue(Channels.MISSED_CALLS in Channels.ids)
        assertTrue(Channels.MISSED_CALLS in Channels.allIds)
        val mapping = sources.single { it.first.endsWith("core/notify/NotificationMapping.kt") }.second
        assertTrue(mapping.contains("-> Channels.MISSED_CALLS"))
    }

    /**
     * Calls is for ringing alone (its ringtone): only the call service posts there, and each of its notifications
     * on Calls is silent (the ongoing call and its lock-screen version).
     */
    @Test
    fun onlyTheCallServicePostsOnCallsAndSilently() {
        val posting = sources.filter { (path, text) ->
            !path.endsWith("core/notify/Channels.kt") && Regex("""\bChannels\.CALLS\b""").containsMatchIn(text)
        }
        assertEquals(listOf("CallService.kt"), posting.map { File(it.first).name })
        val service = posting.single().second
        val builders = Regex("""Builder\(\s*[^,()]+,\s*Channels\.CALLS\s*\)""").findAll(service).count()
        assertTrue(builders > 0)
        assertEquals("every Calls notification is silent", builders, Regex("""\.setSilent\(true\)""").findAll(service).count())
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
