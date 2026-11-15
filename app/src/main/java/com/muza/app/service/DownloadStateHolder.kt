package com.muza.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface DownloadState {
    object Idle : DownloadState
    object FetchingInfo : DownloadState
    data class Downloading(val progress: Float, val statusText: String) : DownloadState
    data class Success(val fileName: String) : DownloadState
    data class Error(val message: String) : DownloadState
}

data class VideoMetadata(
    val url: String,
    val title: String,
    val durationSeconds: Long,
    val thumbnailUrl: String?,
    val uploader: String? = null
)

object DownloadStateHolder {
    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state = _state.asStateFlow()

    private val _metadata = MutableStateFlow<VideoMetadata?>(null)
    val metadata = _metadata.asStateFlow()

    fun updateState(newState: DownloadState) {
        _state.value = newState
    }

    fun setMetadata(newMetadata: VideoMetadata?) {
        _metadata.value = newMetadata
    }

    fun reset() {
        _state.value = DownloadState.Idle
        _metadata.value = null
    }

    fun clearState() {
        _state.value = DownloadState.Idle
    }
}
