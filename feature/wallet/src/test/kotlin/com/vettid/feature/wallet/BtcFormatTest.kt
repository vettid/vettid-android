// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.wallet

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** The amounts, the BIP21 URI and the groups of four (ANDROID-PLAN §4 screens table: "Wallet detail", "Receive"). */
class BtcFormatTest {
    @Test
    fun bitcoinHasUpToEightDecimalsWithoutTrailingZeros() {
        assertEquals("0.00123", BtcFormat.btc(123_000))
        assertEquals("0.00000001", BtcFormat.btc(1))
        assertEquals("1", BtcFormat.btc(100_000_000))
        assertEquals("1.5", BtcFormat.btc(150_000_000))
        assertEquals("0", BtcFormat.btc(0))
        assertEquals("21000000", BtcFormat.btc(BtcFormat.MAX_SATS))
        assertEquals("-0.001", BtcFormat.btc(-100_000))
        assertEquals("12345.6789", BtcFormat.btc(1_234_567_890_000))
    }

    @Test
    fun satoshisUseTheLocalesGrouping() {
        assertEquals("123,000", BtcFormat.sats(123_000, Locale.US))
        assertEquals("123.000", BtcFormat.sats(123_000, Locale.GERMANY))
        assertEquals("0", BtcFormat.sats(0, Locale.US))
        assertEquals("-1,500", BtcFormat.sats(-1_500, Locale.US))
    }

    @Test
    fun anAmountIsPositiveWithAtMostEightDecimals() {
        assertEquals(BtcFormat.Amount.None, BtcFormat.parse(""))
        assertEquals(BtcFormat.Amount.None, BtcFormat.parse("  "))
        assertEquals(BtcFormat.Amount.Valid(123_000), BtcFormat.parse("0.00123"))
        assertEquals(BtcFormat.Amount.Valid(123_000), BtcFormat.parse("0,00123"))
        assertEquals(BtcFormat.Amount.Valid(50_000_000), BtcFormat.parse(".5"))
        assertEquals(BtcFormat.Amount.Valid(200_000_000), BtcFormat.parse("2."))
        assertEquals(BtcFormat.Amount.Valid(1), BtcFormat.parse("0.00000001"))
        assertEquals(BtcFormat.Amount.Invalid, BtcFormat.parse("0.000000001"))
        assertEquals(BtcFormat.Amount.Invalid, BtcFormat.parse("0"))
        assertEquals(BtcFormat.Amount.Invalid, BtcFormat.parse("0.0"))
        assertEquals(BtcFormat.Amount.Invalid, BtcFormat.parse("-1"))
        assertEquals(BtcFormat.Amount.Invalid, BtcFormat.parse("1e3"))
        assertEquals(BtcFormat.Amount.Invalid, BtcFormat.parse("1.2.3"))
        assertEquals(BtcFormat.Amount.Invalid, BtcFormat.parse("."))
        assertEquals(BtcFormat.Amount.Valid(BtcFormat.MAX_SATS), BtcFormat.parse("21000000"))
        assertEquals(BtcFormat.Amount.Invalid, BtcFormat.parse("21000001"))
        assertEquals(BtcFormat.Amount.Invalid, BtcFormat.parse("99999999999999999999"))
    }

    @Test
    fun theFieldTakesDigitsAndOneSeparator() {
        assertEquals("0.123", BtcFormat.filter("0.1a2-3"))
        assertEquals("1.23", BtcFormat.filter("1.2.3"))
        assertEquals("", BtcFormat.filter("abc"))
    }

    @Test
    fun theUriIsUpperCaseWithALowerCaseAmountKey() {
        val a = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"
        assertEquals("BITCOIN:TB1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KXPJZSX", Bip21.uri(a))
        assertEquals("BITCOIN:TB1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KXPJZSX?amount=0.00123", Bip21.uri(a, 123_000))
        assertEquals("BITCOIN:TB1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KXPJZSX?amount=1", Bip21.uri(a, 100_000_000))
    }

    @Test
    fun onlyBech32AddressesAreUpperCased() {
        assertEquals("BITCOIN:BCRT1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KYGT080", Bip21.uri("bcrt1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080"))
        assertEquals("BITCOIN:BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4", Bip21.uri("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"))
        assertEquals("BITCOIN:TB1P5CYXNUXMEUWUVKWFEM96LXX9WEX2A5QE0L8E37J0SY6V6PXD0P6SXT3UKD?amount=1", Bip21.uri("tb1p5cyxnuxmeuwuvkwfem96lxx9wex2a5qe0l8e37j0sy6v6pxd0p6sxt3ukd", 100_000_000))
        // Base58 is case-sensitive: kept as it is.
        assertEquals("BITCOIN:1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2", Bip21.uri("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2"))
        assertEquals("BITCOIN:mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn?amount=0.5", Bip21.uri("mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn", 50_000_000))
    }

    /** The owner's decision 1a: with an amount, Share sends the URI so the amount shows; without one, the address alone. */
    @Test
    fun shareSendsTheUriWithTheAmountAndThePlainAddressWithout() {
        val a = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"
        assertEquals(a, Bip21.share(a))
        assertEquals("bitcoin:$a?amount=0.00123", Bip21.share(a, 123_000))
        assertEquals("bitcoin:mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn?amount=0.5", Bip21.share("mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn", 50_000_000))
    }

    @Test
    fun theTextIsInGroupsOfFour() {
        assertEquals("tb1q w508 d6qe jxtd g4y5 r3za rvar y0c5 xw7k xpjz sx", Bip21.groups("tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"))
        assertEquals("abcd", Bip21.groups("abcd"))
        assertEquals("abcd e", Bip21.groups("abcde"))
    }
}
