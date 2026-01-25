package com.vettid.app.features.enrollmentwizard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Horizontal step indicator for the enrollment wizard.
 *
 * Shows circles for each step with:
 * - Completed steps: Checkmark icon with primary color
 * - Current step: Filled circle with primary color
 * - Future steps: Empty circle, grayed out
 */
@Composable
fun WizardStepIndicator(
    currentStep: Int,
    totalSteps: Int = WizardPhase.TOTAL_STEPS,
    stepLabels: List<String> = listOf("Start", "Verify", "PIN", "Password", "Confirm", "Profile", "Done"),
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        // Step circles with connecting lines
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (step in 0 until totalSteps) {
                val isCompleted = step < currentStep
                val isCurrent = step == currentStep
                val isFuture = step > currentStep

                // Step circle
                StepCircle(
                    stepNumber = step + 1,
                    isCompleted = isCompleted,
                    isCurrent = isCurrent,
                    modifier = Modifier.weight(1f)
                )

                // Connecting line (except after last step)
                if (step < totalSteps - 1) {
                    StepConnector(
                        isCompleted = step < currentStep,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Step labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            stepLabels.forEachIndexed { index, label ->
                val isCompleted = index < currentStep
                val isCurrent = index == currentStep

                StepLabel(
                    label = label,
                    isCompleted = isCompleted,
                    isCurrent = isCurrent,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun StepCircle(
    stepNumber: Int,
    isCompleted: Boolean,
    isCurrent: Boolean,
    modifier: Modifier = Modifier
) {
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isCompleted -> MaterialTheme.colorScheme.primary
            isCurrent -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = tween(300),
        label = "step_circle_bg"
    )

    val contentColor by animateColorAsState(
        targetValue = when {
            isCompleted -> MaterialTheme.colorScheme.onPrimary
            isCurrent -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        },
        animationSpec = tween(300),
        label = "step_circle_content"
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(backgroundColor),
            contentAlignment = Alignment.Center
        ) {
            if (isCompleted) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Completed",
                    tint = contentColor,
                    modifier = Modifier.size(16.dp)
                )
            } else {
                Text(
                    text = stepNumber.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = contentColor
                )
            }
        }
    }
}

@Composable
private fun StepConnector(
    isCompleted: Boolean,
    modifier: Modifier = Modifier
) {
    val progress by animateFloatAsState(
        targetValue = if (isCompleted) 1f else 0f,
        animationSpec = tween(300),
        label = "connector_progress"
    )

    Box(
        modifier = modifier
            .height(2.dp)
            .padding(horizontal = 4.dp)
    ) {
        // Background line
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )

        // Completed portion
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

@Composable
private fun StepLabel(
    label: String,
    isCompleted: Boolean,
    isCurrent: Boolean,
    modifier: Modifier = Modifier
) {
    val textColor by animateColorAsState(
        targetValue = when {
            isCompleted -> MaterialTheme.colorScheme.primary
            isCurrent -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        },
        animationSpec = tween(300),
        label = "step_label_color"
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Compact step indicator showing just dots.
 * For use in space-constrained layouts.
 */
@Composable
fun CompactStepIndicator(
    currentStep: Int,
    totalSteps: Int = WizardPhase.TOTAL_STEPS,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (step in 0 until totalSteps) {
            val isCompleted = step < currentStep
            val isCurrent = step == currentStep

            val dotColor by animateColorAsState(
                targetValue = when {
                    isCompleted || isCurrent -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                animationSpec = tween(300),
                label = "dot_color"
            )

            val dotSize by animateFloatAsState(
                targetValue = if (isCurrent) 10f else 6f,
                animationSpec = tween(200),
                label = "dot_size"
            )

            Box(
                modifier = Modifier
                    .size(dotSize.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }
    }
}
