package com.vettid.core.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.vettid.core.ui.R
import com.vettid.core.ui.theme.Spacing

/**
 * A top-level screen's search in its top bar (owner request 2026-10-08): hidden by default behind a search icon next
 * to the avatar ([label] is the icon's description, e.g. "Search your vault"); the icon turns the bar into a focused
 * field; back or ✕ clears the query and brings the bar back. While [query] is not empty the field stays shown.
 * Made with [rememberTopBarSearch].
 */
@Stable
class TopBarSearch internal constructor(
    val query: String,
    val onQueryChange: (String) -> Unit,
    val label: String,
    val placeholder: String,
    private val opened: Boolean,
    private val setOpened: (Boolean) -> Unit,
) {
    /** Whether the bar is the search field. */
    val active: Boolean get() = opened || query.isNotEmpty()

    fun open() = setOpened(true)

    /** Clears the query and shows the normal bar again. */
    fun close() {
        onQueryChange("")
        setOpened(false)
    }
}

/** The search of a top-level screen: [query] is the screen's (its ViewModel's); whether it is open is kept here. */
@Composable
fun rememberTopBarSearch(query: String, onQueryChange: (String) -> Unit, label: String, placeholder: String): TopBarSearch {
    var opened by rememberSaveable { mutableStateOf(false) }
    return TopBarSearch(query, onQueryChange, label, placeholder, opened) { opened = it }
}

/** The top bar as a search field: back (closes), the field (focused, keyboard up), ✕ (closes). Back closes it first. */
@Composable
fun SearchTopBar(search: TopBarSearch, modifier: Modifier = Modifier, autoFocus: Boolean = true) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        if (autoFocus) {
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(72.dp)
            .padding(horizontal = Spacing.xs)
            .testTag("top_bar_search"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = search::close, modifier = Modifier.testTag("top_bar_search_back")) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.core_ui_cd_close_search))
        }
        TextField(
            value = search.query,
            onValueChange = { search.onQueryChange(it.replace("\n", "")) },
            placeholder = { Text(search.placeholder) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.weight(1f).focusRequester(focus).testTag("top_bar_search_field"),
        )
        IconButton(onClick = search::close, modifier = Modifier.testTag("top_bar_search_close")) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.core_ui_cd_clear_search))
        }
    }
    // After the field, so that back reaches the search before anything the field registers.
    BackHandler(onBack = search::close)
}
