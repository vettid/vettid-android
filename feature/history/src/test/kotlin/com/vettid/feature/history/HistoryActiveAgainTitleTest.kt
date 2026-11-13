package com.vettid.feature.history

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * (GC #7478; VAULT-MESSAGING 0.88.7) History's `connection.active_again`: "Connection with <name> works again", else
 * "Connection works again" (owner decisions b and a, GC #7655, both owner-approved 2026-11-10), the name from History's
 * connection names, cleaned and bidi-isolated, as the other connection titles.
 */
@RunWith(RobolectricTestRunner::class)
class HistoryActiveAgainTitleTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private fun title(names: EntryNames) = HistoryText.title(context, "connection.active_again", "c1", names)

    @Test
    fun theOwnersWordsWordForWord() {
        assertEquals("Connection with %1\$s works again", context.getString(R.string.history_kind_connection_active_again))
        assertEquals("Connection works again", context.getString(R.string.history_kind_connection_active_again_any))
    }

    @Test
    fun theConnectionsNameCleanedAndIsolatedElseTheTitleWithoutIt() {
        assertEquals("Connection with ⁨Sam Lee⁩ works again", title(EntryNames(connection = "Sam Lee")))
        assertEquals("cleaned", "Connection with ⁨Sam Lee⁩ works again", title(EntryNames(connection = "Sam‮ Lee")))
        assertEquals("not known", "Connection works again", title(EntryNames()))
        assertEquals("nothing once cleaned", "Connection works again", title(EntryNames(connection = "‮​⁩")))
    }
}
