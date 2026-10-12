package com.vettid.feature.items

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.vettid.core.data.items.FieldKinds
import com.vettid.core.data.items.FieldValue
import com.vettid.core.data.items.ItemDetail
import com.vettid.core.data.items.ItemFieldView
import com.vettid.core.data.items.Sensitivity
import com.vettid.core.data.items.Totp
import com.vettid.core.ui.components.CLIP_EXTRA_IS_SENSITIVE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What "Copy" puts on the clipboard (A6 audit S3, S4): for a one-time code the current code, never its seed unless
 * the member chose to see the seed; and Android's sensitive flag by the item's protection.
 */
@RunWith(RobolectricTestRunner::class)
class ItemCopyTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var context: Context

    private val seed = "JBSWY3DPEHPK3PXP"

    private fun item(sensitivity: Sensitivity, vararg fields: Pair<String, String>) = ItemDetail(
        itemId = "01I",
        version = 1,
        name = "Item",
        category = "other",
        sensitivity = sensitivity,
        fields = fields.mapIndexed { i, (kind, v) -> ItemFieldView("f${i + 1}", kind, kind, FieldValue.Text(v)) },
        revealed = true,
    )

    private fun show(item: ItemDetail, shown: Set<String> = emptySet()) = rule.setContent {
        context = LocalContext.current
        ItemDetailScreen(ItemDetailUiState(item.itemId, item, loading = false, shown = shown), ItemDetailActions())
    }

    private fun clip(): ClipData = checkNotNull(context.getSystemService(ClipboardManager::class.java).primaryClip) { "nothing was copied" }

    private fun ClipData.text() = getItemAt(0).text.toString()

    private fun ClipData.sensitive() = description.extras?.getBoolean(CLIP_EXTRA_IS_SENSITIVE) == true

    @Test
    fun copyOnAOneTimeCodeCopiesTheCurrentCodeNotTheSeed() {
        show(item(Sensitivity.SECRET, FieldKinds.OTP to seed))
        // The seed is hidden: there is no button that copies it.
        rule.onNodeWithTag("item_copy_f1").assertDoesNotExist()
        rule.onNodeWithContentDescription("Copy the current code of otp").performScrollTo().assertIsDisplayed()
        val before = System.currentTimeMillis() / 1000
        rule.onNodeWithTag("item_otp_copy").performClick()
        val after = System.currentTimeMillis() / 1000
        val copied = clip()
        assertNotEquals(seed, copied.text())
        assertTrue(copied.text(), Regex("[0-9]{6}").matches(copied.text()))
        val totp = Totp.parse(seed)!!
        assertTrue("the code of the moment", copied.text() in setOf(totp.code(before), totp.code(after)))
        assertTrue("a code is sensitive", copied.sensitive())
    }

    @Test
    fun theSeedIsCopiedOnlyWhereItWasRevealedOnPurpose() {
        show(item(Sensitivity.DATA, FieldKinds.OTP to seed), shown = setOf("f1"))
        rule.onNodeWithContentDescription("Copy the setup key of otp").assertIsDisplayed()
        rule.onNodeWithTag("item_copy_f1").performClick()
        assertEquals(seed, clip().text())
        assertTrue("a seed is sensitive in any item", clip().sensitive())
        // The code's own button still copies the code.
        rule.onNodeWithTag("item_otp_copy").performClick()
        assertNotEquals(seed, clip().text())
    }

    @Test
    fun everyValueOfASecretOrCriticalItemIsCopiedAsSensitive() {
        show(item(Sensitivity.SECRET, FieldKinds.NUMBER to "4111111111111111", FieldKinds.MULTILINE to "abandon ability able"))
        rule.onNodeWithTag("item_copy_f1").performClick()
        assertEquals("4111111111111111", clip().text())
        assertTrue("a card number", clip().sensitive())
        rule.onNodeWithTag("item_copy_f2").performClick()
        assertEquals("abandon ability able", clip().text())
        assertTrue("a recovery phrase", clip().sensitive())
    }

    @Test
    fun anOrdinaryItemsValuesAreNotFlaggedButItsPasswordIs() {
        show(item(Sensitivity.DATA, FieldKinds.TEXT to "Blue", FieldKinds.PASSWORD to "hunter2"))
        rule.onNodeWithTag("item_copy_f1").performClick()
        assertEquals("Blue", clip().text())
        assertFalse(clip().sensitive())
        rule.onNodeWithTag("item_copy_f2").performClick()
        assertEquals("hunter2", clip().text())
        assertTrue(clip().sensitive())
    }

    @Test
    fun theFlagFollowsTheProtectionAndTheKind() {
        assertFalse(ItemClips.sensitive(FieldKinds.TEXT, Sensitivity.DATA))
        assertTrue(ItemClips.sensitive(FieldKinds.PASSWORD, Sensitivity.DATA))
        assertTrue(ItemClips.sensitive(FieldKinds.OTP, Sensitivity.DATA))
        assertTrue(ItemClips.sensitive(FieldKinds.TEXT, Sensitivity.SECRET))
        assertTrue(ItemClips.sensitive(FieldKinds.NUMBER, Sensitivity.CRITICAL))
    }
}
