package tech.leonst.bubbles

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class HeartbeatClient(
    candidateUrls: List<String> = listOf(
        "http://127.0.0.1:8000",
        "http://192.168.55.127:8000",
        "http://10.0.2.2:8000"
    ),
    private val intervalMillis: Long = 5000L
) {
    private val candidateUrls = candidateUrls.toMutableList()
    private var activeBaseUrl: String? = null
    private var heartbeatJob: Job? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    // Override or set primary base URL
    fun setBaseUrl(url: String) {
        activeBaseUrl = url.trimEnd('/')
    }

    // Start periodic heartbeat requests
    fun start(scope: CoroutineScope) {
        if (heartbeatJob?.isActive == true) return

        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                sendHeartbeat()
                delay(intervalMillis)
            }
        }
    }

    // Stop periodic heartbeat requests
    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    // Send single heartbeat check
    suspend fun sendHeartbeat(): Boolean = withContext(Dispatchers.IO) {
        val targets = activeBaseUrl?.let { listOf(it) } ?: candidateUrls

        for (base in targets) {
            val url = "${base.trimEnd('/')}/heartbeat"
            try {
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        Log.d("HeartbeatClient", "Heartbeat success from $url: $body")
                        activeBaseUrl = base
                        return@withContext true
                    } else {
                        Log.w("HeartbeatClient", "Heartbeat received ${response.code} from $url")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("HeartbeatClient", "Heartbeat failed for $url: ${e.message}")
            }
        }
        false
    }
}
