package com.vettid.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.vettid.core.ui.R
import com.vettid.core.ui.text.ShownPiece
import com.vettid.core.ui.text.ShownString
import com.vettid.core.ui.theme.Spacing

/** Left-to-right isolate and override (AA8: the box's text in its logical order, kept from reordering what is around it). */
private const val LRI = '⁦'
private const val LRO = '‭'
private const val PDF = '‬'
private const val PDI = '⁩'

/**
 * A string as AA8 "How a string is shown" draws it: in full in a monospace box, left to right and isolated, escapes in
 * a highlighted box, spaces marked, other than printable ASCII highlighted with the look-alike line under it.
 */
@Composable
fun ShownStringBox(s: String, modifier: Modifier = Modifier) {
    val pieces = remember(s) { ShownString.pieces(s) }
    val colors = MaterialTheme.colorScheme
    val escapedWord = stringResource(R.string.core_ui_string_escaped, "%s")
    val empty = stringResource(R.string.core_ui_string_empty)
    val shown: AnnotatedString = buildAnnotatedString {
        append(LRI)
        append(LRO)
        if (pieces.isEmpty()) {
            withStyle(SpanStyle(background = colors.tertiaryContainer, color = colors.onTertiaryContainer)) { append(empty) }
        }
        pieces.forEach { p ->
            when (p) {
                is ShownPiece.Text -> if (p.plain) {
                    append(p.text)
                } else {
                    withStyle(SpanStyle(background = colors.secondaryContainer, color = colors.onSecondaryContainer)) { append(p.text) }
                }
                is ShownPiece.Escape ->
                    withStyle(SpanStyle(background = colors.tertiaryContainer, color = colors.onTertiaryContainer)) { append(p.label) }
                ShownPiece.Space -> withStyle(SpanStyle(color = colors.outline)) { append('·') }
            }
        }
        append(PDF)
        append(PDI)
    }
    // What a screen reader says: the text, each escape as "escaped character U+202E".
    val spoken = if (pieces.isEmpty()) {
        empty
    } else {
        pieces.joinToString("") {
            when (it) {
                is ShownPiece.Text -> it.text
                is ShownPiece.Escape -> " ${escapedWord.replace("%s", it.label)} "
                ShownPiece.Space -> " "
            }
        }
    }
    Column(modifier) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Surface(
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, colors.outline),
                color = colors.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    shown,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                    softWrap = true,
                    modifier = Modifier.padding(Spacing.s).semantics { contentDescription = spoken }.testTag("shown_string"),
                )
            }
        }
        if (ShownString.lookalike(pieces)) {
            Text(
                stringResource(R.string.core_ui_string_lookalike),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.testTag("shown_string_lookalike"),
            )
        }
    }
}
