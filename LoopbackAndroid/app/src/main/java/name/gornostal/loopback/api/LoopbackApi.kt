package name.gornostal.loopback.api

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class ApiException(val code: Int, message: String) : IOException(message)

/** Thin client for the Loopback server's REST API. */
class LoopbackApi(private val baseUrl: String, private val apiKey: String) {

    suspend fun listRequests(status: String): List<LoopbackRequest> =
        get("/api/requests?status=$status&limit=200").let { json.decodeFromString<RequestList>(it).requests }

    suspend fun getRequest(id: String): LoopbackRequest =
        json.decodeFromString(get("/api/requests/$id"))

    suspend fun answer(id: String, selected: List<String>, text: String?): LoopbackRequest =
        json.decodeFromString(post("/api/requests/$id/answer", json.encodeToString(AnswerBody(selected, text))))

    suspend fun registerDevice(token: String, name: String) {
        post("/api/devices", json.encodeToString(RegisterDeviceBody(token = token, name = name)))
    }

    /** Cheap authenticated call used by "Test connection". */
    suspend fun ping() {
        get("/api/requests?status=pending&limit=1")
    }

    private suspend fun get(path: String): String = execute(Request.Builder().url(baseUrl + path).get())

    private suspend fun post(path: String, body: String): String =
        execute(Request.Builder().url(baseUrl + path).post(body.toRequestBody(JSON_MEDIA)))

    private suspend fun execute(builder: Request.Builder): String {
        val request = builder.header("Authorization", "Bearer $apiKey").build()
        return client.newCall(request).await().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { json.decodeFromString<ErrorBody>(text).error }.getOrNull()
                throw ApiException(response.code, message ?: "HTTP ${response.code}")
            }
            text
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        private val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }

        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { cancel() }
}
