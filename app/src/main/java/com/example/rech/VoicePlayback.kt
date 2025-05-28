// VoicePlayback.kt

package com.example.rech

import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

class VoicePlayback {

    private var mediaPlayer: MediaPlayer? = null
    private var currentPlayingFilePath: String? = null

    // For playback progress
    private val _currentPosition = MutableStateFlow(0)
    val currentPosition: StateFlow<Int> = _currentPosition

    private val _duration = MutableStateFlow(0)
    val duration: StateFlow<Int> = _duration

    private var playbackProgressJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    var onPlaybackCompleteListener: (() -> Unit)? = null // Callback for UI


    fun startPlayback(filePath: String) {
        // Stop any currently playing audio first
        stopPlayback()

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(filePath)
                prepareAsync() // Prepare asynchronously to prevent UI freezing
                setOnPreparedListener { mp ->
                    // Set up completion listener AFTER preparing
                    mp.setOnCompletionListener {
                        Log.d("VoicePlayback", "Playback completed for: $filePath")
                        stopPlayback() // Stop and release when done
                        onPlaybackCompleteListener?.invoke() // Notify UI
                    }
                    mp.start() // Start playback once prepared
                    currentPlayingFilePath = filePath
                    _duration.value = mp.duration
                    _currentPosition.value = mp.currentPosition
                    startProgressUpdate() // Start updating progress

                    Log.d("VoicePlayback", "Playback started for: $filePath")
                }
                setOnErrorListener { _, what, extra ->
                    Log.e("VoicePlayback", "MediaPlayer error: what=$what, extra=$extra")
                    stopPlayback()
                    onPlaybackCompleteListener?.invoke() // Notify UI even on error
                    true // Return true to indicate the error was handled
                }
            }
        } catch (e: IOException) {
            Log.e("VoicePlayback", "Error setting data source or preparing: ${e.message}")
            stopPlayback()
        } catch (e: IllegalArgumentException) {
            Log.e("VoicePlayback", "Illegal argument for data source: ${e.message}")
            stopPlayback()
        } catch (e: IllegalStateException) {
            Log.e("VoicePlayback", "Illegal state for media player: ${e.message}")
            stopPlayback()
        }
    }

    fun stopPlayback() {
        playbackProgressJob?.cancel() // Stop progress updates
        mediaPlayer?.apply {
            if (isPlaying) {
                stop() // Stop playback
            }
            release() // Release resources
            Log.d("VoicePlayback", "Playback stopped and released.")
        }
        mediaPlayer = null // Clear the reference
        currentPlayingFilePath = null
        _currentPosition.value = 0
        _duration.value = 0
    }

    fun isPlaying(): Boolean {
        return mediaPlayer?.isPlaying ?: false
    }

    fun isPlaying(filePath: String): Boolean {
        return isPlaying() && currentPlayingFilePath == filePath
    }

    fun seekTo(positionMillis: Int) {
        mediaPlayer?.seekTo(positionMillis)
        _currentPosition.value = positionMillis // Update UI immediately
    }

    private fun startProgressUpdate() {
        playbackProgressJob?.cancel() // Cancel any existing job
        playbackProgressJob = scope.launch {
            while (isActive && mediaPlayer != null && mediaPlayer!!.isPlaying) {
                _currentPosition.value = mediaPlayer!!.currentPosition
                delay(50L) // Update every 50ms for smoother progress
            }
            // Ensure position is updated to 0 if playback naturally stops
            if (! (mediaPlayer?.isPlaying ?: false)) {
                _currentPosition.value = 0
            }
        }
    }

    // Call this when the containing component (e.g., ViewModel or Activity) is destroyed
    fun release() {
        stopPlayback()
        scope.cancel() // Cancel the coroutine scope for good measure
    }
}