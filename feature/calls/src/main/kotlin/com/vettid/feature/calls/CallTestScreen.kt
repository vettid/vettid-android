package com.vettid.feature.calls

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vettid.core.calls.CallDiagnostics
import com.vettid.core.calls.CallState
import com.vettid.core.calls.CallsController
import com.vettid.core.calls.CallsDiagnostics
import com.vettid.core.calls.MediaConfig
import com.vettid.core.calls.MediaFaults
import com.vettid.core.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * THE TWO-PHONE TESTS ONLY (CALLS-PLAN 0.1.1 C1): the switches that make this phone break one rule of
 * docs/calls/CALL-MEDIA.md §7 on purpose, and what a call measured, as plain text for `uiautomator dump`.
 *
 * It exists in a build made with `-PvettidCallsTest=true` (and in debuggable builds); in any other the app has no
 * way to it and R8 removes it. And it does something only while [CallsController.testsAllowed]: the vault has
 * said, since the last unlock, that this app is signed with a development key. Its texts are not translated and
 * not member-facing: they are labels for the test scripts, kept stable.
 */
object CallTest {
    /** The screen's title, and the string the APK check looks for: only a test build's dex has it. */
    const val MARKER = "VettID call tests (test build)"

    /** The switches, by their stable labels. */
    val SWITCHES: List<Pair<String, (MediaConfig) -> Boolean>> = listOf(
        "keepRemoteSsrc" to { c -> c.faults.keepRemoteSsrc },
        "misstateOwnSsrc" to { c -> c.faults.misstateOwnSsrc },
        "sendUnencrypted" to { c -> c.faults.sendUnencrypted },
        "wrongSendKey" to { c -> c.faults.wrongSendKey },
        "dtlsPqcTrial" to { c -> c.dtlsPqcTrial },
    )

    fun toggled(c: MediaConfig, label: String): MediaConfig {
        val f = c.faults
        return when (label) {
            "keepRemoteSsrc" -> c.copy(faults = f.copy(keepRemoteSsrc = !f.keepRemoteSsrc))
            "misstateOwnSsrc" -> c.copy(faults = f.copy(misstateOwnSsrc = !f.misstateOwnSsrc))
            "sendUnencrypted" -> c.copy(faults = f.copy(sendUnencrypted = !f.sendUnencrypted))
            "wrongSendKey" -> c.copy(faults = f.copy(wrongSendKey = !f.wrongSendKey))
            "dtlsPqcTrial" -> c.copy(dtlsPqcTrial = !c.dtlsPqcTrial)
            else -> c
        }
    }

