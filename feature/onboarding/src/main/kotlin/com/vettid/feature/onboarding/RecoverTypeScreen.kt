package com.vettid.feature.onboarding

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.VerbatimTtsAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.vettid.core.altchan.RecoveryCodes
import com.vettid.core.ui.components.FormScaffold
import com.vettid.core.ui.components.SecureWindow
import com.vettid.core.ui.components.NoKeyboardLearning
import com.vettid.core.ui.components.excludeFromAutofill
import com.vettid.core.ui.theme.Spacing

/**
 * The recovery code typed instead of scanned (VAULT-MESSAGING 0.25.0 §11.11.10; ANDROID-PLAN 0.1.33): one monospace
 * field drawn in groups of four, three rows of four groups, with "n of 12 groups" under it. The keyboard is a text
 * keyboard without autocorrect, suggestions or learning; the field is kept from autofill services, nothing in it can
 * be copied or cut to the clipboard (a paste is accepted), and while it is shown the window is excluded from
 * screenshots and the recent-apps preview ([secure]; off only in the debug screen catalog, which is screenshotted).
 * [extra] holds the answers the scan screen shows too (the claim's and the vault's refusals, failures).
 */
@Composable
internal fun RecoverTypeScreen(
    state: RecoverUiState,
    actions: RecoverActions,
    onBack: (() -> Unit)?,
    secure: Boolean,
    extra: @Composable () -> Unit,
) {
    if (secure) SecureWindow()
    val groups = stringResource(R.string.recover_type_groups, state.typedGroups, RecoveryCodes.TYPED_GROUPS)
    val problem = typedProblemText(state)
    FormScaffold(
        title = stringResource(R.string.recover_type_title),
        body = stringResource(R.string.recover_type_body),
        primaryLabel = stringResource(R.string.onboarding_continue),
        onPrimary = actions::submitTyped,
        primaryEnabled = state.typedComplete,
        busy = state.busy,
        onBack = onBack,
        secondaryLabel = stringResource(R.string.recover_scan_instead),
        onSecondary = actions::scan,
        modifier = Modifier.testTag("recover_type"),
    ) {
        PasteOnly {
            NoKeyboardLearning {
                OutlinedTextField(
                    value = state.typed,
                    onValueChange = actions::setTyped,
                    label = { Text(stringResource(R.string.recover_type_label)) },
                    minLines = TypedCodeTransformation.ROWS,
                    enabled = !state.busy,
                    isError = problem != null,
                    supportingText = { Text(groups, Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("typed_groups")) },
                    textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                    visualTransformation = TypedCodeTransformation,
                    // A password-class text keyboard: no autocorrect, no suggestions and no personalized learning. The
                    // characters stay visible (the transformation above only groups them).
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                        autoCorrectEnabled = false,
                    ),
                    keyboardActions = KeyboardActions(onDone = { actions.submitTyped() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .excludeFromAutofill()
                        .semantics {
                            stateDescription = groups
                            if (problem != null) error(problem)
                        }
                        .testTag("recover_typed"),
                )
            }
        }
        if (problem != null) {
            Spacer(Modifier.height(Spacing.s))
            Text(
                problem,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }.testTag("typed_problem"),
            )
        }
        extra()
    }
}

/** The text for [RecoverUiState.typedProblem]; a refused character names the group to look at. */
@Composable
internal fun typedProblemText(state: RecoverUiState): String? {
    val text = when (state.typedProblem) {
        null -> return null
        TypedProblem.LETTER_U -> stringResource(R.string.recover_type_letter_u)
        TypedProblem.OTHER_CHARACTER -> stringResource(R.string.recover_type_other_character)
        TypedProblem.BAD_CHECK -> stringResource(R.string.recover_type_bad_check, state.accountHost)
        TypedProblem.SHORT_CODE -> stringResource(R.string.recover_type_short_code)
        TypedProblem.INCOMPLETE -> stringResource(R.string.recover_type_incomplete)
    }
    val refusedCharacter = state.typedProblem == TypedProblem.LETTER_U || state.typedProblem == TypedProblem.OTHER_CHARACTER
    return if (refusedCharacter && state.typedGroup > 0) stringResource(R.string.recover_type_in_group, text, state.typedGroup) else text
}

/**
 * Draws the canonical characters in groups of four, four groups to a row (`7ZK3 M9QX 2HTD CDEF`), without changing
 * the field's value. Each group is marked to be spoken character by character, never as a word.
 */
internal object TypedCodeTransformation : VisualTransformation {
    private const val GROUPS_PER_ROW = 4
    const val ROWS = RecoveryCodes.TYPED_GROUPS / GROUPS_PER_ROW
    private const val GROUP = RecoveryCodes.GROUP

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val out = AnnotatedString.Builder(raw.length + raw.length / GROUP)
        raw.chunked(GROUP).forEachIndexed { i, group ->
            if (i > 0) out.append(if (i % GROUPS_PER_ROW == 0) "\n" else " ")
            val start = out.length
            out.append(group)
            out.addTtsAnnotation(VerbatimTtsAnnotation(group), start, out.length)
        }
        val shown = out.toAnnotatedString()
        return TransformedText(shown, Offsets(raw.length, shown.length))
    }

    /** One separator after every complete group that is followed by another character. */
    private class Offsets(private val rawLength: Int, private val shownLength: Int) : OffsetMapping {
        override fun originalToTransformed(offset: Int): Int {
            val o = offset.coerceIn(0, rawLength)
            return if (o == 0) 0 else (o + (o - 1) / GROUP).coerceAtMost(shownLength)
        }

        override fun transformedToOriginal(offset: Int): Int {
            val t = offset.coerceIn(0, shownLength)
            return (t - t / (GROUP + 1)).coerceAtMost(rawLength)
        }
    }
}

/**
 * The recovery code never goes to the clipboard (§11.11.10: "the code does not go to a clipboard"): inside
 * [content] the text menu offers no Copy or Cut, and whatever asks to write to the clipboard writes nothing. Reading
 * (a paste) is passed through.
 */
@Composable
internal fun PasteOnly(content: @Composable () -> Unit) {
    val clipboard = LocalClipboard.current
    val toolbar = LocalTextToolbar.current
    val readOnly = remember(clipboard) { ReadOnlyClipboard(clipboard) }
    val pasteOnly = remember(toolbar) { PasteOnlyToolbar(toolbar) }
    CompositionLocalProvider(LocalClipboard provides readOnly, LocalTextToolbar provides pasteOnly, content = content)
}

private class ReadOnlyClipboard(private val real: Clipboard) : Clipboard {
    override val nativeClipboard get() = real.nativeClipboard

    override suspend fun getClipEntry(): ClipEntry? = real.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) = Unit
}

private class PasteOnlyToolbar(private val real: TextToolbar) : TextToolbar by real {
    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) = real.showMenu(rect, null, onPasteRequested, null, onSelectAllRequested)

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
        onAutofillRequested: (() -> Unit)?,
    ) = real.showMenu(rect, null, onPasteRequested, null, onSelectAllRequested, onAutofillRequested)
}
