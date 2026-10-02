package name.gornostal.loopback.push

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** In-process signal so an open inbox refreshes when a push arrives. */
object PushEvents {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    fun emit(requestId: String) {
        _events.tryEmit(requestId)
    }
}
