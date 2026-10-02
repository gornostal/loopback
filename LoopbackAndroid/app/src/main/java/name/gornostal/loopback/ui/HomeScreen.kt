package name.gornostal.loopback.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import name.gornostal.loopback.HomeTab
import name.gornostal.loopback.MainViewModel

/** The tabbed home shell: a bottom navigation bar switching between Pending, History and Settings. */
@Composable
fun HomeScreen(viewModel: MainViewModel) {
    HomeShell(
        tab = viewModel.tab,
        pendingCount = viewModel.pending.size,
        onSelectTab = viewModel::selectTab,
    ) { padding ->
        when (viewModel.tab) {
            HomeTab.Pending -> InboxScreen(viewModel, showHistory = false, modifier = Modifier.padding(padding))
            HomeTab.History -> InboxScreen(viewModel, showHistory = true, modifier = Modifier.padding(padding))
            HomeTab.Settings -> SettingsScreen(viewModel, modifier = Modifier.padding(padding))
        }
    }
}

/** Stateless shell; [HomeScreen] wires it to the ViewModel, previews feed it sample content. */
@Composable
fun HomeShell(
    tab: HomeTab,
    pendingCount: Int,
    onSelectTab: (HomeTab) -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = { LoopbackNavBar(tab, pendingCount, onSelectTab) },
        content = content,
    )
}

@Composable
private fun LoopbackNavBar(tab: HomeTab, pendingCount: Int, onSelectTab: (HomeTab) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column {
        HorizontalDivider(color = colors.outlineVariant, thickness = 1.dp)
        NavigationBar(containerColor = colors.surfaceContainerLow.copy(alpha = 0.92f), tonalElevation = 0.dp) {
            HomeTab.entries.forEach { item ->
                val selected = item == tab
                NavigationBarItem(
                    selected = selected,
                    onClick = { onSelectTab(item) },
                    icon = {
                        val icon = item.icon(selected)
                        if (item == HomeTab.Pending && pendingCount > 0) {
                            BadgedBox(badge = {
                                Badge(containerColor = colors.primary, contentColor = colors.onPrimary) {
                                    Text(pendingCount.toString())
                                }
                            }) { Icon(icon, contentDescription = null) }
                        } else {
                            Icon(icon, contentDescription = null)
                        }
                    },
                    label = { Text(item.label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = colors.primary,
                        selectedTextColor = colors.primary,
                        indicatorColor = colors.primary.copy(alpha = 0.16f),
                        unselectedIconColor = colors.onSurfaceVariant,
                        unselectedTextColor = colors.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}

private val HomeTab.label: String
    get() = when (this) {
        HomeTab.Pending -> "Pending"
        HomeTab.History -> "History"
        HomeTab.Settings -> "Settings"
    }

private fun HomeTab.icon(selected: Boolean): ImageVector = when (this) {
    HomeTab.Pending -> if (selected) Icons.Filled.Notifications else Icons.Outlined.Notifications
    HomeTab.History -> if (selected) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle
    HomeTab.Settings -> if (selected) Icons.Filled.Settings else Icons.Outlined.Settings
}
