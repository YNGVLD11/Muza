package com.muza.app.util

import java.util.Locale

object Formatters {
    /**
     * Formats duration in seconds into "MM:SS" or "HH:MM:SS"
     */
    fun formatDuration(durationSeconds: Long): String {
        if (durationSeconds <= 0) return "00:00"
        val hours = durationSeconds / 3600
        val minutes = (durationSeconds % 3600) / 60
        val seconds = durationSeconds % 60

        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}
