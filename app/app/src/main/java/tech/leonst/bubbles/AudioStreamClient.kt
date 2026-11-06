package tech.leonst.bubbles

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.util.concurrent.TimeUnit

class AudioStreamClient(private val heartbeatClient: HeartbeatClient) {
    private val streamClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var outputJob: Job? = null
    private var inputJob: Job? = null
    private var inputChannel: Channel<ByteArray>? = null
    private var audioTrack: AudioTrack? = null

    private val outputSampleRate = 24000

    // Start receiving playing from /api/stream/output
    fun startOutputStream(scope: CoroutineScope) {
        if (outputJob?.isActive == true) return

        outputJob = scope.launch(Dispatchers.IO) {
            val track = createAudioTrack()
            audioTrack = track
            track.play()

            val buffer = ByteArray(4096)

            try {
                while (isActive) {
                    var connected = false
                    for (base in heartbeatClient.getTargetUrls()) {
                        if (!isActive) break
                        val url = "${base.trimEnd('/')}/api/stream/output"
                        try {
                            val request = Request.Builder()
                                .url(url)
                                .get()
                                .build()

                            streamClient.newCall(request).execute().use { response ->
                                if (response.isSuccessful) {
                                    connected = true
                                    heartbeatClient.setBaseUrl(base)
                                    Log.d("AudioStreamClient", "Connected to output stream: $url")
                                    val byteStream = response.body?.byteStream() ?: return@use
                                    while (isActive) {
                                        val read = byteStream.read(buffer)
                                        if (read == -1) break
                                        if (read > 0) {
                                            track.write(buffer, 0, read)
                                        }
                                    }
                                }
                            }
                            if (connected) break
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w("AudioStreamClient", "Output stream failed for $url: ${e.message}")
                        }
                    }
                    delay(1500)
                }
            } finally {
                try {
                    track.stop()
                    track.release()
                } catch (e: Exception) {
                    Log.e("AudioStreamClient", "Error releasing AudioTrack", e)
                }
                audioTrack = null
            }
        }
    }

    // Start streaming mic to /api/stream/input
    fun startInputStream(scope: CoroutineScope) {
        stopInputStream()

        val channel = Channel<ByteArray>(capacity = 64)
        inputChannel = channel

        inputJob = scope.launch(Dispatchers.IO) {
            for (base in heartbeatClient.getTargetUrls()) {
                if (!isActive) break
                val url = "${base.trimEnd('/')}/api/stream/input"
                try {
                    val requestBody = object : RequestBody() {
                        override fun contentType() = "audio/pcm;rate=16000".toMediaType()

                        override fun writeTo(sink: BufferedSink) {
                            runBlocking {
                                for (chunk in channel) {
                                    sink.write(chunk)
                                    sink.flush()
                                }
                            }
                        }
                    }

                    val request = Request.Builder()
                        .url(url)
                        .post(requestBody)
                        .build()

                    streamClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            heartbeatClient.setBaseUrl(base)
                            Log.d("AudioStreamClient", "Finished input stream to $url")
                            return@launch
                        } else {
                            Log.w("AudioStreamClient", "Input stream returned ${response.code} from $url")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("AudioStreamClient", "Input stream failed for $url: ${e.message}")
                }
            }
        }
    }

    // Push a chunk
    fun sendAudioChunk(pcmBytes: ByteArray) {
        inputChannel?.trySend(pcmBytes)
    }


    fun stopInputStream() {
        inputChannel?.close()
        inputChannel = null
        inputJob = null
    }

    // Stop all input and output
    fun stopAll() {
        stopInputStream()
        outputJob?.cancel()
        outputJob = null
    }

    private fun createAudioTrack(): AudioTrack {
        val minBufferSize = AudioTrack.getMinBufferSize(
            outputSampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = if (minBufferSize > 0) maxOf(minBufferSize * 2, 4096) else 4096

        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(outputSampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }
}
