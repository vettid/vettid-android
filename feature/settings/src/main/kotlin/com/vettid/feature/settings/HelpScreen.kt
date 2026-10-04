package com.vettid.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Security
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.vettid.core.ui.components.DetailScaffold
import com.vettid.core.ui.components.LargeTitle
import com.vettid.core.ui.components.SettingsDivider
import com.vettid.core.ui.components.SettingsGroup
import com.vettid.core.ui.components.SettingsRow
import com.vettid.core.ui.components.SettingsSectionHeader
import com.vettid.core.ui.theme.VettIdTheme
import kotlinx.serialization.Serializable

@Serializable
data object HelpRoute

fun NavGraphBuilder.helpDestination(onBack: () -> Unit) {
    composable<HelpRoute> { HelpScreen(onBack = onBack) }
}

/** Help: links to the website's explanations and contact page. */
@Composable
fun HelpScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    val about = stringResource(R.string.help_url_about)
    val security = stringResource(R.string.help_url_security)
    val contact = stringResource(R.string.help_url_contact)
    DetailScaffold(onBackClick = onBack, modifier = modifier, background = VettIdTheme.colors.groupedBackground) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            LargeTitle(stringResource(R.string.help_title))
            SettingsSectionHeader(stringResource(R.string.help_section_learn))
            SettingsGroup {
                SettingsRow(
                    label = stringResource(R.string.help_about),
                    onClick = { uri.openUri(about) },
                    icon = Icons.Outlined.Info,
                    showChevron = false,
                )
                SettingsDivider()
                SettingsRow(
                    label = stringResource(R.string.help_security),
                    onClick = { uri.openUri(security) },
                    icon = Icons.Outlined.Security,
                    showChevron = false,
                )
            }
            SettingsSectionHeader(stringResource(R.string.help_section_support))
            SettingsGroup {
                SettingsRow(
                    label = stringResource(R.string.help_contact),
                    onClick = { uri.openUri(contact) },
                    icon = Icons.Outlined.MailOutline,
                    showChevron = false,
                )
            }
        }
    }
}

