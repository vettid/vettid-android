package com.vettid.app.spike

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vettid.core.calls.AudioRoute
import com.vettid.core.calls.CallEngine
import com.vettid.core.calls.CallKey
import com.vettid.core.calls.CallSnapshot
import com.vettid.core.calls.SignalBlob
import com.vettid.core.ui.components.QrCode
import com.vettid.core.ui.components.QrScanner
import com.vettid.core.ui.components.VettIdBackTopBar
import com.vettid.core.ui.components.rememberCameraPermission
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.VettIdTheme
import kotlinx.coroutines.delay
import kotlin.math.sqrt

/**
 * C0 SPIKE (CALLS-PLAN 0.1.1 §4, §7): two phones on one network exchange Opus audio, frame-encrypted by the media
 * stack's `FrameCryptor` under a 32-byte key typed on both. Signalling is a QR code or the clipboard. Debug builds
 * only: the launcher entry "VettID calls spike". The window is not FLAG_SECURE (the tester photographs results); the
 * key on it is a test key.
 */
class CallsSpikeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Recorded before anything of the spike runs: the app's own start must not have loaded the media stack.
        val loadedAtOpen = CallEngine.nativeLibraryLoaded() && !SpikeSession.everStarted
        setContent {
            VettIdTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                        VettIdBackTopBar(onBackClick = ::finish)
                        SpikeScreen(loadedAtOpen)
                    }
                }
            }
        }
    }
}

private enum class Scan { NONE, KEY, SIGNAL }

@Composable
private fun SpikeScreen(loadedAtOpen: Boolean) {
    val context = LocalContext.current
    val engine by SpikeSession.engine.collectAsStateWithLifecycle()
    val notes by SpikeSession.notes.collectAsStateWithLifecycle()
    var keyText by rememberSaveable { mutableStateOf("") }
    var scan by rememberSaveable { mutableStateOf(Scan.NONE) }
    var message by rememberSaveable { mutableStateOf("") }
    val key = CallKey.parse(keyText)
    val microphone = rememberPermissions()

    fun take(text: String) {
        message = takeSignal(context, text, key)
        if (message.isEmpty()) scan = Scan.NONE
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter).padding(bottom = Spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text("Calls spike C0 (debug only)", style = MaterialTheme.typography.headlineSmall)
        ProcessCard(loadedAtOpen, engine)
        if (!microphone.granted) {
            Button(onClick = microphone.request, modifier = Modifier.testTag("spike.permission")) { Text("Allow the microphone") }
        }
        KeySection(keyText, key, editable = engine == null, onChange = { keyText = it }, onScan = { scan = Scan.KEY })

        SignalButtons(
            canOffer = key != null && engine == null && microphone.granted,
            canTake = microphone.granted,
            onOffer = {
                key?.let { SpikeSession.start(context, it) }
                SpikeSession.engine.value?.startAsCaller()
            },
            onScan = { scan = Scan.SIGNAL },
            onPaste = { take(pasted(context)) },
        )
        if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error)

        if (scan != Scan.NONE) {
            Scanner(
                onText = { text ->
                    val scanned = CallKey.parse(text)
                    if (scan == Scan.SIGNAL) {
                        take(text)
                    } else if (scanned != null) {
                        keyText = CallKey.hex(scanned)
                        scan = Scan.NONE
                    }
                },
                onClose = { scan = Scan.NONE },
            )
        }

        engine?.let { call -> CallPanel(call, notes) }
    }
}

@Composable
private fun SignalButtons(canOffer: Boolean, canTake: Boolean, onOffer: () -> Unit, onScan: () -> Unit, onPaste: () -> Unit) {
    Section("2. Signalling (QR code or clipboard, between the two phones)")
    Buttons {
        Button(onClick = onOffer, enabled = canOffer, modifier = Modifier.testTag("spike.offer")) { Text("Phone A: make the offer") }
        OutlinedButton(onClick = onScan, enabled = canTake) { Text("Scan offer / answer") }
        OutlinedButton(onClick = onPaste, enabled = canTake) { Text("Paste offer / answer") }
    }
}

