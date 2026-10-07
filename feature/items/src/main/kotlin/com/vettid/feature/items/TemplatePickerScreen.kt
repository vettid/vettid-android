package com.vettid.feature.items

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.vettid.core.data.items.Sensitivity
import com.vettid.core.ui.components.DetailScaffold
import com.vettid.core.ui.components.LargeTitle
import com.vettid.core.ui.components.SettingsSectionHeader
import com.vettid.core.ui.components.VettIdListRow

/**
 * "Add to vault" (VAULT-ITEMS §9: one Add screen, template or blank): a blank item, then the recommended templates
 * by category, each with the sensitivity it suggests. [onPick] gets the template id, or null for a blank item.
 */
@Composable
fun TemplatePickerScreen(onBack: () -> Unit, onPick: (String?) -> Unit, modifier: Modifier = Modifier) {
    val groups = ItemTemplates.all.groupBy { it.category }
    DetailScaffold(onBackClick = onBack, modifier = modifier.testTag("items_templates")) {
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
            item(key = "title") { LargeTitle(stringResource(R.string.items_add_title)) }
            item(key = "blank") {
                VettIdListRow(
                    title = stringResource(R.string.items_template_blank),
                    supporting = stringResource(R.string.items_template_blank_note),
                    tileIcon = Icons.Outlined.Add,
                    onClick = { onPick(null) },
                    modifier = Modifier.testTag("items_template_blank"),
                )
            }
            ItemTemplates.categories.filter { groups.containsKey(it.id) }.forEach { c ->
                item(key = "h_${c.id}") { SettingsSectionHeader(stringResource(c.label)) }
                items(groups.getValue(c.id), key = { it.id }) { t ->
                    VettIdListRow(
                        title = stringResource(t.name),
                        supporting = templateNote(t.sensitivity),
                        tileIcon = c.icon,
                        onClick = { onPick(t.id) },
                        modifier = Modifier.testTag("items_template_${t.id}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun templateNote(s: Sensitivity): String = when (s) {
    Sensitivity.DATA -> stringResource(R.string.items_template_note_data)
    Sensitivity.SECRET -> stringResource(R.string.items_template_note_secret)
    Sensitivity.CRITICAL -> stringResource(R.string.items_template_note_critical)
}
