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
    const val EXTRA_REQUEST_ID = "requestId"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_REQUESTS,
            "Agent requests",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Questions from AI agents waiting for your answer"
        }
        manager.createNotificationChannel(channel)
    }

    fun showRequest(context: Context, requestId: String, title: String, body: String, source: String?) {
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
        val notification = NotificationCompat.Builder(context, CHANNEL_REQUESTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body.ifBlank { "Tap to answer" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.ifBlank { "Tap to answer" }))
            .setSubText(source?.takeIf { it.isNotBlank() })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
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
