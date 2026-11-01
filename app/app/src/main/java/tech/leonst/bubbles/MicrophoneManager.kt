package tech.leonst.bubbles

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class MicrophoneManager(private val context: Context) {
    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude = _amplitude.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun toggle(scope: CoroutineScope, onPermissionNeeded: () -> Unit) {
        if (_isRecording.value) {
            stop()
        } else {
            if (hasPermission()) {
                start(scope)
            } else {
                onPermissionNeeded()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        if (_isRecording.value) return
        if (!hasPermission()) return

        try {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = if (minBufferSize > 0) maxOf(minBufferSize, 2048) else 2048

            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("MicrophoneManager", "AudioRecord failed to initialize")
                record.release()
                return
            }

            record.startRecording()
            audioRecord = record
            _isRecording.value = true

            recordingJob = scope.launch(Dispatchers.IO) {
                val buffer = ShortArray(1024)
                while (isActive && _isRecording.value) {
                    val readCount = record.read(buffer, 0, buffer.size)
                    if (readCount > 0) {
                        var sum = 0.0
                        for (i in 0 until readCount) {
                            val sample = buffer[i].toDouble()
                            sum += sample * sample
                        }
                        val rms = sqrt(sum / readCount)
                        val normalized = (rms / 32767.0).toFloat().coerceIn(0f, 1f)
                        _amplitude.value = normalized
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MicrophoneManager", "Failed to start microphone", e)
            stop()
        }
    }

    fun stop() {
        _isRecording.value = false
        _amplitude.value = 0f
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.apply {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.e("MicrophoneManager", "Error stopping AudioRecord", e)
        } finally {
            audioRecord = null
        }
    }
}
