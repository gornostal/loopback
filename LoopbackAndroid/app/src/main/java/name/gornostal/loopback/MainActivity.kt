package name.gornostal.loopback

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.core.content.ContextCompat
import name.gornostal.loopback.push.Notifications
import name.gornostal.loopback.ui.InboxScreen
import name.gornostal.loopback.ui.RequestScreen
import name.gornostal.loopback.ui.SettingsScreen
import name.gornostal.loopback.ui.theme.LoopbackTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            LoopbackTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LaunchedEffect(Unit) { askNotificationPermission() }
                    when (val screen = viewModel.screen) {
                        Screen.Inbox -> InboxScreen(viewModel)
                        is Screen.Request -> {
                            BackHandler { viewModel.showInbox() }
                            RequestScreen(viewModel, screen.id)
                        }
                        Screen.Settings -> {
                            BackHandler(enabled = viewModel.settings.isConfigured) { viewModel.showInbox() }
                            SettingsScreen(viewModel)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val requestId = intent?.getStringExtra(Notifications.EXTRA_REQUEST_ID) ?: return
        intent.removeExtra(Notifications.EXTRA_REQUEST_ID)
        viewModel.openRequest(requestId)
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
