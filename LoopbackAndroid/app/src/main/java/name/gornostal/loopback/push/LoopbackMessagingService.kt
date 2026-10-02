package name.gornostal.loopback.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LoopbackMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val requestId = data["requestId"] ?: return
        when (data["type"]) {
            "request" -> {
                Notifications.showRequest(
                    context = this,
                    requestId = requestId,
                    title = data["title"] ?: "New request",
                    body = data["body"].orEmpty(),
                    source = data["source"],
                )
                PushEvents.emit(requestId)
            }
            "cancelled" -> {
                Notifications.dismiss(this, requestId)
                PushEvents.emit(requestId)
            }
            else -> Log.w(TAG, "Unknown push type: ${data["type"]}")
        }
    }

    override fun onNewToken(token: String) {
        scope.launch { DeviceRegistrar.register(applicationContext, token = token, force = true) }
    }

    private companion object {
        const val TAG = "LoopbackMessaging"
    }
}
