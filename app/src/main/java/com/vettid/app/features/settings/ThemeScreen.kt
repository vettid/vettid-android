package com.vettid.app.features.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vettid.app.ui.theme.*
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.flow.collectLatest

/**
 * Theme settings screen.
 * Per mobile-ui-plan.md Section 3.3.1
 */
@Composable
fun ThemeSettingsContent(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is SettingsEffect.ShowSuccess -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
                is SettingsEffect.ShowError -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
                else -> {}
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = "APPEARANCE",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    AppTheme.values().forEach { theme ->
                        ThemeOption(
                            theme = theme,
                            isSelected = state.theme == theme,
                            onClick = { viewModel.updateTheme(theme) }
                        )
                        if (theme != AppTheme.values().last()) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Preview",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            ThemePreviewCard(theme = state.theme)
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun ThemeOption(
    theme: AppTheme,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(theme.displayName) },
        leadingContent = {
            RadioButton(
                selected = isSelected,
                onClick = onClick
            )
        },
        trailingContent = {
            when (theme) {
                AppTheme.AUTO -> Icon(
                    Icons.Default.BrightnessAuto,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AppTheme.LIGHT -> Icon(
                    Icons.Default.LightMode,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AppTheme.DARK -> Icon(
                    Icons.Default.DarkMode,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

@Composable
private fun ThemePreviewCard(theme: AppTheme) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (theme) {
        AppTheme.AUTO -> systemDark
        AppTheme.LIGHT -> false
        AppTheme.DARK -> true
    }

    val backgroundColor = if (isDark) VettidBlack else VettidWhite
    val surfaceColor = if (isDark) VettidDarkGray else VettidOffWhite
    val primaryColor = if (isDark) VettidGold else VettidBlack
    val textColor = if (isDark) VettidWhite else VettidBlack
    val subtitleColor = if (isDark) VettidLightGray else VettidDarkGray

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Mock header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(primaryColor)
                )
                Text(
                    text = "Preview",
                    style = MaterialTheme.typography.titleMedium,
                    color = textColor
                )
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = textColor
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Mock content card
            Card(
                colors = CardDefaults.cardColors(containerColor = surfaceColor),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Sample Card",
                        style = MaterialTheme.typography.titleSmall,
                        color = textColor
                    )
                    Text(
                        text = "This is how content will look",
                        style = MaterialTheme.typography.bodySmall,
                        color = subtitleColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Mock button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(primaryColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Button",
                    color = if (isDark) VettidBlack else VettidWhite,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}
