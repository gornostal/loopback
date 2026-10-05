package name.gornostal.loopback.api

import kotlinx.serialization.Serializable

@Serializable
data class RequestOption(
    val label: String,
    val description: String? = null,
)

@Serializable
data class Answer(
    val selected: List<String> = emptyList(),
    val text: String? = null,
    val answeredAt: String? = null,
)

@Serializable
data class LoopbackRequest(
    val id: String,
    val createdAt: String,
    /** `ask` (wants an answer) or `notify` (one-way message, already resolved as `notified`). */
    val kind: String = "ask",
    val status: String,
    val title: String,
    val context: String? = null,
    val options: List<RequestOption> = emptyList(),
    val multiSelect: Boolean = false,
    val allowFreeText: Boolean = true,
    val source: String? = null,
    val answer: Answer? = null,
) {
    val isPending: Boolean get() = status == "pending"
    val isNotification: Boolean get() = kind == "notify"
}

@Serializable
data class RequestList(val requests: List<LoopbackRequest>)

@Serializable
data class AnswerBody(val selected: List<String>, val text: String? = null)

@Serializable
data class RegisterDeviceBody(val token: String, val platform: String = "android", val name: String? = null)

@Serializable
data class ErrorBody(val error: String? = null)
