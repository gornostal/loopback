package name.gornostal.loopback

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import name.gornostal.loopback.push.Notifications
import name.gornostal.loopback.ui.HomeScreen
import name.gornostal.loopback.ui.RequestScreen
import name.gornostal.loopback.ui.theme.LoopbackSurface
import name.gornostal.loopback.ui.theme.LoopbackTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark-only UI: force light system bar icons regardless of the system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        handleIntent(intent)
        setContent {
            LoopbackTheme {
                LoopbackSurface {
                    LaunchedEffect(Unit) { askNotificationPermission() }
                    when (val screen = viewModel.screen) {
                        Screen.Home -> {
                            // Back from a secondary tab returns to Pending; from Pending it exits.
                            BackHandler(enabled = viewModel.tab != HomeTab.Pending && viewModel.settings.isConfigured) {
                                viewModel.selectTab(HomeTab.Pending)
                            }
                            HomeScreen(viewModel)
                        }
                        is Screen.Request -> {
                            BackHandler { viewModel.showHome() }
                            RequestScreen(viewModel, screen.id)
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
