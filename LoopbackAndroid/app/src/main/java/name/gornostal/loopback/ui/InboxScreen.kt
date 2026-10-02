package name.gornostal.loopback.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import name.gornostal.loopback.MainViewModel
import name.gornostal.loopback.api.LoopbackRequest
import name.gornostal.loopback.ui.theme.Brand

@Composable
fun InboxScreen(viewModel: MainViewModel, showHistory: Boolean, modifier: Modifier = Modifier) {
    InboxContent(
        requests = if (showHistory) viewModel.history else viewModel.pending,
        history = showHistory,
        configured = viewModel.settings.isConfigured,
        loading = viewModel.loading,
        error = viewModel.error,
        onClearError = viewModel::clearError,
        onRefresh = viewModel::refresh,
        onOpenSettings = viewModel::showSettings,
        onOpenRequest = viewModel::openRequest,
        modifier = modifier,
    )
}

/**
 * Stateless list of requests for either the Pending or History tab; [InboxScreen] wires it to the
 * ViewModel, previews feed it sample data.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxContent(
    requests: List<LoopbackRequest>,
    history: Boolean,
    configured: Boolean,
    loading: Boolean,
    error: String?,
    onClearError: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRequest: (id: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(error) {
        val message = error ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        onClearError()
    }

    val title = if (history) "History" else "Pending"
    val subtitle = when {
        !configured -> "Not connected to a server yet"
        history -> if (requests.isEmpty()) "Nothing answered yet" else "${requests.size} resolved"
        requests.isEmpty() -> "All caught up"
        requests.size == 1 -> "1 request is waiting for you"
        else -> "${requests.size} requests are waiting for you"
    }

    Box(modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "header") { ScreenHeader(title, subtitle) }
                if (requests.isEmpty()) {
                    item(key = "empty") {
                        when {
                            !configured -> EmptyState(
                                icon = Icons.Outlined.Notifications,
                                text = "Point the app at your Loopback server to start receiving requests.",
                                action = "Open settings" to onOpenSettings,
                            )
                            history -> EmptyState(
                                icon = Icons.Outlined.CheckCircle,
                                text = "Requests you've answered or that were withdrawn will show up here.",
                            )
                            else -> EmptyState(
                                icon = Icons.Outlined.Notifications,
                                text = "No pending requests.\nWhen an agent needs you, it shows up here.",
                            )
                        }
                    }
                } else {
                    items(requests, key = { it.id }) { request ->
                        RequestCard(request, onClick = { onOpenRequest(request.id) })
                    }
                }
            }
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter)) { Snackbar(it) }
    }
}

@Composable
private fun EmptyState(icon: ImageVector, text: String, action: Pair<String, () -> Unit>? = null) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier.size(72.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            TextButton(onClick = action.second) { Text(action.first) }
        }
    }
}

@Composable
internal fun RequestCard(request: LoopbackRequest, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (request.isPending) colors.surfaceContainerHigh.copy(alpha = 0.85f)
            else colors.surfaceContainerLow.copy(alpha = 0.85f),
            contentColor = colors.onSurface,
        ),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            // Pending cards carry the logo's loop as a stripe; resolved ones go quiet.
            if (request.isPending) Box(Modifier.width(4.dp).fillMaxHeight().background(Brand.LoopVertical))
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SourceAvatar(request.source)
                    Column(Modifier.weight(1f)) {
                        Text(
                            request.source?.takeIf { it.isNotBlank() } ?: "Agent",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            relativeTime(request.createdAt),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    if (!request.isPending) StatusPill(request)
                }
                Text(request.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                request.context?.lineSequence()?.firstOrNull { it.isNotBlank() }?.let { preview ->
                    Text(
                        stripInlineMarkdown(preview),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!request.isPending && request.answer != null) {
                    Text(
                        answerSummary(request),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.tertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

fun answerSummary(request: LoopbackRequest): String {
    val answer = request.answer ?: return ""
    val parts = answer.selected.toMutableList()
    answer.text?.takeIf { it.isNotBlank() }?.let { parts.add("“$it”") }
    return "You: " + parts.joinToString(", ")
}
