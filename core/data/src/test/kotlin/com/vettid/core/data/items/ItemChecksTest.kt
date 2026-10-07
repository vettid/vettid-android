package com.vettid.core.data.items

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The checks of VAULT-MESSAGING §10.7 and §10.8 before anything is sent, and the size the vault counts. */
class ItemChecksTest {
    private fun draft(vararg fields: DraftField, s: Sensitivity = Sensitivity.DATA, tags: List<String> = emptyList()) =
        ItemDraft(name = "Passport", category = "identity_document", sensitivity = s, tags = tags, fields = fields.toList())

    private fun text(kind: String, v: String) = DraftField(label = "L", kind = kind, text = v)

    @Test
    fun aTemplateLikeDraftPasses() {
        val c = ItemChecks.check(draft(text("text", "X123"), text("date", "2031-04-30"), text("date", "2031-04")))
        assertTrue(c.ok)
        assertEquals(emptyList<String>(), c.tags)
    }

    @Test
    fun theNameIsRequiredAndBounded() {
        assertTrue(DraftProblem.NAME_EMPTY in ItemChecks.check(ItemDraft(name = "  ")).problems)
        assertTrue(DraftProblem.NAME_TOO_LONG in ItemChecks.check(ItemDraft(name = "é".repeat(65))).problems)
        assertTrue(DraftProblem.BAD_CHARACTER in ItemChecks.check(ItemDraft(name = "a\u0007b")).problems)
        assertTrue(ItemChecks.check(ItemDraft(name = "x".repeat(128))).ok)
    }

    @Test
    fun valuesFollowTheirKind() {
        fun bad(kind: String, v: String) = DraftProblem.VALUE_INVALID in ItemChecks.check(draft(text(kind, v))).fieldProblems[0].orEmpty()
        assertTrue(bad("number", "1,5"))
        assertFalse(bad("number", "-3.25"))
        assertTrue(bad("date", "2031-02-30"))
        assertTrue(bad("date", "30.04.2031"))
        assertTrue(bad("email", "no-at-sign"))
        assertFalse(bad("email", "a@b"))
        assertTrue(bad("phone", "call me"))
        assertFalse(bad("phone", "+49 (30) 123-45.6"))
        assertTrue(bad("url", "example.com"))
        assertFalse(bad("url", "https://example.com/x"))
        assertTrue(bad("otp", "short"))
        assertFalse(bad("otp", "JBSWY3DPEHPK3PXP"))
        // Every kind accepts "" (not filled in).
        listOf("number", "date", "email", "phone", "url", "otp").forEach { assertFalse(it, bad(it, "")) }
    }

    @Test
    fun lineBreaksOnlyWhereTheKindAllowsThem() {
        assertTrue(DraftProblem.BAD_CHARACTER in ItemChecks.check(draft(text("text", "a\nb"))).fieldProblems[0].orEmpty())
        assertTrue(ItemChecks.check(draft(text("multiline", "a\nb\tc"))).ok)
        assertTrue(ItemChecks.check(draft(text("password", "a\nb"))).ok)
    }

    @Test
    fun anAddressNeedsAnUpperCaseCountryCode() {
        val ok = DraftField(label = "Address", kind = "address", address = AddressValue(city = "Berlin", country = "DE"))
        assertTrue(ItemChecks.check(draft(ok)).ok)
        val lower = ok.copy(address = ok.address.copy(country = "de"))
        assertTrue(DraftProblem.VALUE_INVALID in ItemChecks.check(draft(lower)).fieldProblems[0].orEmpty())
        val long = ok.copy(address = ok.address.copy(street = "x".repeat(257)))
        assertTrue(DraftProblem.VALUE_TOO_LONG in ItemChecks.check(draft(long)).fieldProblems[0].orEmpty())
    }

    @Test
    fun labelsAreRequiredAndAtMost64Bytes() {
        val c = ItemChecks.check(draft(DraftField(label = "", kind = "text"), DraftField(label = "x".repeat(65), kind = "text")))
        assertEquals(setOf(DraftProblem.LABEL_EMPTY), c.fieldProblems[0])
        assertEquals(setOf(DraftProblem.LABEL_TOO_LONG), c.fieldProblems[1])
    }

    @Test
    fun atMost64Fields() {
        val fields = (1..65).map { DraftField(label = "F$it", kind = "text") }.toTypedArray()
        assertTrue(DraftProblem.TOO_MANY_FIELDS in ItemChecks.check(draft(*fields)).problems)
    }

