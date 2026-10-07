package com.vettid.core.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.vettid.core.ui.theme.Spacing

/**
 * Account sheet (Proton): large avatar tile, name and detail, then option groups.
 * Groups are [SettingsGroup]s emitted by [content].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvatarSheet(
    name: String,
    detail: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    optionsHeader: String? = null,
    photo: ImageBitmap? = null,
    content: @Composable () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = com.vettid.core.ui.theme.VettIdTheme.colors.groupedBackground,
        modifier = modifier,
    ) {
        AvatarSheetContent(name = name, detail = detail, optionsHeader = optionsHeader, photo = photo, content = content)
    }
}

/** The sheet's body, usable without the modal (gallery, previews). */
@Composable
fun AvatarSheetContent(
    name: String,
    detail: String,
    optionsHeader: String?,
    photo: ImageBitmap? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = Spacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        InitialTile(name = name, size = 88, style = TileStyle.Self, photo = photo)
        Spacer(Modifier.height(Spacing.l))
        Text(
            text = name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.l))
        Column(Modifier.fillMaxWidth()) {
            if (optionsHeader != null) SettingsSectionHeader(optionsHeader)
            content()
        }
    }
}
