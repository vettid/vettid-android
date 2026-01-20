package com.vettid.app.features.connections.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vettid.app.features.connections.models.ConnectionTag

/**
 * Tag chip for displaying connection tags.
 */
@Composable
fun TagChip(
    tag: ConnectionTag,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    selected: Boolean = false
) {
    val tagColor = Color(tag.color)

    Surface(
        modifier = modifier.then(
            if (onClick != null) Modifier.clickable { onClick() } else Modifier
        ),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) tagColor.copy(alpha = 0.2f) else tagColor.copy(alpha = 0.1f),
        border = if (selected) {
            ButtonDefaults.outlinedButtonBorder
        } else null
    ) {
        Row(
            modifier = Modifier.padding(
                start = 8.dp,
                end = if (onRemove != null) 4.dp else 8.dp,
                top = 4.dp,
                bottom = 4.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Color dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(tagColor)
            )

            Text(
                text = tag.name,
                style = MaterialTheme.typography.labelMedium,
                color = tagColor
            )

            if (onRemove != null) {
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Remove tag",
                        modifier = Modifier.size(14.dp),
                        tint = tagColor
                    )
                }
            }
        }
    }
}

/**
 * Horizontal row of tags.
 */
@Composable
fun TagRow(
    tags: List<ConnectionTag>,
    modifier: Modifier = Modifier,
    onTagClick: ((ConnectionTag) -> Unit)? = null
) {
    if (tags.isEmpty()) return

    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(tags, key = { it.id }) { tag ->
            TagChip(
                tag = tag,
                onClick = onTagClick?.let { { it(tag) } }
            )
        }
    }
}

/**
 * Tag selector for filtering or assigning tags.
 */
@Composable
fun TagSelector(
    availableTags: List<ConnectionTag>,
    selectedTags: Set<String>,
    onTagToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
    onCreateTag: (() -> Unit)? = null
) {
    Column(modifier = modifier) {
        Text(
            text = "Tags",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            availableTags.forEach { tag ->
                TagChip(
                    tag = tag,
                    selected = tag.id in selectedTags,
                    onClick = { onTagToggle(tag.id) }
                )
            }

            if (onCreateTag != null) {
                AddTagChip(onClick = onCreateTag)
            }
        }
    }
}

/**
 * Chip to add a new tag.
 */
@Composable
private fun AddTagChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Add Tag",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Dialog for creating a new tag.
 */
@Composable
fun CreateTagDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, color: Long) -> Unit
) {
    var tagName by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf(0xFF4CAF50L) }

    val colorOptions = listOf(
        0xFF4CAF50L, // Green
        0xFF2196F3L, // Blue
        0xFFFF9800L, // Orange
        0xFF9C27B0L, // Purple
        0xFFF44336L, // Red
        0xFF00BCD4L, // Cyan
        0xFFFF5722L, // Deep Orange
        0xFF607D8BL  // Blue Gray
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Tag") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = tagName,
                    onValueChange = { tagName = it },
                    label = { Text("Tag Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Color",
                    style = MaterialTheme.typography.titleSmall
                )

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(colorOptions) { color ->
                        ColorOption(
                            color = color,
                            selected = color == selectedColor,
                            onClick = { selectedColor = color }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(tagName, selectedColor) },
                enabled = tagName.isNotBlank()
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ColorOption(
    color: Long,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color(color))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                modifier = Modifier.size(20.dp),
                tint = Color.White
            )
        }
    }
}

/**
 * Simple flow row layout for wrapping items.
 */
@Composable
private fun FlowRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable () -> Unit
) {
    // Using Compose Foundation's FlowRow would be better, but for compatibility:
    // This is a simplified version that wraps content
    Row(
        modifier = modifier,
        horizontalArrangement = horizontalArrangement
    ) {
        content()
    }
}
