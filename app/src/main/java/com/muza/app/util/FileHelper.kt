package com.muza.app.util

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.IOException

object FileHelper {
    private const val TAG = "FileHelper"

    /**
     * Finds the MP3 file in [directory], copies it to the user's selected SAF [targetTreeUri],
     * and deletes the temporary directory and its contents.
     *
     * @return The final file name of the copied MP3, or null if failed.
     */
    fun moveMp3ToUserFolder(
        context: Context,
        tempDir: File,
        targetTreeUri: Uri
    ): String? {
        val mp3Files = tempDir.listFiles { _, name -> name.endsWith(".mp3", ignoreCase = true) }
        val sourceFile = mp3Files?.firstOrNull() ?: run {
            Log.e(TAG, "No MP3 file found in temp directory: ${tempDir.absolutePath}")
            return null
        }

        val fileName = sourceFile.name
        val treeDoc = DocumentFile.fromTreeUri(context, targetTreeUri)
        if (treeDoc == null || !treeDoc.canWrite()) {
            Log.e(TAG, "Cannot write to target tree URI: $targetTreeUri")
            return null
        }

        // Create new MP3 file in destination tree
        val targetFileDoc = treeDoc.createFile("audio/mpeg", fileName)
        if (targetFileDoc == null) {
            Log.e(TAG, "Failed to create target document in $targetTreeUri")
            return null
        }

        return try {
            context.contentResolver.openOutputStream(targetFileDoc.uri)?.use { outputStream ->
                FileInputStream(sourceFile).use { inputStream ->
                    inputStream.copyTo(outputStream, bufferSize = 64 * 1024)
                }
                outputStream.flush()
            } ?: throw IOException("Could not open output stream for target document")

            Log.d(TAG, "File successfully copied to ${targetFileDoc.uri}")

            // Clean up temp directory
            cleanDirectory(tempDir)

            fileName
        } catch (e: Exception) {
            Log.e(TAG, "Error copying file to SAF folder", e)
            // Attempt to clean up partially written file
            try {
                targetFileDoc.delete()
            } catch (_: Exception) {}
            null
        }
    }

    /**
     * Recursively deletes a directory and all files inside.
     */
    fun cleanDirectory(dir: File) {
        if (!dir.exists()) return
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                cleanDirectory(file)
            } else {
                file.delete()
            }
        }
        dir.delete()
    }
}
