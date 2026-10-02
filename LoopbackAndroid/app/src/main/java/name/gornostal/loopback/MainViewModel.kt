package name.gornostal.loopback

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import name.gornostal.loopback.api.LoopbackApi
import name.gornostal.loopback.api.LoopbackRequest
import name.gornostal.loopback.push.DeviceRegistrar
import name.gornostal.loopback.push.Notifications
import name.gornostal.loopback.push.PushEvents

/** Tabs in the bottom navigation bar. */
enum class HomeTab { Pending, History, Settings }

sealed interface Screen {
    /** The tabbed home shell; which tab is showing lives in [MainViewModel.tab]. */
    data object Home : Screen
    data class Request(val id: String) : Screen
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val settings = Settings(app)

    var screen: Screen by mutableStateOf(Screen.Home)
        private set
    var tab: HomeTab by mutableStateOf(if (settings.isConfigured) HomeTab.Pending else HomeTab.Settings)
        private set

    var pending: List<LoopbackRequest> by mutableStateOf(emptyList())
        private set
    var history: List<LoopbackRequest> by mutableStateOf(emptyList())
        private set
    var loading: Boolean by mutableStateOf(false)
        private set
    var error: String? by mutableStateOf(null)
        private set

    /** Request currently open in the detail screen (may be fetched on demand from a push). */
    var openRequest: LoopbackRequest? by mutableStateOf(null)
        private set
    var openLoading: Boolean by mutableStateOf(false)
        private set
    var submitting: Boolean by mutableStateOf(false)
        private set

    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            PushEvents.events.collect {
                refresh()
                val open = openRequest
                if (open != null && open.id == it) loadRequest(it)
            }
        }
        if (settings.isConfigured) {
            refresh()
            viewModelScope.launch { DeviceRegistrar.register(getApplication()) }
        }
    }

    private fun api(): LoopbackApi? =
        if (settings.isConfigured) LoopbackApi(settings.serverUrl, settings.apiKey) else null

    /** Back to the home shell on whatever tab was last selected. */
    fun showHome() {
        screen = Screen.Home
        openRequest = null
    }

    fun showInbox() {
        tab = HomeTab.Pending
        showHome()
    }

    fun showSettings() {
        tab = HomeTab.Settings
        showHome()
    }

    fun selectTab(selected: HomeTab) {
        tab = selected
    }

    fun openRequest(id: String) {
        screen = Screen.Request(id)
        openRequest = (pending + history).firstOrNull { it.id == id }
        loadRequest(id)
    }

    fun refresh() {
        val api = api() ?: return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            loading = true
            error = null
            try {
                pending = api.listRequests("pending")
                history = api.listRequests("all").filter { !it.isPending }
            } catch (e: Exception) {
                error = e.message ?: "Could not reach server"
            } finally {
                loading = false
            }
        }
    }

    private fun loadRequest(id: String) {
        val api = api() ?: return
        viewModelScope.launch {
            openLoading = true
            try {
                openRequest = api.getRequest(id)
            } catch (e: Exception) {
                error = e.message ?: "Could not load request"
            } finally {
                openLoading = false
            }
        }
    }

    /** Submits the answer; returns an error message or null on success. */
    suspend fun submitAnswer(id: String, selected: List<String>, text: String?): String? {
        val api = api() ?: return "Server not configured"
        submitting = true
        return try {
            val updated = api.answer(id, selected, text?.takeIf { it.isNotBlank() })
            openRequest = updated
            Notifications.dismiss(getApplication(), id)
            refresh()
            null
        } catch (e: Exception) {
            e.message ?: "Could not submit"
        } finally {
            submitting = false
        }
    }

    /** Persists settings and verifies them against the server. Returns an error or null. */
    suspend fun saveSettings(url: String, key: String): String? {
        settings.serverUrl = url
        settings.apiKey = key
        settings.registeredToken = null
        val api = api() ?: return "Fill in both fields"
        return try {
            api.ping()
            refresh()
            DeviceRegistrar.register(getApplication(), force = true)
        } catch (e: Exception) {
            e.message ?: "Could not reach server"
        }
    }

    suspend fun registerDevice(): String? = DeviceRegistrar.register(getApplication(), force = true)

    fun clearError() {
        error = null
    }
}
