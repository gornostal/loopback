package name.gornostal.loopback.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import name.gornostal.loopback.MainViewModel
import name.gornostal.loopback.push.DeviceRegistrar

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    var pushToken by remember { mutableStateOf<String?>(null) }
    val firebaseAvailable = remember { DeviceRegistrar.isFirebaseAvailable(context) }

    LaunchedEffect(Unit) {
        pushToken = runCatching { DeviceRegistrar.currentToken(context) }.getOrNull()
    }

    SettingsContent(
        initialUrl = viewModel.settings.serverUrl,
        initialKey = viewModel.settings.apiKey,
        configured = viewModel.settings.isConfigured,
        firebaseAvailable = firebaseAvailable,
        pushToken = pushToken,
        deviceName = DeviceRegistrar.deviceName(),
        onBack = viewModel::showInbox,
        onSave = viewModel::saveSettings,
        onRegisterDevice = viewModel::registerDevice,
    )
}

/**
 * Stateless settings UI; [SettingsScreen] wires it to the ViewModel and Firebase, previews feed it sample data.
 * [onSave] and [onRegisterDevice] return an error message to show, or null on success.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    initialUrl: String,
    initialKey: String,
    configured: Boolean,
    firebaseAvailable: Boolean,
    pushToken: String?,
    deviceName: String,
    onBack: () -> Unit,
    onSave: suspend (url: String, key: String) -> String?,
    onRegisterDevice: suspend () -> String?,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var key by rememberSaveable { mutableStateOf(initialKey) }
    var busy by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (configured) "Settings" else "Connect to your server") },
                navigationIcon = {
                    if (configured) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) { Snackbar(it) } },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Point the app at your Loopback server. Agents post requests there and the server pushes them here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Server URL") },
                placeholder = { Text("https://loopback.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("App key") },
                supportingText = { Text("LOOPBACK_APP_KEY from the server, not the agent key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        val error = onSave(url, key)
                        busy = false
                        if (error == null) {
                            snackbar.showSnackbar("Connected")
                            onBack()
                        } else {
                            snackbar.showSnackbar(error)
                        }
                    }
                },
                enabled = !busy && url.isNotBlank() && key.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (busy) "Connecting…" else "Save & test connection") }

            HorizontalDivider()

            Text("Push notifications", style = MaterialTheme.typography.titleMedium)
            when {
                !firebaseAvailable -> Text(
                    "Firebase isn't configured in this build. Add app/google-services.json from the Firebase console and rebuild to receive pushes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                pushToken == null -> Text(
                    "Fetching push token…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Text(
                    "This device: $deviceName\nToken: ${pushToken.take(16)}…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val error = onRegisterDevice()
                        snackbar.showSnackbar(error ?: "Device registered with server")
                    }
                },
                enabled = firebaseAvailable && configured && pushToken != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Re-register this device") }
            Spacer(Modifier.height(16.dp))
        }
    }
}