    @Test
    fun tagsAreNormalisedAsTheVaultDoes() {
        assertEquals("travel plans", ItemChecks.normalizeTag("  Travel   Plans "))
        assertNull(ItemChecks.normalizeTag("-dash"))
        assertNull(ItemChecks.normalizeTag("@other"))
        assertEquals("@profile", ItemChecks.normalizeTag("@profile"))
        assertNull(ItemChecks.normalizeTag("@profile", reserved = false))
        assertEquals(listOf("a", "b"), ItemChecks.normalizeTags(listOf("B", "a", "A", " ")))
        val c = ItemChecks.check(draft(tags = listOf("Medical", "medical", "Travel")))
        assertEquals(listOf("medical", "travel"), c.tags)
        assertTrue(DraftProblem.TAG_INVALID in ItemChecks.check(draft(tags = listOf("ok", "!no"))).problems)
        assertTrue(DraftProblem.TOO_MANY_TAGS in ItemChecks.check(draft(tags = (1..17).map { "t$it" })).problems)
    }

    @Test
    fun theProfileTagStaysOnDataItems() {
        assertTrue(ItemChecks.check(draft(tags = listOf("@profile"))).ok)
        assertTrue(DraftProblem.PROFILE_NOT_DATA in ItemChecks.check(draft(tags = listOf("@profile"), s = Sensitivity.SECRET)).problems)
    }

    @Test
    fun theSizeIsTheVaultsEncodingWithEveryValue() {
        val d = draft(text("text", "X123"))
        // {"item_id":…,"version":…,"name":"Passport",…}: the reference's Item.JSON(true) with the longest ids.
        val expected = (
            """{"item_id":"01ARZ3NDEKTSV4RRFFQ69G5FAV","version":9007199254740991,"name":"Passport","category":"identity_document",""" +
                """"sensitivity":"data","tags":[],"fields":[{"field_id":"f9999999","label":"L","kind":"text","value":"X123"}],""" +
                """"created_at":"2026-10-07T12:34:56.789123456Z","updated_at":"2026-10-07T12:34:56.789123456Z"}"""
            ).length
        assertEquals(expected, ItemChecks.check(d).size)
    }

    @Test
    fun aCriticalItemIsBoundedBy12KiB() {
        val big = text("multiline", "x".repeat(13_000))
        assertTrue(DraftProblem.TOO_LARGE in ItemChecks.check(draft(big, s = Sensitivity.CRITICAL)).problems)
        assertFalse(DraftProblem.TOO_LARGE in ItemChecks.check(draft(big, s = Sensitivity.SECRET)).problems)
        assertEquals(ItemChecks.MAX_CRITICAL_BYTES, ItemChecks.check(draft(s = Sensitivity.CRITICAL)).maxSize)
    }

    @Test
    fun anItemIsBoundedBy64KiB() {
        val fields = (1..5).map { text("multiline", "y".repeat(14_000)) }.toTypedArray()
        assertTrue(DraftProblem.TOO_LARGE in ItemChecks.check(draft(*fields)).problems)
    }

    @Test
    fun addressValuesEncodeTheirNonEmptyMembersInOrder() {
        val v = FieldValue.Address(AddressValue(country = "DE", city = "Berlin", street = "A 1"))
        assertEquals("""{"street":"A 1","city":"Berlin","country":"DE"}""", ItemChecks.valueJson(v))
    }

    @Test
    fun filtersMatchNameTagCategoryAndSensitivity() {
        val a = ItemSummary("1", 1, "Passport", "identity_document", Sensitivity.DATA, tags = listOf("travel"))
        val b = ItemSummary("2", 1, "bank login", "login", Sensitivity.SECRET, tags = listOf("money"))
        val c = ItemSummary("3", 1, "Alpha phrase", "crypto_wallet", Sensitivity.CRITICAL)
        val all = listOf(a, b, c)
        assertEquals(listOf(c, b, a), ItemFilter().apply(all))
        assertEquals(listOf(a), ItemFilter(query = "PASS").apply(all))
        assertEquals(listOf(b), ItemFilter(query = "mon").apply(all))
        assertEquals(listOf(a), ItemFilter(tag = "travel").apply(all))
        assertEquals(listOf(b), ItemFilter(category = "login").apply(all))
        assertEquals(listOf(c), ItemFilter(sensitivity = Sensitivity.CRITICAL).apply(all))
        assertTrue(ItemFilter(tag = "money", sensitivity = Sensitivity.DATA).apply(all).isEmpty())
    }
}
