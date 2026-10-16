package com.vettid.core.crypto

import com.vettid.core.crypto.altchan.PasswordRules
import com.vettid.core.crypto.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VAULT-MESSAGING 0.33.0 §11.14.7: the app's password rules against vettid-vault's shared verdict file
 * (`testdata/passwords/verdicts.json`), at the vault's floor and at the app's slightly higher one.
 */
class PasswordRulesTest {
    private val doc = VectorDoc.load("verdicts.json")
    private val pin = doc.str("pin")
    private val cases = doc.o.array("cases").map { it as JsonObject }.map { it.string("password") to it.string("verdict") }

    @Test
    fun everyVerdictMatchesAtTheVaultsFloor() {
        assertEquals(16, cases.size)
        cases.forEach { (pw, want) ->
            assertEquals(pw, want.takeIf { it != "ok" }, PasswordRules.check(pw, pin, PasswordRules.VAULT_FLOOR))
        }
    }

    @Test
    fun everyVerdictMatchesAtTheAppsHigherFloor() {
        cases.forEach { (pw, want) -> assertEquals(pw, want.takeIf { it != "ok" }, PasswordRules.check(pw, pin)) }
        assertTrue(PasswordRules.APP_FLOOR > PasswordRules.VAULT_FLOOR)
    }

    @Test
    fun thePinAndCharactersNotBytes() {
        assertEquals(PasswordRules.SAME_AS_PIN, PasswordRules.check("135792468024", "135792468024", PasswordRules.VAULT_FLOOR))
        // Twelve characters of which some take several bytes: long enough by characters.
        assertNull(PasswordRules.check("ünïcödé-Qx7#mñ", null).takeIf { it == PasswordRules.SHORT })
        assertEquals(PasswordRules.SHORT, PasswordRules.check("ééééééééééé", null))
    }
}
