package name.gornostal.loopback.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import name.gornostal.loopback.api.LoopbackRequest
import name.gornostal.loopback.ui.theme.Brand

/** Large screen heading used in place of a top app bar, with an optional subtitle. */
@Composable
fun ScreenHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 20.dp, bottom = 8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** Primary call-to-action painted with the logo's loop gradient. */
@Composable
fun AccentButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val brush: Brush = if (enabled) Brand.Loop else SolidColor(colors.surfaceContainerHigh)
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = Brand.Navy,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = colors.onSurfaceVariant,
        ),
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxSize().background(brush), contentAlignment = Alignment.Center) { content() }
    }
}

/** Round badge with the first letter of the agent/source name, on the loop gradient. */
@Composable
fun SourceAvatar(source: String?, size: Dp = 28.dp) {
    val initial = source?.trim()?.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
    Box(
        Modifier.size(size).background(Brand.Loop, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initial,
            style = if (size >= 36.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Brand.Navy,
        )
    }
}

/** Compact status label tinted by outcome: mint for answered, pink for cancelled. */
@Composable
fun StatusPill(request: LoopbackRequest) {
    val colors = MaterialTheme.colorScheme
    val (label, color) = when (request.status) {
        "answered" -> "Answered" to colors.tertiary
        "cancelled" -> "Cancelled" to colors.error
        "pending" -> "Pending" to colors.primary
        else -> request.status.replaceFirstChar { it.uppercase() } to colors.onSurfaceVariant
    }
    Surface(color = color.copy(alpha = 0.16f), contentColor = color, shape = CircleShape) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
