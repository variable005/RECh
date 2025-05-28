package com.example.rech

import java.io.File

data class Recording(
    val name: String,     // e.g., "REC_20240528_103000.mp3"
    val filePath: String, // Full path to the file, e.g., "/storage/emulated/0/Android/data/com.example.rech/files/REC_..."
    val size: Long,       // File size in bytes
    val lastModified: Long // Last modified timestamp in milliseconds
) {
    // Optional: A helper to get the File object if needed
    val file: File
        get() = File(filePath)
}