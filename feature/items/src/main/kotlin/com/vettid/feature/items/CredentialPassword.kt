package com.vettid.feature.items

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.messageRes
import com.vettid.core.ui.components.FormScaffold
import com.vettid.core.ui.components.SecretField
import com.vettid.core.ui.theme.Spacing
import kotlinx.coroutines.delay
import java.time.Instant

/** Why the credential password is asked for (every read or change of a critical item is a credential operation, §10.7). */
enum class PasswordPurpose { OPEN, SAVE, DELETE, PROTECTION }

/**
 * The credential-password step of a critical operation. [error] is the last refusal (`bad_password`, or another
 * failure); [retryUntil] the end of a password backoff (`retry_after`, VAULT-MESSAGING 0.17.0), during which the
 * send button stays off. The password lives in ViewModel state only, and is cleared after every answer.
 */
data class PasswordPrompt(
    val purpose: PasswordPurpose,
    val password: String = "",
    val busy: Boolean = false,
    val error: FailureKind? = null,
    val retryUntil: Instant? = null,
) {
    fun canSend(now: Instant): Boolean = password.isNotEmpty() && !busy && (retryUntil == null || !now.isBefore(retryUntil))

    /** After a refusal: the password cleared, the error and any backoff kept. */
    fun refused(e: VaultFailure, now: Instant): PasswordPrompt = copy(
        password = "",
        busy = false,
        error = e.kind,
        retryUntil = if (e.kind == FailureKind.BACKOFF && e.retryAfterSeconds > 0) now.plusSeconds(e.retryAfterSeconds) else null,
    )

    override fun toString(): String = "PasswordPrompt($purpose, busy=$busy, error=$error)"
}

/** The hero glyph above a [FormScaffold] title (centred by the scaffold). */
@Composable
internal fun HeaderGlyph(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Spacer(Modifier.height(Spacing.l))
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.height(40.dp))
}

/**
 * The credential password for one critical operation on [itemName] (ANDROID-PLAN §4: every critical action asks for
 * it). Shows a wrong password, and a running backoff as a countdown.
 */
@Composable
@Suppress("CyclomaticComplexMethod")
fun CredentialPasswordContent(
    prompt: PasswordPrompt,
    itemName: String,
    onPassword: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val until = prompt.retryUntil
    LaunchedEffect(until) {
        while (until != null && now < until.toEpochMilli()) {
            delay(TICK_MS)
            now = System.currentTimeMillis()
        }
    }
    val waiting = until?.let { maxOf(0L, (it.toEpochMilli() - now + 999) / 1000) } ?: 0L
    val title = when (prompt.purpose) {
        PasswordPurpose.OPEN -> R.string.items_password_open_title
        PasswordPurpose.SAVE -> R.string.items_password_save_title
        PasswordPurpose.DELETE -> R.string.items_password_delete_title
        PasswordPurpose.PROTECTION -> R.string.items_password_protection_title
    }
    FormScaffold(
        title = stringResource(title),
        body = stringResource(R.string.items_password_body, itemName),
        primaryLabel = stringResource(
            if (prompt.purpose == PasswordPurpose.DELETE) R.string.items_delete else R.string.items_password_continue,
        ),
        onPrimary = onSubmit,
        primaryEnabled = prompt.canSend(Instant.ofEpochMilli(now)),
        busy = prompt.busy,
        destructive = prompt.purpose == PasswordPurpose.DELETE,
        onBack = onCancel,
        secondaryLabel = stringResource(R.string.items_cancel),
        onSecondary = onCancel,
        modifier = Modifier.testTag("items_password"),
        header = { HeaderGlyph(Icons.Outlined.Lock) },
    ) {
        val error = when {
            waiting > 0 -> stringResource(R.string.items_password_backoff, waiting)
            prompt.error == FailureKind.BAD_PASSWORD -> stringResource(R.string.items_password_wrong)
            prompt.error == FailureKind.BACKOFF -> stringResource(R.string.items_password_backoff_undated)
            prompt.error != null -> stringResource(prompt.error.messageRes())
            else -> null
        }
        SecretField(
            value = prompt.password,
            onValueChange = onPassword,
            label = stringResource(R.string.items_password_label),
            error = error,
            enabled = !prompt.busy,
            imeAction = ImeAction.Done,
            onImeAction = { if (prompt.canSend(Instant.now())) onSubmit() },
            modifier = Modifier.testTag("items_password_field"),
        )
        Spacer(Modifier.height(Spacing.m))
        Text(
            stringResource(R.string.items_password_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val TICK_MS = 1_000L
