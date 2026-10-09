package com.vettid.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vettid.core.ui.R
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.VettIdShape
import com.vettid.core.ui.theme.tagColor

/**
 * An item tag as a small pill in the tag's own colour ([com.vettid.core.ui.theme.TagColors]: the same tag has the
 * same colour everywhere; `@profile` is gold). [label] is what is shown ("Shared profile" for `@profile`), [tag] the
 * tag itself, which picks the colour.
 */
@Composable
fun TagChip(label: String, modifier: Modifier = Modifier, tag: String = label) {
    val c = tagColor(tag)
    Box(
        modifier = modifier
            .clip(VettIdShape.pill)
            .background(c.container)
            .padding(horizontal = Spacing.s + Spacing.xxs, vertical = Spacing.xxs)
            .testTag("tag_chip_$tag"),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = c.onContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A row's tags on one line: the first [max] as [TagChip]s, then "+N" for the rest. */
@Composable
fun TagChipLine(tags: List<String>, label: @Composable (String) -> String, modifier: Modifier = Modifier, max: Int = 3) {
    if (tags.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        tags.take(max).forEach { TagChip(label(it), tag = it) }
        val more = tags.size - max
        if (more > 0) {
            val cd = pluralStringResource(R.plurals.core_ui_cd_tags_more, more, more)
            Text(
                stringResource(R.string.core_ui_tags_more, more),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = cd },
            )
        }
    }
}

/** A small dot in [tag]'s colour (a tag's choice in a menu). */
@Composable
fun TagDot(tag: String, modifier: Modifier = Modifier) {
    Box(modifier.size(12.dp).clip(VettIdShape.pill).background(tagColor(tag).container))
}

/**
 * A removable tag (the item editor): an input chip in the tag's colour with a close icon. [removeDescription] is the
 * close icon's content description ("Remove tag travel").
 */
@Composable
fun RemovableTagChip(label: String, tag: String, removeDescription: String, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val c = tagColor(tag)
    InputChip(
        selected = false,
        onClick = onRemove,
        label = { Text(label) },
        trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = removeDescription) },
        colors = InputChipDefaults.inputChipColors(
            containerColor = c.container,
            labelColor = c.onContainer,
            trailingIconColor = c.onContainer,
        ),
        border = null,
        modifier = modifier,
    )
}

/**
 * "✕ Clear" at the end of a row of filter chips, shown by the caller exactly while a filter is on: one tap clears
 * every filter (owner request 2026-10-09: one clear action, in the filter row, not a second one under the results).
 */
@Composable
fun ClearFiltersChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cd = stringResource(R.string.core_ui_cd_clear_filters)
    InputChip(
        selected = false,
        onClick = onClick,
        label = { Text(stringResource(R.string.core_ui_clear)) },
        leadingIcon = { Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(18.dp)) },
        modifier = modifier.semantics { contentDescription = cd }.testTag("clear_filters"),
    )
}
