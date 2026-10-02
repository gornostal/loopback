package name.gornostal.loopback.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import name.gornostal.loopback.MainViewModel
import name.gornostal.loopback.api.LoopbackRequest

@Composable
fun InboxScreen(viewModel: MainViewModel) {
    InboxContent(
        pending = viewModel.pending,
        history = viewModel.history,
        loading = viewModel.loading,
        error = viewModel.error,
        onClearError = viewModel::clearError,
        onRefresh = viewModel::refresh,
        onOpenSettings = viewModel::showSettings,
        onOpenRequest = viewModel::openRequest,
    )
}

/** Stateless inbox UI; [InboxScreen] wires it to the ViewModel, previews feed it sample data. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxContent(
    pending: List<LoopbackRequest>,
    history: List<LoopbackRequest>,
    loading: Boolean,
    error: String?,
    onClearError: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRequest: (id: String) -> Unit,
    initialTab: Int = 0,
) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(error) {
        val message = error ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        onClearError()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Loopback") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) { Snackbar(it) } },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = {
                    val n = pending.size
                    Text(if (n > 0) "Pending ($n)" else "Pending")
                })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("History") })
            }
            val items = if (tab == 0) pending else history
            PullToRefreshBox(
                isRefreshing = loading,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (items.isEmpty()) {
                    EmptyState(
                        if (tab == 0) "No pending requests.\nWhen an agent needs you, it shows up here."
                        else "Nothing answered yet.",
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(items, key = { it.id }) { request ->
                            RequestCard(request, onClick = { onOpenRequest(request.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    // Scrollable so pull-to-refresh works on an empty list too.
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Box(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 96.dp), contentAlignment = Alignment.Center) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
internal fun RequestCard(request: LoopbackRequest, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (request.isPending) MaterialTheme.colorScheme.surfaceContainerHigh
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(request.source?.takeIf { it.isNotBlank() }, relativeTime(request.createdAt))
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (!request.isPending) StatusChip(request)
            }
            Text(request.title, style = MaterialTheme.typography.titleMedium)
            request.context?.lineSequence()?.firstOrNull { it.isNotBlank() }?.let { preview ->
                Text(
                    stripInlineMarkdown(preview),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!request.isPending && request.answer != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    answerSummary(request),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun StatusChip(request: LoopbackRequest) {
    val label = when (request.status) {
        "answered" -> "Answered"
        "cancelled" -> "Cancelled"
        else -> request.status.replaceFirstChar { it.uppercase() }
    }
    SuggestionChip(onClick = {}, enabled = false, label = { Text(label) })
}

fun answerSummary(request: LoopbackRequest): String {
    val answer = request.answer ?: return ""
    val parts = answer.selected.toMutableList()
    answer.text?.takeIf { it.isNotBlank() }?.let { parts.add("“$it”") }
    return "You: " + parts.joinToString(", ")
}
