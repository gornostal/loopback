package name.gornostal.loopback.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import name.gornostal.loopback.MainViewModel
import name.gornostal.loopback.api.LoopbackRequest
import name.gornostal.loopback.api.RequestOption

@Composable
fun RequestScreen(viewModel: MainViewModel, requestId: String) {
    RequestContent(
        request = viewModel.openRequest?.takeIf { it.id == requestId },
        loading = viewModel.openLoading,
        submitting = viewModel.submitting,
        onBack = viewModel::showInbox,
        onSubmit = { id, selected, text -> viewModel.submitAnswer(id, selected, text) },
    )
}

/**
 * Stateless request detail UI; [RequestScreen] wires it to the ViewModel, previews feed it sample data.
 * [onSubmit] returns an error message to show, or null on success.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestContent(
    request: LoopbackRequest?,
    loading: Boolean,
    submitting: Boolean,
    onBack: () -> Unit,
    onSubmit: suspend (id: String, selected: List<String>, text: String?) -> String?,
) {
    val snackbar = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(request?.source?.takeIf { it.isNotBlank() } ?: "Request") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) { Snackbar(it) } },
    ) { padding ->
        when {
            request != null -> RequestBody(
                request = request,
                submitting = submitting,
                onSubmit = { selected, text ->
                    onSubmit(request.id, selected, text)?.let { snackbar.showSnackbar(it) }
                },
                modifier = Modifier.padding(padding),
            )
            loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Request not found", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RequestBody(
    request: LoopbackRequest,
    submitting: Boolean,
    onSubmit: suspend (selected: List<String>, text: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    // Selection state keyed by request id so switching requests resets it.
    var selected by rememberSaveable(request.id) { mutableStateOf(setOf<String>()) }
    var text by rememberSaveable(request.id) { mutableStateOf("") }
    val readOnly = !request.isPending
    val shownSelected = if (readOnly) request.answer?.selected?.toSet().orEmpty() else selected
    val canSubmit = !submitting && (selected.isNotEmpty() || text.isNotBlank())

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            relativeTime(request.createdAt),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(request.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        request.context?.takeIf { it.isNotBlank() }?.let { context ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                MarkdownText(context, modifier = Modifier.padding(16.dp))
            }
        }

        if (readOnly) ResolvedBanner(request)

        if (request.options.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (request.multiSelect) "Choose any that apply" else "Choose one",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                request.options.forEach { option ->
                    OptionRow(
                        option = option,
                        selected = option.label in shownSelected,
                        multi = request.multiSelect,
                        enabled = !readOnly && !submitting,
                        onClick = {
                            selected = when {
                                request.multiSelect ->
                                    if (option.label in selected) selected - option.label else selected + option.label
                                option.label in selected -> emptySet()
                                else -> setOf(option.label)
                            }
                        },
                    )
                }
            }
        }

        if (request.allowFreeText || (readOnly && !request.answer?.text.isNullOrBlank())) {
            OutlinedTextField(
                value = if (readOnly) request.answer?.text.orEmpty() else text,
                onValueChange = { text = it },
                label = {
                    Text(if (request.options.isEmpty()) "My answer" else "My answer (optional)")
                },
                placeholder = { Text("Type anything the options don't cover…") },
                readOnly = readOnly,
                enabled = !submitting,
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (!readOnly) {
            Button(
                onClick = { scope.launch { onSubmit(selected.toList(), text.takeIf { it.isNotBlank() }) } },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (submitting) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text("Send answer", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun OptionRow(
    option: RequestOption,
    selected: Boolean,
    multi: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        enabled = enabled,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) colors.primaryContainer else colors.surfaceContainerHigh,
            disabledContainerColor = if (selected) colors.primaryContainer else colors.surfaceContainerLow,
            disabledContentColor = colors.onSurface,
        ),
        border = if (selected) BorderStroke(1.5.dp, colors.primary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (multi) Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
            else RadioButton(selected = selected, onClick = null, enabled = enabled)
            Column(Modifier.padding(start = 4.dp).weight(1f)) {
                Text(option.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                option.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ResolvedBanner(request: LoopbackRequest) {
    val (text, color) = when (request.status) {
        "answered" -> "You answered ${request.answer?.answeredAt?.let { absoluteTime(it) }.orEmpty()}" to
            MaterialTheme.colorScheme.primary
        "cancelled" -> "The agent withdrew this request" to MaterialTheme.colorScheme.error
        else -> "This request is ${request.status}" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column {
        HorizontalDivider()
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = color,
            modifier = Modifier.padding(vertical = 10.dp),
        )
        HorizontalDivider()
    }
}
