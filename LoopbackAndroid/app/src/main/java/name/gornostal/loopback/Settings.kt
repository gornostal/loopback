package name.gornostal.loopback

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Server connection settings. Stored in SharedPreferences; single-user app. */
class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("loopback", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_URL, "") ?: ""
        set(value) = prefs.edit { putString(KEY_URL, value.trim().trimEnd('/')) }

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit { putString(KEY_API_KEY, value.trim()) }

    /** Last FCM token we registered with the server, to avoid redundant calls. */
    var registeredToken: String?
        get() = prefs.getString(KEY_REGISTERED_TOKEN, null)
        set(value) = prefs.edit { putString(KEY_REGISTERED_TOKEN, value) }

    val isConfigured: Boolean get() = serverUrl.isNotBlank() && apiKey.isNotBlank()

    private companion object {
        const val KEY_URL = "server_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_REGISTERED_TOKEN = "registered_token"
    }
}