/** An offer or an answer from the other phone, scanned or pasted: acts on it, or says why not ("" when taken). */
private fun takeSignal(context: Context, text: String, key: ByteArray?): String {
    val blob = SignalBlob.decode(text)
    val problem = signalProblem(blob, key, SpikeSession.engine.value)
    if (blob != null && problem.isEmpty()) {
        if (blob.kind == SignalBlob.Kind.OFFER && key != null) {
            SpikeSession.start(context, key)
            SpikeSession.engine.value?.acceptOffer(blob.sdp)
        } else {
            SpikeSession.engine.value?.acceptAnswer(blob.sdp)
        }
    }
    return problem
}

/** Why a scanned or pasted text cannot be used now, or "" if it can. */
private fun signalProblem(blob: SignalBlob.Decoded?, key: ByteArray?, call: CallEngine?): String = when {
    blob == null -> "That is not an offer or an answer."
    blob.kind == SignalBlob.Kind.OFFER && key == null -> "Enter the key first."
    blob.kind == SignalBlob.Kind.OFFER && call != null -> "A call is already open: hang up first."
    blob.kind == SignalBlob.Kind.ANSWER && call == null -> "Make an offer first."
    else -> ""
}

@Composable
private fun KeySection(keyText: String, key: ByteArray?, editable: Boolean, onChange: (String) -> Unit, onScan: () -> Unit) {
    var showQr by rememberSaveable { mutableStateOf(false) }
    Section("1. Key (the same 64 hex characters on both phones)")
    OutlinedTextField(
        value = keyText,
        onValueChange = onChange,
        enabled = editable,
        label = { Text("32-byte key, hexadecimal") },
        supportingText = {
            Text(if (key != null) "Key check: ${CallKey.check(key)} (must be the same on both phones)" else "Not a 32-byte key yet")
        },
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth().testTag("spike.key"),
    )
    Buttons {
        OutlinedButton(onClick = { onChange(CallKey.hex(CallKey.random())) }, enabled = editable) { Text("Random key") }
        OutlinedButton(onClick = { showQr = !showQr }, enabled = key != null) { Text(if (showQr) "Hide key QR" else "Show key QR") }
        OutlinedButton(onClick = onScan, enabled = editable) { Text("Scan key") }
    }
    if (showQr && key != null) QrCode(CallKey.hex(key), "The test key as a QR code", size = 220.dp)
}

@Composable
private fun CallPanel(call: CallEngine, notes: List<String>) {
    val context = LocalContext.current
    val s by call.state.collectAsStateWithLifecycle()

    s.localBlob?.let { blob ->
        val kind = if (SignalBlob.decode(blob)?.kind == SignalBlob.Kind.OFFER) "offer" else "answer"
        Text("This phone's $kind (${blob.length} characters): scan it with the other phone, or copy it.")
        QrCode(blob, "This phone's $kind as a QR code", size = 320.dp)
        OutlinedButton(onClick = { copy(context, blob) }) { Text("Copy $kind") }
    } ?: Text("Gathering addresses… (${s.gathering})")

    Section("3. State")
    Facts(
        "Role" to s.role,
        "Connection" to s.connection,
        "ICE" to "${s.ice} (gathering ${s.gathering}, signalling ${s.signaling})",
        "Candidate pair" to "local ${s.stats.local ?: "–"}  ↔  remote ${s.stats.remote ?: "–"}  ${s.stats.pairState}",
        "Round trip" to (s.stats.roundTripMs?.let { "%.1f ms".format(it) } ?: "–"),
        "DTLS / SRTP" to "${s.stats.dtlsState} / ${s.stats.srtpCipher}",
        "Codec" to "send ${s.stats.sendCodec.ifEmpty { "–" }}, receive ${s.stats.receiveCodec.ifEmpty { "–" }} (Opus ${s.opusOnly})",
        "Frame cryptor, sending" to s.senderCryptor,
        "Frame cryptor, receiving" to s.receiverCryptor,
    )
    s.error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }

    Section("4. Sound (0.000 is silence; speech is about 0.01 to 0.5)")
    Meter("Microphone, this phone", s.stats.micLevel)
    Meter("Received from the other phone, decrypted", s.stats.remoteLevel)
    Facts(
        "Packets" to "sent ${s.stats.packetsSent}, received ${s.stats.packetsReceived} (${s.stats.bytesReceived} bytes)",
        "Samples" to "received ${s.stats.samplesReceived}, concealed ${s.stats.concealedSamples}",
    )

    Section("5. Tests")
    Buttons {
        FilterChip(selected = s.wrongKey, onClick = { call.setWrongKey(!s.wrongKey) }, label = { Text("Wrong key on this phone") })
        FilterChip(selected = s.sendPlain, onClick = { call.setSendPlain(!s.sendPlain) }, label = { Text("Send unencrypted") })
        FilterChip(selected = s.micMuted, onClick = { call.setMicMuted(!s.micMuted) }, label = { Text("Mute microphone") })
        OutlinedButton(onClick = call::reapplyKey) { Text("Set the key again") }
        OutlinedButton(onClick = call::selfCheck) { Text("Format self-check") }
    }
    if (s.selfCheck.isNotEmpty()) Text("Self-check: ${s.selfCheck}", modifier = Modifier.testTag("spike.selfcheck"))

    Routes()
    if (notes.isNotEmpty()) Text(notes.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
    Button(onClick = { SpikeSession.stop(context) }, modifier = Modifier.testTag("spike.hangup")) { Text("Hang up") }
}

