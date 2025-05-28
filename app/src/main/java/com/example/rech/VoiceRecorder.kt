// VoiceRecorder.kt

package com.example.rech

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class VoiceRecorder(
    private val context: Context
) {

    private var recorder: MediaRecorder? = null
    private var outputFilePath: String? = null

    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.NOT_RECORDING)
    val recordingState: StateFlow<RecordingState> = _recordingState

    private val _currentAmplitude = MutableStateFlow(0)
    val currentAmplitude: StateFlow<Int> = _currentAmplitude

    private var amplitudeJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)


    fun startRecording(): String? {
        // If already recording or paused, stop it first to ensure a clean start
        if (_recordingState.value != RecordingState.NOT_RECORDING) {
            stopRecording()
        }

        val timestamp = System.currentTimeMillis()
        val outputDir = context.getExternalFilesDir(null)
        val newOutputFile = File(outputDir, "REC_${timestamp}.mp3")
        outputFilePath = newOutputFile.absolutePath // Store the current recording path

        recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(96000)
            setAudioSamplingRate(44100)
            setOutputFile(outputFilePath) // Use the stored path

            try {
                prepare()
                start()
                _recordingState.value = RecordingState.RECORDING // Update state to RECORDING
                Log.d("VoiceRecorder", "Recording started: $outputFilePath")

                // Start amplitude update coroutine
                amplitudeJob = scope.launch {
                    while (isActive) {
                        // Only update amplitude if actively recording (not paused)
                        if (_recordingState.value == RecordingState.RECORDING) {
                            _currentAmplitude.value = recorder?.maxAmplitude ?: 0
                        } else {
                            _currentAmplitude.value = 0 // Reset amplitude if paused or stopped
                        }
                        delay(100L)
                    }
                }
                return outputFilePath
            } catch (e: IOException) {
                Log.e("VoiceRecorder", "prepare() failed: ${e.message}")
                stopRecording()
                return null
            } catch (e: IllegalStateException) {
                Log.e("VoiceRecorder", "start() failed, recorder in illegal state: ${e.message}")
                stopRecording()
                return null
            }
        }
    }

    fun pauseRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) { // API 24+
            if (_recordingState.value == RecordingState.RECORDING) { // Only pause if actively recording
                try {
                    recorder?.pause() // Correct: Call pause on the recorder
                    _recordingState.value = RecordingState.PAUSED // Update state to PAUSED
                    Log.d("VoiceRecorder", "Recording paused.")
                } catch (e: IllegalStateException) {
                    Log.e("VoiceRecorder", "pause() failed, recorder in illegal state: ${e.message}")
                }
            }
        } else {
            Log.w("VoiceRecorder", "Pause not supported on this API level. Stopping recording.")
            stopRecording() // Fallback for older APIs
        }
    }

    fun resumeRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) { // API 24+
            if (_recordingState.value == RecordingState.PAUSED) { // Only resume if currently paused
                try {
                    recorder?.resume() // Correct: Call resume on the recorder
                    _recordingState.value = RecordingState.RECORDING // Update state to RECORDING
                    Log.d("VoiceRecorder", "Recording resumed.")
                } catch (e: IllegalStateException) {
                    Log.e("VoiceRecorder", "resume() failed, recorder in illegal state: ${e.message}")
                }
            }
        } else {
            Log.w("VoiceRecorder", "Resume not supported on this API level.")
            // No action for resume on older APIs if it was stopped.
        }
    }

    fun stopRecording() {
        recorder?.apply {
            try {
                if (_recordingState.value == RecordingState.RECORDING || _recordingState.value == RecordingState.PAUSED) {
                    stop() // Stop recording
                }
                release() // Release recorder resources
                Log.d("VoiceRecorder", "Recording stopped and released.")
            } catch (e: IllegalStateException) {
                Log.e("VoiceRecorder", "stop() or release() failed, recorder in illegal state: ${e.message}")
            } catch (e: RuntimeException) { // Catch issues like no valid audio data
                Log.e("VoiceRecorder", "stop() or release() failed unexpectedly: ${e.message}")
            }
        }
        recorder = null // Clear the recorder reference
        outputFilePath = null // Clear the current file path

        // Stop amplitude update coroutine and reset amplitude
        amplitudeJob?.cancel()
        _currentAmplitude.value = 0
        _recordingState.value = RecordingState.NOT_RECORDING // Update state to NOT_RECORDING
    }

    fun getCurrentRecordingFilePath(): String? {
        return outputFilePath
    }
}

enum class RecordingState {
    NOT_RECORDING,
    RECORDING, // Actively recording
    PAUSED     // Recording is paused
}