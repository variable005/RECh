// MainActivity.kt
package com.example.rech

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayCircle // Using PlayCircle for Resume
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rech.ui.theme.REChTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.collectAsState
import com.example.rech.RecordingState // This is the crucial import
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            REChTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    RecorderScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun RecorderScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    val vibrator = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    val recordAudioPermissionState = rememberPermissionState(
        permission = Manifest.permission.RECORD_AUDIO
    )

    val voiceRecorder = remember { VoiceRecorder(context) }
    val currentRecordingState by voiceRecorder.recordingState.collectAsState()
    val isRecording = currentRecordingState == RecordingState.RECORDING
    val isRecordingPaused = currentRecordingState == RecordingState.PAUSED


    var recordingDurationMillis by remember { mutableLongStateOf(0L) }
    var startTimeMillis by remember { mutableLongStateOf(0L) }
    var pauseOffset by remember { mutableLongStateOf(0L) }


    // State to track which file is currently playing (if any)
    // This needs to be mutableStateOf, not derived from voicePlayback directly for icon changes
    var currentPlayingFile by remember { mutableStateOf<String?>(null) }


    var showDeleteConfirmationDialog by remember { mutableStateOf(false) }
    var recordingToDelete by remember { mutableStateOf<Recording?>(null) }

    var showRenameDialog by remember { mutableStateOf(false) }
    var recordingToRename by remember { mutableStateOf<Recording?>(null) }
    var newFileNameInput by remember { mutableStateOf("") }


    val voicePlayback = remember { VoicePlayback() }


    val recordings = remember { mutableStateListOf<Recording>() }

    val readRecordings: () -> Unit = remember {
        {
            val outputDir = context.getExternalFilesDir(null)
            val files = outputDir?.listFiles()?.filter { it.isFile && it.extension == "mp3" }
            val newRecordings = files?.map { file ->
                Recording(
                    name = file.name,
                    filePath = file.absolutePath,
                    size = file.length(),
                    lastModified = file.lastModified()
                )
            }?.sortedByDescending { it.lastModified }
                ?: emptyList()

            recordings.clear()
            recordings.addAll(newRecordings)
        }
    }

    val deleteRecording: (Recording) -> Unit = { record ->
        val fileToDelete = File(record.filePath)
        if (fileToDelete.exists()) {
            val success = fileToDelete.delete()
            if (success) {
                Toast.makeText(context, "Deleted: ${record.name}", Toast.LENGTH_SHORT).show()
                if (currentPlayingFile == record.filePath) {
                    voicePlayback.stopPlayback()
                    currentPlayingFile = null
                }
                readRecordings()
            } else {
                Toast.makeText(context, "Failed to delete: ${record.name}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "File not found: ${record.name}", Toast.LENGTH_SHORT).show()
        }
        recordingToDelete = null
        showDeleteConfirmationDialog = false
    }

    val renameRecording: (Recording, String) -> Unit = { record, newName ->
        if (newName.isBlank()) {
            Toast.makeText(context, "File name cannot be empty.", Toast.LENGTH_SHORT).show()
        } else {
            val sanitizedNewName = newName.replace("[\\\\/:*?\"<>|]".toRegex(), "")
            val originalFile = File(record.filePath)
            val extension = originalFile.extension
            val newFile = File(originalFile.parent, "$sanitizedNewName.$extension")

            if (originalFile.exists()) {
                if (newFile.exists() && newFile.absolutePath != originalFile.absolutePath) {
                    Toast.makeText(context, "A file with that name already exists.", Toast.LENGTH_SHORT).show()
                } else {
                    val success = originalFile.renameTo(newFile)
                    if (success) {
                        Toast.makeText(context, "Renamed to: ${newFile.name}", Toast.LENGTH_SHORT).show()
                        if (currentPlayingFile == record.filePath) {
                            currentPlayingFile = newFile.absolutePath
                        }
                        readRecordings()
                    } else {
                        Toast.makeText(context, "Failed to rename '${record.name}'. Ensure file is not in use.", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                Toast.makeText(context, "Original file not found: ${record.name}", Toast.LENGTH_SHORT).show()
            }
        }
        recordingToRename = null
        newFileNameInput = ""
        showRenameDialog = false
    }


    LaunchedEffect(Unit) {
        readRecordings()
        // Ensure this listener is set up correctly
        voicePlayback.onPlaybackCompleteListener = {
            currentPlayingFile = null // Reset currentPlayingFile when playback finishes
            // Vibrate on playback completion
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50)
            }
        }
    }

    LaunchedEffect(recordAudioPermissionState.status) {
        if (!recordAudioPermissionState.status.isGranted) {
            recordAudioPermissionState.launchPermissionRequest()
        }
    }

    LaunchedEffect(currentRecordingState) {
        when (currentRecordingState) {
            RecordingState.RECORDING -> {
                if (startTimeMillis == 0L) {
                    startTimeMillis = System.currentTimeMillis()
                    pauseOffset = 0L
                } else {
                    startTimeMillis = System.currentTimeMillis() - pauseOffset
                }
                while (isActive && currentRecordingState == RecordingState.RECORDING) {
                    delay(1000L)
                    recordingDurationMillis = System.currentTimeMillis() - startTimeMillis
                }
            }
            RecordingState.PAUSED -> {
                pauseOffset = System.currentTimeMillis() - startTimeMillis
            }
            RecordingState.NOT_RECORDING -> {
                recordingDurationMillis = 0L
                startTimeMillis = 0L
                pauseOffset = 0L
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(50.dp)) // Add this line

        Text(
            text = recordingDurationMillis.formatTime(),
            fontSize = 72.sp,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(64.dp))

        FloatingActionButton(
            onClick = {
                when {
                    recordAudioPermissionState.status.isGranted -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            @Suppress("DEPRECATION")
                            vibrator.vibrate(80)
                        }

                        // IMPORTANT: Always stop playback if recording starts or resumes
                        if (voicePlayback.isPlaying()) {
                            voicePlayback.stopPlayback()
                            currentPlayingFile = null // Ensure UI reflects no file playing
                        }

                        when (currentRecordingState) {
                            RecordingState.NOT_RECORDING -> {
                                voiceRecorder.startRecording()?.let { filePath ->
                                    Toast.makeText(context, "Recording started: $filePath", Toast.LENGTH_LONG).show()
                                } ?: run {
                                    Toast.makeText(context, "Failed to start recording.", Toast.LENGTH_SHORT).show()
                                }
                            }
                            RecordingState.RECORDING -> {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                    voiceRecorder.pauseRecording()
                                    Toast.makeText(context, "Recording paused.", Toast.LENGTH_SHORT).show()
                                } else {
                                    voiceRecorder.stopRecording()
                                    Toast.makeText(context, "Pause not supported. Recording stopped.", Toast.LENGTH_SHORT).show()
                                    readRecordings()
                                }
                            }
                            RecordingState.PAUSED -> {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                    voiceRecorder.resumeRecording()
                                    Toast.makeText(context, "Recording resumed.", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Resume not supported on this device.", Toast.LENGTH_SHORT).show()
                                    voiceRecorder.stopRecording()
                                    readRecordings()
                                }
                            }
                        }
                    }
                    recordAudioPermissionState.status.shouldShowRationale -> {
                        Toast.makeText(context, "Recording permission is essential for this app. Please grant it.", Toast.LENGTH_LONG).show()
                    }
                    else -> {
                        Toast.makeText(context, "Recording permission permanently denied. Please enable it in app settings.", Toast.LENGTH_LONG).show()
                    }
                }
            },
            modifier = Modifier.padding(16.dp)
        ) {
            val icon = when (currentRecordingState) {
                RecordingState.NOT_RECORDING -> Icons.Filled.Mic
                RecordingState.RECORDING -> Icons.Filled.Pause
                RecordingState.PAUSED -> Icons.Filled.PlayCircle
            }
            val contentDescription = when (currentRecordingState) {
                RecordingState.NOT_RECORDING -> "Start Recording"
                RecordingState.RECORDING -> "Pause Recording"
                RecordingState.PAUSED -> "Resume Recording"
            }
            Icon(imageVector = icon, contentDescription = contentDescription)
        }

        if (currentRecordingState != RecordingState.NOT_RECORDING) {
            FloatingActionButton(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(80)
                    }
                    voiceRecorder.stopRecording()
                    Toast.makeText(context, "Recording stopped.", Toast.LENGTH_SHORT).show()
                    readRecordings()
                },
                modifier = Modifier.padding(16.dp),
                containerColor = MaterialTheme.colorScheme.error
            ) {
                Icon(Icons.Filled.Stop, contentDescription = "Stop Recording")
            }
        }


        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Recordings:",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (recordings.isEmpty()) {
            Text(
                text = "No recordings yet. Tap the microphone to start!",
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                items(recordings) { recording ->
                    // Pass currentPlayingFile directly for the icon to change
                    // The isPlaying parameter here correctly triggers recomposition
                    RecordingItem(
                        recording = recording,
                        isPlaying = currentPlayingFile == recording.filePath, // Use currentPlayingFile state
                        voicePlayback = voicePlayback,
                        onPlaybackClick = { filePath ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                            } else {
                                @Suppress("DEPRECATION")
                                vibrator.vibrate(50)
                            }

                            // Stop recording if active before playback
                            if (currentRecordingState != RecordingState.NOT_RECORDING) {
                                voiceRecorder.stopRecording()
                                Toast.makeText(context, "Recording stopped to play audio.", Toast.LENGTH_SHORT).show()
                                readRecordings()
                            }

                            // Toggle playback for the specific file
                            if (currentPlayingFile == filePath) { // If this file is already playing
                                voicePlayback.stopPlayback()
                                currentPlayingFile = null // Reset currentPlayingFile
                            } else {
                                voicePlayback.startPlayback(filePath)
                                currentPlayingFile = filePath // Set currentPlayingFile to the new file
                            }
                        },
                        onDeleteClick = { record ->
                            recordingToDelete = record
                            showDeleteConfirmationDialog = true
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                            } else {
                                @Suppress("DEPRECATION")
                                vibrator.vibrate(50)
                            }
                        },
                        onRenameClick = { record ->
                            recordingToRename = record
                            newFileNameInput = record.name.substringBeforeLast(".")
                            showRenameDialog = true
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                            } else {
                                @Suppress("DEPRECATION")
                                vibrator.vibrate(50)
                            }
                        }
                    )
                }
            }
        }

        if (showDeleteConfirmationDialog && recordingToDelete != null) {
            AlertDialog(
                onDismissRequest = {
                    showDeleteConfirmationDialog = false
                    recordingToDelete = null
                },
                title = { Text("Confirm Deletion") },
                text = { Text("Are you sure you want to delete '${recordingToDelete?.name}'?") },
                confirmButton = {
                    TextButton(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            @Suppress("DEPRECATION")
                            vibrator.vibrate(50)
                        }
                        recordingToDelete?.let { deleteRecording(it) }
                    }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showDeleteConfirmationDialog = false
                        recordingToDelete = null
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            @Suppress("DEPRECATION")
                            vibrator.vibrate(30)
                        }
                    }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showRenameDialog && recordingToRename != null) {
            AlertDialog(
                onDismissRequest = {
                    showRenameDialog = false
                    recordingToRename = null
                    newFileNameInput = ""
                },
                title = { Text("Rename Recording") },
                text = {
                    TextField(
                        value = newFileNameInput,
                        onValueChange = { newFileNameInput = it },
                        label = { Text("New file name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            @Suppress("DEPRECATION")
                            vibrator.vibrate(50)
                        }
                        recordingToRename?.let { record ->
                            renameRecording(record, newFileNameInput)
                        }
                    }) {
                        Text("Rename")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showRenameDialog = false
                        recordingToRename = null
                        newFileNameInput = ""
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            @Suppress("DEPRECATION")
                            vibrator.vibrate(30)
                        }
                    }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun RecorderScreenPreview() {
    REChTheme {
        RecorderScreen()
    }
}

fun Long.formatTime(): String {
    val totalSeconds = this / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d:%02d", hours, minutes, seconds)
}

fun Int.formatTimeMillis(): String {
    val totalSeconds = this / 1000
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}

private val sizeFormat = DecimalFormat("#.##")
fun Long.formatFileSize(): String {
    if (this <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(this.toDouble()) / Math.log10(1024.0)).toInt()
    return sizeFormat.format(this / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
}

fun Long.formatDateTime(): String {
    val sdf = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(this))
}

@Composable
fun RecordingItem(
    recording: Recording,
    isPlaying: Boolean, // This parameter is now directly controlled by MainActivity's currentPlayingFile
    onPlaybackClick: (String) -> Unit,
    onDeleteClick: (Recording) -> Unit,
    onRenameClick: (Recording) -> Unit,
    voicePlayback: VoicePlayback // Still needed for Slider's currentPosition/duration
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = recording.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "Size: ${recording.size.formatFileSize()}", fontSize = 12.sp)
                    Text(text = "Recorded: ${recording.lastModified.formatDateTime()}", fontSize = 12.sp)
                }

                IconButton(
                    onClick = { onPlaybackClick(recording.filePath) },
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    // Icon logic now simply uses the `isPlaying` parameter received
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play"
                    )
                }

                IconButton(
                    onClick = { onDeleteClick(recording) },
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Delete Recording",
                        tint = MaterialTheme.colorScheme.error
                    )
                }

                IconButton(
                    onClick = { onRenameClick(recording) },
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Rename Recording",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Playback progress bar and duration display (only visible if this specific item is playing)
            // The Slider's visibility is now tied to the `isPlaying` state.
            if (isPlaying) {
                Spacer(modifier = Modifier.height(8.dp))
                // Only collect state if this item IS playing, to avoid unnecessary recompositions elsewhere
                val currentPosition by voicePlayback.currentPosition.collectAsState()
                val duration by voicePlayback.duration.collectAsState()

                Slider(
                    value = currentPosition.toFloat(),
                    onValueChange = { newValue ->
                        // This allows the user to drag the slider and see immediate feedback
                        // The actual seek will happen on onValueChangeFinished
                        voicePlayback.seekTo(newValue.toInt())
                    },
                    onValueChangeFinished = {
                        // User has finished dragging the slider, finalize the seek
                        // The seekTo in onValueChange already handles it for visual feedback
                    },
                    valueRange = 0f..duration.toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = currentPosition.formatTimeMillis(), fontSize = 12.sp)
                    Text(text = duration.formatTimeMillis(), fontSize = 12.sp)
                }
            }
        }
    }
}