/** The process facts of CALLS-PLAN §4 proof 5: when the native library loads, what it costs. */
@Composable
private fun ProcessCard(loadedAtOpen: Boolean, engine: CallEngine?) {
    var pssKb by remember { mutableStateOf(0L) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            pssKb = Debug.getPss()
            loaded = CallEngine.nativeLibraryLoaded()
            delay(PROCESS_REFRESH_MS)
        }
    }
    val snapshot = engine?.state?.collectAsStateWithLifecycle()?.value
    Facts(
        "Phone" to "${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "Media library loaded when this screen opened" to if (loadedAtOpen) "YES (unexpected)" else "no",
        "Media library loaded now" to if (loaded) "yes" else "no",
        "Load, then factory" to (snapshot?.let { "${it.initMs} ms, ${it.factoryMs} ms" } ?: "–"),
        "Process memory (PSS)" to "%.1f MB".format(pssKb / KB_PER_MB),
    )
}

@Composable
private fun Routes() {
    val audio = SpikeSession.audio ?: return
    var routes by remember { mutableStateOf(emptyList<AudioRoute>()) }
    var current by remember { mutableStateOf<AudioRoute?>(null) }
    LaunchedEffect(audio) {
        while (true) {
            routes = audio.routes()
            current = audio.current()
            delay(PROCESS_REFRESH_MS)
        }
    }
    Section("6. Audio route")
    Buttons {
        routes.forEach { route ->
            FilterChip(
                selected = route.id == current?.id,
                onClick = { if (!audio.select(route)) SpikeSession.note("route ${route.label} refused") },
                label = { Text(route.label) },
            )
        }
    }
}

@Composable
private fun Scanner(onText: (String) -> Unit, onClose: () -> Unit) {
    val camera = rememberCameraPermission()
    if (camera.granted) {
        QrScanner(onText = onText, modifier = Modifier.fillMaxWidth().height(360.dp), onUnavailable = onClose)
    } else {
        Button(onClick = camera.request) { Text("Allow the camera to scan") }
    }
    OutlinedButton(onClick = onClose) { Text("Stop scanning") }
}

@Composable
private fun Meter(label: String, level: Double) {
    Text("$label: ${"%.3f".format(level)}")
    LinearProgressIndicator(
        progress = { sqrt(level.coerceIn(0.0, 1.0)).toFloat() },
        modifier = Modifier.fillMaxWidth().height(12.dp),
        drawStopIndicator = {},
    )
}

@Composable
private fun Section(title: String) = Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Spacing.s))

@Composable
private fun Facts(vararg rows: Pair<String, String>) = Column {
    rows.forEach { (name, value) -> Text("$name: $value", style = MaterialTheme.typography.bodyMedium) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Buttons(content: @Composable () -> Unit) =
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) { content() }

private class Permissions(val granted: Boolean, val request: () -> Unit)

/** The microphone (required) and notifications (for the foreground service's notice; the call runs without). */
@Composable
private fun rememberPermissions(): Permissions {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = it[Manifest.permission.RECORD_AUDIO] == true
    }
    return Permissions(granted) {
        launcher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
    }
}

private fun copy(context: Context, text: String) =
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("VettID calls spike", text))

private fun pasted(context: Context): String {
    val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
    return clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
}

private const val PROCESS_REFRESH_MS = 1000L
private const val KB_PER_MB = 1024.0