    /**
     * What the tests read, one `label: value` a line, the labels stable. Counts, states and types only: no
     * identifier, no address, nothing secret.
     */
    @Suppress("CyclomaticComplexMethod") // One flat list: a line per label, each with its "-" for "not known".
    fun lines(
        allowed: Boolean,
        config: MediaConfig,
        call: CallState,
        d: CallDiagnostics,
        all: CallsDiagnostics,
    ): List<Pair<String, String>> {
        val m = d.media
        val state = when (call) {
            CallState.Idle -> "idle"
            is CallState.Live -> "live ${call.phase}${if (call.reconnecting) " reconnecting" else ""}${if (call.muted) " muted" else ""}"
            is CallState.Ended -> "ended ${call.reason}"
        }
        return listOf(
            "testsAllowed" to allowed.toString(),
            "faults" to (SWITCHES.filter { it.second(config) }.joinToString(",") { it.first }.ifEmpty { "none" }),
            "call" to state,
            "sendCryptor" to (m?.sendCryptor?.name ?: "-"),
            "receiveCryptor" to (m?.receiveCryptor?.name ?: "-"),
            "cryptorsMoved" to (m?.cryptorsMoved?.toString() ?: "-"),
            "inboundAudioStreams" to (m?.inboundAudioStreams?.toString() ?: "-"),
            "packetsSent" to (m?.packetsSent?.toString() ?: "-"),
            "packetsReceived" to (m?.packetsReceived?.toString() ?: "-"),
            "micLevel" to (m?.micLevel?.let { "%.3f".format(java.util.Locale.ROOT, it) } ?: "-"),
            "remoteLevel" to (m?.remoteLevel?.let { "%.3f".format(java.util.Locale.ROOT, it) } ?: "-"),
            "codec" to (m?.let { "${it.sendCodec}/${it.receiveCodec}" } ?: "-"),
            "dtlsState" to (m?.transport?.get("dtlsState") ?: "-"),
            "tlsVersion" to (m?.transport?.get("tlsVersion") ?: "-"),
            "dtlsCipher" to (m?.transport?.get("dtlsCipher") ?: "-"),
            "srtpCipher" to (m?.transport?.get("srtpCipher") ?: "-"),
            "dtlsPqcTrialInProcess" to (m?.dtlsPqcTrial?.toString() ?: "-"),
            "path" to (m?.relayed?.let { if (it) "relayed" else "direct" } ?: "-"),
            "localCandidate" to (m?.local?.toString() ?: "-"),
            "remoteCandidate" to (m?.remote?.toString() ?: "-"),
            "roundTripMs" to (m?.roundTripMs?.let { "%.0f".format(java.util.Locale.ROOT, it) } ?: "-"),
            "tapToPlacedMs" to (d.tapToPlacedMs?.toString() ?: "-"),
            "tapToRingingMs" to (d.tapToRingingMs?.toString() ?: "-"),
            "answerToMediaMs" to (d.answerToMediaMs?.toString() ?: "-"),
            "staleEnded" to all.staleEnded.toString(),
            "droppedForTime" to all.droppedForTime.toString(),
            "refused" to all.refused.toString(),
            "errors" to all.errors.toString(),
            "listenRestarts" to all.listenRestarts.toString(),
        )
    }
}

data class CallTestUiState(
    val allowed: Boolean = false,
    val config: MediaConfig = MediaConfig(),
    val lines: List<Pair<String, String>> = emptyList(),
)

@HiltViewModel
class CallTestViewModel @Inject constructor(private val calls: CallsController) : ViewModel() {
    /** Bumped when a switch changed (the controller's configuration is not a flow). */
    private val changed = MutableStateFlow(0)

    val uiState: StateFlow<CallTestUiState> =
        combine(calls.testsAllowed, calls.state, calls.callDiagnostics, calls.diagnostics, changed) { allowed, call, d, all, _ ->
            val config = calls.mediaConfig
            CallTestUiState(allowed, config, CallTest.lines(allowed, config, call, d, all))
        }.stateIn(viewModelScope, SharingStarted.Eagerly, CallTestUiState())

    /** Flips one switch for the next call. Refused by the controller unless the tests are allowed. */
    fun toggle(label: String) {
        runCatching { calls.setMediaConfig(CallTest.toggled(calls.mediaConfig, label)) }
        changed.value++
    }

    fun reset() {
        calls.setMediaConfig(MediaConfig(faults = MediaFaults()))
        changed.value++
    }
}

@Composable
fun CallTestScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm: CallTestViewModel = hiltViewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    CallTestContent(state, vm::toggle, vm::reset, onBack, modifier)
}

@Composable
fun CallTestContent(
    state: CallTestUiState,
    onToggle: (String) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize().testTag("calltest"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(Spacing.l)) {
            Text(CallTest.MARKER, style = MaterialTheme.typography.titleLarge)
            Row {
                TextButton(onClick = onBack, modifier = Modifier.testTag("calltest_back")) { Text("Back") }
                TextButton(onClick = onReset, modifier = Modifier.testTag("calltest_reset")) { Text("All off") }
            }
            for ((label, on) in CallTest.SWITCHES) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f))
                    Switch(
                        checked = on(state.config),
                        onCheckedChange = { onToggle(label) },
                        enabled = state.allowed,
                        modifier = Modifier.testTag("calltest_switch_$label"),
                    )
                }
            }
            for ((label, value) in state.lines) {
                Text("$label: $value", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("calltest_$label"))
            }
        }
    }
}
