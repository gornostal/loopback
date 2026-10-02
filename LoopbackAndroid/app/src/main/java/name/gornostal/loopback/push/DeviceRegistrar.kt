package name.gornostal.loopback.push

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import name.gornostal.loopback.Settings
import name.gornostal.loopback.api.LoopbackApi

/** Fetches the FCM token and registers it with the configured Loopback server. */
object DeviceRegistrar {
    private const val TAG = "DeviceRegistrar"

    fun isFirebaseAvailable(context: Context): Boolean = FirebaseApp.getApps(context).isNotEmpty()

    suspend fun currentToken(context: Context): String? {
        if (!isFirebaseAvailable(context)) return null
        return suspendCancellableCoroutine { cont ->
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
    }

    /**
     * Registers [token] (or the current one) with the server. Returns null on success,
     * or a human-readable reason it didn't happen.
     */
    suspend fun register(context: Context, token: String? = null, force: Boolean = false): String? {
        val settings = Settings(context)
        if (!settings.isConfigured) return "Server not configured"
        val fcmToken = try {
            token ?: currentToken(context) ?: return "Firebase is not configured in this build"
        } catch (e: Exception) {
            Log.w(TAG, "Could not get FCM token", e)
            return "Could not get push token: ${e.message}"
        }
        if (!force && settings.registeredToken == fcmToken) return null
        return try {
            LoopbackApi(settings.serverUrl, settings.apiKey).registerDevice(fcmToken, deviceName())
            settings.registeredToken = fcmToken
            Log.i(TAG, "Registered push token with server")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Device registration failed", e)
            "Registration failed: ${e.message}"
        }
    }

    fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
}
