package name.gornostal.loopback.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import name.gornostal.loopback.api.Answer
import name.gornostal.loopback.api.LoopbackRequest
import name.gornostal.loopback.api.RequestOption
import name.gornostal.loopback.ui.theme.LoopbackTheme
import java.time.Duration
import java.time.Instant

// Android Studio design-time previews. Open any file in this package and switch to the
// Split/Design view to render them without building or installing the app.

private fun ago(duration: Duration): String = Instant.now().minus(duration).toString()

private val sampleContext = """
    I'm about to run the database migration for the **orders** table. It will:

    - add a `fulfilled_at` column
    - backfill it from `shipments`
    - drop the legacy `status_text` column

    Estimated downtime is ~2 minutes. See [the plan](https://example.com/plan) for details.

    ```sql
    ALTER TABLE orders ADD COLUMN fulfilled_at TIMESTAMP;
    ```
""".trimIndent()

private val pendingSingle = LoopbackRequest(
    id = "7Xk2mQ9pLw",
    createdAt = ago(Duration.ofMinutes(3)),
    status = "pending",
    title = "Run the orders migration now?",
    context = sampleContext,
    options = listOf(
        RequestOption("Yes, go ahead", "Runs immediately against production"),
        RequestOption("Schedule for tonight", "Queues it for the 02:00 maintenance window"),
        RequestOption("No"),
    ),
    multiSelect = false,
    allowFreeText = true,
    source = "Claude Code · loopback",
)

private val pendingMulti = LoopbackRequest(
    id = "Bq4nRt8vZc",
    createdAt = ago(Duration.ofHours(2)),
    status = "pending",
    title = "Which platforms should the release notes mention?",
    context = "The changelog for **v0.2** is drafted. Pick everything that shipped.",
    options = listOf(
        RequestOption("Android"),
        RequestOption("Server"),
        RequestOption("CLI"),
        RequestOption("Docs"),
    ),
    multiSelect = true,
    allowFreeText = false,
    source = "Codex",
)

private val pendingFreeTextOnly = LoopbackRequest(
    id = "Hs6dWe1yUa",
    createdAt = ago(Duration.ofSeconds(20)),
    status = "pending",
    title = "What should the new config key be called?",
    context = null,
    options = emptyList(),
    allowFreeText = true,
    source = "Claude Code",
)

private val answered = LoopbackRequest(
    id = "Pz3cVb5nMk",
    createdAt = ago(Duration.ofDays(1)),
    status = "answered",
    title = "Delete the stale feature branches?",
    context = "There are 14 branches with no commits in 90 days.",
    options = listOf(RequestOption("Delete all"), RequestOption("Keep them")),
    allowFreeText = true,
    source = "Claude Code · cleanup",
    answer = Answer(
        selected = listOf("Delete all"),
        text = "But leave anything prefixed release/",
        answeredAt = ago(Duration.ofHours(23)),
    ),
)

private val cancelled = LoopbackRequest(
    id = "Gf9hJk2lQw",
    createdAt = ago(Duration.ofDays(9)),
    status = "cancelled",
    title = "Approve the dependency bump?",
    context = "Bumping OkHttp 4.12 → 5.0.",
    options = listOf(RequestOption("Approve"), RequestOption("Reject")),
    source = "Dependabot agent",
)

private val samplePending = listOf(pendingSingle, pendingMulti, pendingFreeTextOnly)
private val sampleHistory = listOf(answered, cancelled)

@Composable
private fun PreviewTheme(content: @Composable () -> Unit) {
    LoopbackTheme(dynamicColor = false) {
        Surface(content = content)
    }
}

// ---- Inbox ------------------------------------------------------------------------------------

@PreviewLightDark
@Composable
private fun InboxPendingPreview() = PreviewTheme {
    InboxContent(
        pending = samplePending,
        history = sampleHistory,
        loading = false,
        error = null,
        onClearError = {},
        onRefresh = {},
        onOpenSettings = {},
        onOpenRequest = {},
    )
}

@Preview
@Composable
private fun InboxHistoryPreview() = PreviewTheme {
    InboxContent(
        pending = samplePending,
        history = sampleHistory,
        loading = false,
        error = null,
        onClearError = {},
        onRefresh = {},
        onOpenSettings = {},
        onOpenRequest = {},
        initialTab = 1,
    )
}

@Preview
@Composable
private fun InboxEmptyPreview() = PreviewTheme {
    InboxContent(
        pending = emptyList(),
        history = emptyList(),
        loading = false,
        error = null,
        onClearError = {},
        onRefresh = {},
        onOpenSettings = {},
        onOpenRequest = {},
    )
}

