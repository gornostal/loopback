package name.gornostal.loopback.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import name.gornostal.loopback.MainViewModel
import name.gornostal.loopback.push.DeviceRegistrar

@Composable
fun SettingsScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
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
        onConnected = viewModel::showInbox,
        onSave = viewModel::saveSettings,
        onRegisterDevice = viewModel::registerDevice,
        modifier = modifier,
    )
}

/**
 * Stateless settings UI; [SettingsScreen] wires it to the ViewModel and Firebase, previews feed it sample data.
 * [onSave] and [onRegisterDevice] return an error message to show, or null on success.
 * [onConnected] fires after a successful save so the shell can jump to the inbox.
 */
@Composable
fun SettingsContent(
    initialUrl: String,
    initialKey: String,
    configured: Boolean,
    firebaseAvailable: Boolean,
    pushToken: String?,
    deviceName: String,
    onConnected: () -> Unit,
    onSave: suspend (url: String, key: String) -> String?,
    onRegisterDevice: suspend () -> String?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val colors = MaterialTheme.colorScheme
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var key by rememberSaveable { mutableStateOf(initialKey) }
    var busy by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ScreenHeader(
                title = if (configured) "Settings" else "Connect",
                subtitle = if (configured) "Server connection and push notifications"
                else "Point the app at your Loopback server",
            )

            SettingsSection(title = "Server") {
                Text(
                    "Agents post requests to the server and the server pushes them here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Server URL") },
                    placeholder = { Text("https://loopback.example.com") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("App key") },
                    supportingText = { Text("LOOPBACK_APP_KEY from the server, not the agent key") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                AccentButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            val error = onSave(url, key)
                            busy = false
                            if (error == null) {
                                snackbar.showSnackbar("Connected")
                                onConnected()
                            } else {
                                snackbar.showSnackbar(error)
                            }
                        }
                    },
                    enabled = !busy && url.isNotBlank() && key.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                ) {
                    Text(
                        if (busy) "Connecting…" else "Save & test connection",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            SettingsSection(title = "Push notifications") {
                when {
                    !firebaseAvailable -> Text(
                        "Firebase isn't configured in this build. Add app/google-services.json from the Firebase console and rebuild to receive pushes.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.error,
                    )
                    pushToken == null -> Text(
                        "Fetching push token…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                    else -> Text(
                        "This device: $deviceName\nToken: ${pushToken.take(16)}…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
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
            }
            Spacer(Modifier.height(8.dp))
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter)) { Snackbar(it) }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surfaceContainerLow.copy(alpha = 0.85f),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.primary)
            content()
        }
    }
}
