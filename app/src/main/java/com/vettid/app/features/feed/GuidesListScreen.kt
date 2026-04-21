package com.vettid.app.features.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Lists every guide the app ships with as a tappable card. Tapping a
 * card opens the full guide screen. Surfaced from the VettID system
 * connection's "Guides" action — replaces the old feed-cards
 * representation guides used to get in the activity feed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuidesListScreen(
    onBack: () -> Unit,
    onOpenGuide: (guideId: String) -> Unit,
    viewModel: GuidesListViewModel = hiltViewModel(),
) {
    val guideIds = remember { GuideContentProvider.allGuideIds() }
    val readIds by viewModel.readIds.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Guides") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(items = guideIds, key = { it }) { guideId ->
                val content = GuideContentProvider.getContent(guideId) ?: return@items
                GuideRow(
                    title = content.title,
                    icon = content.icon,
                    preview = (content.sections.firstOrNull { it is GuideSection.Paragraph }
                        as? GuideSection.Paragraph)?.text.orEmpty(),
                    isRead = guideId in readIds,
                    onClick = { onOpenGuide(guideId) },
                )
            }
        }
    }
}

@Composable
private fun GuideRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    preview: String,
    isRead: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (isRead) FontWeight.Normal else FontWeight.SemiBold,
                color = if (isRead)
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                else
                    MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (preview.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        if (isRead) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Read",
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape,
                    ),
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
}