@Preview
@Composable
private fun InboxRefreshingPreview() = PreviewTheme {
    InboxContent(
        pending = samplePending,
        history = emptyList(),
        loading = true,
        error = null,
        onClearError = {},
        onRefresh = {},
        onOpenSettings = {},
        onOpenRequest = {},
    )
}

@PreviewLightDark
@Composable
private fun RequestCardPreview() = PreviewTheme {
    RequestCard(answered, onClick = {})
}

// ---- Request ----------------------------------------------------------------------------------

@PreviewLightDark
@Composable
private fun RequestSingleSelectPreview() = PreviewTheme {
    RequestContent(
        request = pendingSingle,
        loading = false,
        submitting = false,
        onBack = {},
        onSubmit = { _, _, _ -> null },
    )
}

@Preview
@Composable
private fun RequestMultiSelectPreview() = PreviewTheme {
    RequestContent(
        request = pendingMulti,
        loading = false,
        submitting = false,
        onBack = {},
        onSubmit = { _, _, _ -> null },
    )
}

@Preview
@Composable
private fun RequestFreeTextOnlyPreview() = PreviewTheme {
    RequestContent(
        request = pendingFreeTextOnly,
        loading = false,
        submitting = false,
        onBack = {},
        onSubmit = { _, _, _ -> null },
    )
}

@Preview
@Composable
private fun RequestSubmittingPreview() = PreviewTheme {
    RequestContent(
        request = pendingSingle,
        loading = false,
        submitting = true,
        onBack = {},
        onSubmit = { _, _, _ -> null },
    )
}

@PreviewLightDark
@Composable
private fun RequestAnsweredPreview() = PreviewTheme {
    RequestContent(
        request = answered,
        loading = false,
        submitting = false,
        onBack = {},
        onSubmit = { _, _, _ -> null },
    )
}

@Preview
@Composable
private fun RequestCancelledPreview() = PreviewTheme {
    RequestContent(
        request = cancelled,
        loading = false,
        submitting = false,
        onBack = {},
        onSubmit = { _, _, _ -> null },
    )
}

@Preview
@Composable
private fun RequestLoadingPreview() = PreviewTheme {
    RequestContent(
        request = null,
        loading = true,
        submitting = false,
        onBack = {},
        onSubmit = { _, _, _ -> null },
    )
}

@Preview
@Composable
private fun RequestNotFoundPreview() = PreviewTheme {
    RequestContent(
        request = null,
        loading = false,
        submitting = false,
        onBack = {},
        onSubmit = { _, _, _ -> null },
    )
}

// ---- Settings ---------------------------------------------------------------------------------

@PreviewLightDark
@Composable
private fun SettingsConfiguredPreview() = PreviewTheme {
    SettingsContent(
        initialUrl = "https://loopback.example.com",
        initialKey = "app_live_8f3a2c",
        configured = true,
        firebaseAvailable = true,
        pushToken = "dXkq8R2tQmS9vLpZ3nHbYw:APA91bF…",
        deviceName = "Google Pixel 8",
        onBack = {},
        onSave = { _, _ -> null },
        onRegisterDevice = { null },
    )
}

@Preview
@Composable
private fun SettingsFirstRunPreview() = PreviewTheme {
    SettingsContent(
        initialUrl = "",
        initialKey = "",
        configured = false,
        firebaseAvailable = true,
        pushToken = null,
        deviceName = "Google Pixel 8",
        onBack = {},
        onSave = { _, _ -> null },
        onRegisterDevice = { null },
    )
}

@Preview
@Composable
private fun SettingsNoFirebasePreview() = PreviewTheme {
    SettingsContent(
        initialUrl = "https://loopback.example.com",
        initialKey = "app_live_8f3a2c",
        configured = true,
        firebaseAvailable = false,
        pushToken = null,
        deviceName = "Google Pixel 8",
        onBack = {},
        onSave = { _, _ -> null },
        onRegisterDevice = { null },
    )
}

// ---- Markdown ---------------------------------------------------------------------------------

@PreviewLightDark
@Composable
private fun MarkdownPreview() = PreviewTheme {
    MarkdownText(
        """
            # Heading

            Paragraph with **bold**, _italic_, `code` and a [link](https://example.com).

            - bullet one
            - bullet two

            1. first
            2. second

            > A quote

            ```
            fenced code
            ```
        """.trimIndent(),
        modifier = Modifier.padding(16.dp),
    )
}
