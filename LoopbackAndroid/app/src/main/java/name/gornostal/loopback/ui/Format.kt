package name.gornostal.loopback.ui

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("MMM d, HH:mm")

/** "just now", "5m ago", "3h ago", or an absolute date for older items. */
fun relativeTime(iso: String, now: Instant = Instant.now()): String {
    val instant = runCatching { Instant.parse(iso) }.getOrNull() ?: return ""
    val age = Duration.between(instant, now)
    return when {
        age.toMinutes() < 1 -> "just now"
        age.toMinutes() < 60 -> "${age.toMinutes()}m ago"
        age.toHours() < 24 -> "${age.toHours()}h ago"
        age.toDays() < 7 -> "${age.toDays()}d ago"
        else -> timeFormat.format(instant.atZone(ZoneId.systemDefault()))
    }
}

fun absoluteTime(iso: String): String {
    val instant = runCatching { Instant.parse(iso) }.getOrNull() ?: return ""
    return timeFormat.format(instant.atZone(ZoneId.systemDefault()))
}

private val inlineMarkdown = Regex("""(\*\*|__|~~|`|\*|_)|\[([^\]]+)\]\([^)]*\)|^#{1,6}\s+|^[-*+>]\s+""", RegexOption.MULTILINE)

/** Flattens inline markdown for one-line previews: strips emphasis markers, keeps link text. */
fun stripInlineMarkdown(text: String): String =
    inlineMarkdown.replace(text) { m -> m.groupValues[2] }.trim()
