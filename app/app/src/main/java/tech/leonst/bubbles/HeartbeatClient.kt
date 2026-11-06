package tech.leonst.bubbles

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class HeartbeatClient(
    candidateUrls: List<String> = listOf(
        "http://${ServerPreferences.DEFAULT_IP}:${ServerPreferences.DEFAULT_PORT}",
    ),
    private val intervalMillis: Long = 5000L
) {
    private val candidateUrls = candidateUrls.toMutableList()
    private var activeBaseUrl: String? = null
    private var heartbeatJob: Job? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    @Volatile
    var lastHeartbeatSuccess: Boolean = false
        private set

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    // set primary base URL
    fun setBaseUrl(url: String) {
        activeBaseUrl = url.trimEnd('/')
    }

    // Set and replace candidate base URL
    fun updateBaseUrl(url: String) {
        val normalized = url.trimEnd('/')
        activeBaseUrl = normalized
        candidateUrls.clear()
        candidateUrls.add(normalized)
    }

    // Return active base URL if known, followed by candidate URLs
    fun getTargetUrls(): List<String> {
        val active = activeBaseUrl
        return if (active != null) {
            listOf(active) + candidateUrls.filter { it != active }
        } else {
            candidateUrls.toList()
        }
    }

    // Start heartbeat requests
    fun start(scope: CoroutineScope) {
        if (heartbeatJob?.isActive == true) return

        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                sendHeartbeat()
                delay(intervalMillis)
            }
        }
    }

    // Stop heartbeat requests
    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    // Send heartbeat check
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
                        lastHeartbeatSuccess = true
                        _isConnected.value = true
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
        lastHeartbeatSuccess = false
        _isConnected.value = false
        false
    }
}
