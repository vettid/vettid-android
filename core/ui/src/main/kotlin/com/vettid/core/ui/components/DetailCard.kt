package com.vettid.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.VettIdShape

/**
 * Outlined content card of a detail screen (Proton message card): same colour as
 * the screen, a hairline border, rounded corners.
 */
@Composable
fun DetailCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = VettIdShape.card,
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.s),
    ) {
        Column(Modifier.padding(Spacing.l), content = content)
    }
}
