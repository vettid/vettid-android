package com.vettid.core.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.vettid.core.ui.theme.Spacing

/**
 * A row of filter chips that scrolls sideways, ended by "✕ Clear" ([ClearFiltersChip]) while [filtering]: the clear
 * chip stays at the row's end, outside the scroll, so it is in view however many chips there are (owner request
 * 2026-10-09: one clear action, in the filter row).
 */
@Composable
fun FilterChipRow(
    filtering: Boolean,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    chips: @Composable RowScope.() -> Unit,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
                .padding(start = Spacing.gutter, end = if (filtering) Spacing.s else Spacing.gutter),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
            content = chips,
        )
        if (filtering) ClearFiltersChip(onClick = onClear, modifier = Modifier.padding(end = Spacing.gutter))
    }
}
