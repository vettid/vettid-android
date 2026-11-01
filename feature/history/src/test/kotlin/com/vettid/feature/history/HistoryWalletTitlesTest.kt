package com.vettid.feature.history

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The wallet's History titles (ANDROID-PLAN §4 "Wallet", "History"): by the wallet's name, or the connection's; never an amount.
 */
@RunWith(RobolectricTestRunner::class)
class HistoryWalletTitlesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun title(kind: String, names: EntryNames = EntryNames()) =
        HistoryText.title(context, kind, "01WALLET0000000000000000AA", names, isolate = false)

    @Test
    fun theWalletsEntriesReadAsTheDrafts() {
        val savings = EntryNames(item = "Savings")
        assertEquals("Wallet created: Savings", title("wallet.created", savings))
        assertEquals("Wallet deleted", title("wallet.deleted"))
        assertEquals("Address given to Sam Lee", title("wallet.address_issued", EntryNames(connection = "Sam Lee")))
        assertEquals("Bitcoin payment signed", title("wallet.signed"))
        assertEquals("Payment refused by a spending limit: Savings", title("wallet.cap_refused", savings))
        assertEquals("Spending limit lowered: Savings", title("wallet.caps_lowered", savings))
        assertEquals("Spending limit raised or removed: Savings", title("wallet.caps_raised", savings))
    }

    @Test
    fun aRefusedRequestNamesTheConnection() {
        assertEquals(
            "Payment request from Sam Lee refused by a spending limit",
            title("wallet.cap_refused", EntryNames(item = "Savings", connection = "Sam Lee")),
        )
    }

    @Test
    fun withoutTheNameTheyStillRead() {
        assertEquals("Wallet created", title("wallet.created"))
        assertEquals("Address given to a connection", title("wallet.address_issued"))
        assertEquals("Payment refused by a spending limit", title("wallet.cap_refused"))
    }

    @Test
    fun theWalletsEntriesNameTheirItem() {
        for (k in listOf("wallet.created", "wallet.cap_refused", "wallet.caps_lowered", "wallet.caps_raised")) {
            assertEquals(k, "01WALLET0000000000000000AA", AuditKinds.itemOf(k, "01WALLET0000000000000000AA"))
        }
    }
}
