package name.gornostal.loopback.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import name.gornostal.loopback.MainActivity
import name.gornostal.loopback.R

object Notifications {
    const val CHANNEL_REQUESTS = "requests"
    const val CHANNEL_NOTICES = "notices"
    const val EXTRA_REQUEST_ID = "requestId"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val requests = NotificationChannel(
            CHANNEL_REQUESTS,
            "Agent requests",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Questions from AI agents waiting for your answer"
        }
        // Separate channel so one-way notices can be quieter (or muted) without silencing questions.
        val notices = NotificationChannel(
            CHANNEL_NOTICES,
            "Agent notifications",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Updates from AI agents that don't need an answer"
        }
        manager.createNotificationChannels(listOf(requests, notices))
    }

    /** A question: high priority, "Tap to answer". */
    fun showRequest(context: Context, requestId: String, title: String, body: String, source: String?) {
        show(context, CHANNEL_REQUESTS, requestId, title, body.ifBlank { "Tap to answer" }, source)
    }

    /** A one-way message: default priority, opens the full text in the app. */
    fun showNotice(context: Context, requestId: String, title: String, body: String, source: String?) {
        show(context, CHANNEL_NOTICES, requestId, title, body.ifBlank { "Tap to read" }, source)
    }

    private fun show(context: Context, channel: String, requestId: String, title: String, body: String, source: String?) {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_REQUEST_ID, requestId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pending = PendingIntent.getActivity(
            context,
            notificationId(requestId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSubText(source?.takeIf { it.isNotBlank() })
            .setPriority(if (channel == CHANNEL_REQUESTS) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(notificationId(requestId), notification)
    }

    fun dismiss(context: Context, requestId: String) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(requestId))
    }

    private fun notificationId(requestId: String): Int = requestId.hashCode()
}
