package com.vettid.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.map.MapServer
import com.vettid.core.data.prefs.PreferencesRepository
import com.vettid.core.ui.components.FormScaffold
import com.vettid.core.ui.components.HoldsInput
import com.vettid.core.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

/** Settings → Privacy → Map server (ANDROID-PLAN §4 "Location", L2): the tile server the position screen's map loads from. */
@Serializable
data object MapServerRoute

/**
 * The Map server form. [text] is what is typed; [storedText] the address in force (the default or the saved one). The form holds
 * input ([holdsInput], the daily owner check waits) while the typed address differs from the one in force.
 */
data class MapServerUiState(
    val loaded: Boolean = false,
    val text: String = "",
    val storedText: String = "",
    val host: String = "",
    val isDefault: Boolean = true,
    val problem: MapServer.Problem? = null,
    val saved: Boolean = false,
) {
    /** An address is being edited and not saved. */
    val holdsInput: Boolean get() = loaded && text.trim() != storedText

    val canSave: Boolean get() = loaded && text.isNotBlank() && text.trim() != storedText

    override fun toString(): String = "MapServerUiState"
}

@HiltViewModel
class MapServerViewModel @Inject constructor(private val prefs: PreferencesRepository) : ViewModel() {
    private val state = MutableStateFlow(MapServerUiState())
    val uiState: StateFlow<MapServerUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            val setting = MapServer.of(prefs.preferences.first())
            state.update {
                it.copy(loaded = true, text = setting.styleUrl, storedText = setting.styleUrl, host = setting.host, isDefault = setting.isDefault)
            }
        }
    }

    fun setText(text: String) = state.update { it.copy(text = text, problem = null, saved = false) }

    /** Saves the typed address if it passes the rules (https, no credentials, a host); otherwise says why and saves nothing. */
    fun save() {
        val check = MapServer.parse(state.value.text)
        if (check is MapServer.Check.Refused) {
            state.update { it.copy(problem = check.problem, saved = false) }
            return
        }
        check as MapServer.Check.Ok
        viewModelScope.launch {
            // The default is stored as "no own server", so that it follows the app if the default ever changes.
            prefs.setMapStyleUrl(check.url.takeUnless { it == MapServer.DEFAULT_STYLE })
            state.update {
                it.copy(
                    text = check.url, storedText = check.url, host = check.host, isDefault = check.url == MapServer.DEFAULT_STYLE,
                    problem = null, saved = true,
                )
            }
        }
    }

    /** "Use default": back to OpenFreeMap at once. */
    fun useDefault() {
        viewModelScope.launch {
            prefs.setMapStyleUrl(null)
            val host = (MapServer.parse(MapServer.DEFAULT_STYLE) as MapServer.Check.Ok).host
            state.update {
                it.copy(text = MapServer.DEFAULT_STYLE, storedText = MapServer.DEFAULT_STYLE, host = host, isDefault = true, problem = null, saved = true)
            }
        }
    }
}

@Composable
fun MapServerRouteContent(vm: MapServerViewModel, onBack: () -> Unit) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    MapServerContent(state, vm::setText, vm::save, vm::useDefault, onBack)
}

@Composable
fun MapServerContent(state: MapServerUiState, onText: (String) -> Unit, onSave: () -> Unit, onUseDefault: () -> Unit, onBack: () -> Unit) {
    HoldsInput(state.holdsInput)
    FormScaffold(
        title = stringResource(R.string.settings_map_server_title),
        body = if (state.host.isEmpty()) null else stringResource(R.string.settings_map_server_current, state.host),
        primaryLabel = stringResource(R.string.settings_map_server_save),
        onPrimary = onSave,
        primaryEnabled = state.canSave,
        onBack = onBack,
        modifier = Modifier.testTag("map_server"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            OutlinedTextField(
                value = state.text,
                onValueChange = onText,
                label = { Text(stringResource(R.string.settings_map_server_url_label)) },
                singleLine = true,
                isError = state.problem != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().testTag("map_server_url"),
            )
            state.problem?.let { p ->
                Text(
                    stringResource(problemRes(p)),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("map_server_problem"),
                )
            }
            if (state.saved) Text(stringResource(R.string.settings_map_server_saved), modifier = Modifier.testTag("map_server_saved"))
            if (!state.isDefault || state.text.trim() != MapServer.DEFAULT_STYLE) {
                TextButton(onClick = onUseDefault, modifier = Modifier.heightIn(min = 48.dp).testTag("map_server_default")) {
                    Text(stringResource(R.string.settings_map_server_default))
                }
            }
            Text(
                stringResource(R.string.settings_map_server_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("map_server_note"),
            )
        }
    }
}

internal fun problemRes(p: MapServer.Problem): Int = when (p) {
    MapServer.Problem.NOT_A_URL -> R.string.settings_map_url_not_a_url
    MapServer.Problem.NOT_HTTPS -> R.string.settings_map_url_not_https
    MapServer.Problem.CREDENTIALS -> R.string.settings_map_url_credentials
    MapServer.Problem.NO_HOST -> R.string.settings_map_url_no_host
}
