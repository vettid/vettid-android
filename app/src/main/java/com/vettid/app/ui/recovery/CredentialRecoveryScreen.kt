package com.vettid.app.ui.recovery

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialRecoveryScreen(
    viewModel: CredentialRecoveryViewModel = hiltViewModel(),
    onRecoveryComplete: () -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val enteredWords by viewModel.enteredWords.collectAsState()
    val wordValidation by viewModel.wordValidation.collectAsState()
    val canSubmit by viewModel.canSubmit.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recover Credentials") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (val currentState = state) {
                is CredentialRecoveryState.EnteringPhrase -> {
                    EnteringPhraseContent(
                        words = enteredWords,
                        validation = wordValidation,
                        canSubmit = canSubmit,
                        onWordChange = viewModel::setWord,
                        onPaste = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                            if (text != null) {
                                viewModel.parseAndSetWords(text)
                            }
                        },
                        onClear = viewModel::clearWords,
                        onRecover = viewModel::recoverCredentials,
                        getSuggestions = viewModel::getSuggestions
                    )
                }

                is CredentialRecoveryState.Validating -> {
                    LoadingContent(message = "Validating recovery phrase...")
                }

                is CredentialRecoveryState.Downloading -> {
                    LoadingContent(message = "Downloading encrypted backup...")
                }

                is CredentialRecoveryState.Decrypting -> {
                    LoadingContent(message = "Decrypting credentials...")
                }

                is CredentialRecoveryState.Complete -> {
                    CompleteContent(onDone = onRecoveryComplete)
                }

                is CredentialRecoveryState.Error -> {
                    ErrorContent(
                        message = currentState.message,
                        onRetry = viewModel::reset
                    )
                }
            }
        }
    }
}

@Composable
fun EnteringPhraseContent(
    words: List<String>,
    validation: List<Boolean>,
    canSubmit: Boolean,
    onWordChange: (Int, String) -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    onRecover: () -> Unit,
    getSuggestions: (String) -> List<String>
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Enter Your Recovery Phrase",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Enter all 24 words of your recovery phrase in order.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Action buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onPaste,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Paste")
            }

            OutlinedButton(
                onClick = onClear,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Clear")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Word input grid
        RecoveryPhraseInput(
            words = words,
            validation = validation,
            onWordChange = onWordChange,
            getSuggestions = getSuggestions,
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onRecover,
            modifier = Modifier.fillMaxWidth(),
            enabled = canSubmit
        ) {
            Text("Recover Credentials")
        }
    }
}

@Composable
fun RecoveryPhraseInput(
    words: List<String>,
    validation: List<Boolean>,
    onWordChange: (Int, String) -> Unit,
    getSuggestions: (String) -> List<String>,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        itemsIndexed(words) { index, word ->
            var showSuggestions by remember { mutableStateOf(false) }
            var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }

            Column {
                OutlinedTextField(
                    value = word,
                    onValueChange = { value ->
                        onWordChange(index, value)
                        suggestions = if (value.length >= 2) getSuggestions(value) else emptyList()
                        showSuggestions = suggestions.isNotEmpty()
                    },
                    label = { Text("${index + 1}") },
                    isError = !validation[index],
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        imeAction = if (index < 23) ImeAction.Next else ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Next) },
                        onDone = { focusManager.clearFocus() }
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall
                )

                // Suggestion dropdown
                if (showSuggestions && suggestions.isNotEmpty()) {
                    DropdownMenu(
                        expanded = showSuggestions,
                        onDismissRequest = { showSuggestions = false }
                    ) {
                        suggestions.take(4).forEach { suggestion ->
                            DropdownMenuItem(
                                text = { Text(suggestion) },
                                onClick = {
                                    onWordChange(index, suggestion)
                                    showSuggestions = false
                                    focusManager.moveFocus(FocusDirection.Next)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LoadingContent(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
fun CompleteContent(onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Recovery Complete",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Your credentials have been successfully recovered. You can now use the app as before.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Continue")
        }
    }
}

@Composable
fun ErrorContent(
    message: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Recovery Failed",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onRetry) {
            Text("Try Again")
        }
    }
}
