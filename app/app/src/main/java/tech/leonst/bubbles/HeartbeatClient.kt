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
    private var baseUrl: String = "http://10.0.2.2:8000",
    private val intervalMillis: Long = 5000L
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private var heartbeatJob: Job? = null

    // Update base url at runtime if needed
    fun setBaseUrl(url: String) {
        baseUrl = url
    }

    // Start periodic heartbeat
    fun start(scope: CoroutineScope) {
        if (heartbeatJob?.isActive == true) return

        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                sendHeartbeat()
                delay(intervalMillis)
            }
        }
    }

    // Stop periodic heartbeat
    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    // Send single heartbeat request
    suspend fun sendHeartbeat(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "${baseUrl.trimEnd('/')}/heartbeat"
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                Log.d("HeartbeatClient", "Heartbeat sent to $url, response: ${response.code}")
                response.isSuccessful
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("HeartbeatClient", "Heartbeat failed: ${e.message}")
            false
        }
    }
}
