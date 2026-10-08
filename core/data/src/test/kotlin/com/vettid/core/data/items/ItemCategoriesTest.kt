package com.vettid.core.data.items

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Member-defined categories (§10.7 `[a-z][a-z0-9_]{0,31}`): the identifier a typed name gives, and how one is shown. */
class ItemCategoriesTest {
    private fun id(name: String) = ItemCategories.derive(name).id

    private fun problem(name: String) = ItemCategories.derive(name).problem

    @Test
    fun aNameGivesALowerCaseIdentifier() {
        assertEquals("loyalty_cards", id("Loyalty cards"))
        assertEquals("gym_membership", id("  Gym - membership  "))
        assertEquals("home_lab", id("home-lab"))
        assertEquals("pets_vets", id("Pets & vets"))
        assertEquals("tickets", id("Tickets!!!"))
        assertEquals("a_b", id("a__b"))
        assertEquals("cars2", id("Cars2"))
    }

    @Test
    fun accentsAreRemovedAndOtherScriptsDropped() {
        assertEquals("cafe_cards", id("Café cards"))
        assertEquals("strasse", id("Straße"))
        assertEquals("notes", id("Notes 日本"))
        assertNull(id("カード"))
        assertEquals(ItemCategories.Problem.EMPTY, problem("カード"))
    }

    @Test
    fun emptyOrPunctuationOnlyGivesNone() {
        assertEquals(ItemCategories.Problem.EMPTY, problem(""))
        assertEquals(ItemCategories.Problem.EMPTY, problem("   "))
        assertEquals(ItemCategories.Problem.EMPTY, problem("--- !!"))
        assertEquals(ItemCategories.Problem.EMPTY, problem("___"))
    }

    @Test
    fun anIdentifierStartsWithALetter() {
        assertEquals(ItemCategories.Problem.STARTS_WITH_DIGIT, problem("2fa codes"))
        assertEquals(ItemCategories.Problem.STARTS_WITH_DIGIT, problem("_1 thing"))
        assertEquals("fa_codes", id("-fa codes"))
    }

    @Test
    fun aLongNameIsCutTo32Characters() {
        val d = id("Very long category name that goes on and on")!!
        assertEquals(32, d.length)
        assertEquals("very_long_category_name_that_goe", d)
        // A cut that ends on a separator drops it.
        assertEquals("a".repeat(31), id("a".repeat(31) + " b"))
        assertTrue(ItemChecks.isValidCategory(id("x".repeat(100))!!))
    }

    @Test
    fun aRecommendedNameIsTheRecommendedCategory() {
        assertTrue(ItemCategories.derive("Payment card").recommended)
        assertEquals("payment_card", id("Payment card"))
        assertFalse(ItemCategories.derive("Loyalty cards").recommended)
    }

    @Test
    fun aCustomCategoryIsHumanized() {
        assertEquals("Loyalty cards", ItemCategories.humanize("loyalty_cards"))
        assertEquals("Cars2", ItemCategories.humanize("cars2"))
        assertEquals("X", ItemCategories.humanize("x"))
    }

    @Test
    fun customsAreTheMembersOwnDistinctAndSorted() {
        assertEquals(
            listOf("gym", "loyalty_cards"),
            ItemCategories.customs(listOf("login", "loyalty_cards", "gym", "loyalty_cards", "other", "Bad-Id")),
        )
    }
}
