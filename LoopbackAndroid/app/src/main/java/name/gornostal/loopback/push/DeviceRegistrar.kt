package name.gornostal.loopback.push

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import name.gornostal.loopback.Settings
import name.gornostal.loopback.api.LoopbackApi
import java.net.HttpURLConnection
import java.net.URL

/** Fetches the FCM token and registers it with the configured Loopback server. */
object DeviceRegistrar {
    private const val TAG = "DeviceRegistrar"

    fun isFirebaseAvailable(context: Context): Boolean = FirebaseApp.getApps(context).isNotEmpty()

    suspend fun currentToken(context: Context): String? {
        if (!isFirebaseAvailable(context)) return null
        return try {
            withTimeout(30_000) {
                suspendCancellableCoroutine { cont ->
                    FirebaseMessaging.getInstance().token
                        .addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { cont.resumeWithException(it) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "FCM token fetch failed: ${e.message}")
            probeInstallationsApi(context)
            throw e
        }
    }

    /**
     * Diagnostic: calls the Firebase Installations endpoint the SDK uses and logs the raw outcome.
     * The SDK swallows IOExceptions, so this is the only way to see *why* registration fails.
     */
    private suspend fun probeInstallationsApi(context: Context) = withContext(Dispatchers.IO) {
        val options = FirebaseApp.getInstance().options
        val url = "https://firebaseinstallations.googleapis.com/v1/projects/${options.projectId}/installations"
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", options.apiKey)
            conn.setRequestProperty("X-Android-Package", context.packageName)
            val body = """{"fid":"probe-${System.currentTimeMillis()}","appId":"${options.applicationId}","authVersion":"FIS_v2","sdkVersion":"a:18.0.0"}"""
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
            Log.w(TAG, "FIS probe → HTTP $code ${text.take(300).replace('\n', ' ')}")
        } catch (e: Exception) {
            Log.w(TAG, "FIS probe failed with ${e.javaClass.name}: ${e.message}", e)
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
