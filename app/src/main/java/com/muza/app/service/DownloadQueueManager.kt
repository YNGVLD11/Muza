package com.muza.app.service

import android.content.Context
import android.util.Log
import com.muza.app.util.CookieNetscapeConverter
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class DownloadStatus {
    FETCHING_INFO,
    QUEUED,
    DOWNLOADING,
    CONVERTING,
    SAVING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class QueueItem(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String = "Загрузка информации...",
    val durationSeconds: Long = 0L,
    val thumbnailUrl: String? = null,
    val uploader: String? = null,
    val status: DownloadStatus = DownloadStatus.FETCHING_INFO,
    val progress: Float = 0f,
    val statusText: String = "Получение информации...",
    val fileName: String? = null,
    val errorMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

object DownloadQueueManager {
    private const val TAG = "DownloadQueueManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _items = MutableStateFlow<List<QueueItem>>(emptyList())
    val items = _items.asStateFlow()

    @Volatile
    var isServiceRunning = false

    @Volatile
    var activeItemId: String? = null

    /**
     * Atomically retrieves and claims the next queued item (FIFO).
     */
    @Synchronized
    fun dequeueNext(): QueueItem? {
        val list = _items.value
        // Find next eligible item in queue
        val next = list.lastOrNull {
            it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.FETCHING_INFO
        } ?: return null

        activeItemId = next.id
        updateItem(next.id) {
            it.copy(status = DownloadStatus.DOWNLOADING, statusText = "Подготовка...")
        }
        return next
    }

    fun hasPendingItems(): Boolean {
        return _items.value.any {
            it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.FETCHING_INFO
        }
    }

    fun containsUrl(url: String): Boolean {
        val norm = com.muza.app.util.UrlExtractor.extract(url) ?: url
        return _items.value.any { it.url == norm }
    }

    /**
     * Enqueues a URL immediately as an independent item in the queue.
     * Starts in FETCHING_INFO so the shimmering tile appears immediately in the queue list.
     */
    fun enqueue(
        context: Context,
        url: String,
        autoStart: Boolean = false
    ): String {
        val norm = com.muza.app.util.UrlExtractor.extract(url) ?: url
        // If already in active queue or downloading, avoid duplicate
        val existing = _items.value.find {
            it.url == norm && (it.status == DownloadStatus.FETCHING_INFO ||
                    it.status == DownloadStatus.QUEUED ||
                    it.status == DownloadStatus.DOWNLOADING ||
                    it.status == DownloadStatus.CONVERTING ||
                    it.status == DownloadStatus.SAVING)
        }
        if (existing != null) {
            if (autoStart) startQueue(context)
            return existing.id
        }

        val newItem = QueueItem(
            url = norm,
            status = DownloadStatus.FETCHING_INFO,
            statusText = "Получение информации..."
        )

        // Add to queue list
        _items.update { listOf(newItem) + it }

        // Start fetching metadata specifically for this queue item
        fetchMetadataForItem(context, newItem.id, norm, autoStart)

        return newItem.id
    }

    fun enqueueBatch(context: Context, urls: List<String>, autoStart: Boolean = false) {
        val newItems = mutableListOf<QueueItem>()
        urls.forEach { rawUrl ->
            val norm = com.muza.app.util.UrlExtractor.extract(rawUrl) ?: rawUrl
            val existing = _items.value.find {
                it.url == norm && (it.status == DownloadStatus.FETCHING_INFO ||
                        it.status == DownloadStatus.QUEUED ||
                        it.status == DownloadStatus.DOWNLOADING ||
                        it.status == DownloadStatus.CONVERTING ||
                        it.status == DownloadStatus.SAVING)
            }
            if (existing == null) {
                newItems.add(
                    QueueItem(
                        url = norm,
                        status = DownloadStatus.FETCHING_INFO,
                        statusText = "Получение информации..."
                    )
                )
            }
        }

        if (newItems.isNotEmpty()) {
            _items.update { newItems + it }
            newItems.forEach { item ->
                fetchMetadataForItem(context, item.id, item.url, autoStart)
            }
        }
    }

    private fun fetchMetadataForItem(context: Context, id: String, url: String, autoStart: Boolean) {
        scope.launch {
            try {
                val cookiesFile = CookieNetscapeConverter.getCookiesFile(context)
                val request = YoutubeDLRequest(url).apply {
                    addOption("--no-playlist")
                    addOption("--skip-download")
                    addOption("--socket-timeout", "10")
                    addOption("--no-warnings")
                    if (cookiesFile.exists() && cookiesFile.length() > 0) {
                        addOption("--cookies", cookiesFile.absolutePath)
                    }
                }

                val info = YoutubeDL.getInstance().getInfo(request)
                updateItem(id) { current ->
                    // Only update to QUEUED if it hasn't already started downloading
                    val newStatus = if (current.status == DownloadStatus.FETCHING_INFO) {
                        DownloadStatus.QUEUED
                    } else {
                        current.status
                    }
                    val newStatusText = if (current.status == DownloadStatus.FETCHING_INFO) {
                        "В очереди"
                    } else {
                        current.statusText
                    }

                    current.copy(
                        title = info.title ?: current.title,
                        durationSeconds = info.duration.toLong(),
                        thumbnailUrl = info.thumbnail,
                        uploader = info.uploader,
                        status = newStatus,
                        statusText = newStatusText
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed pre-fetching metadata for $url: ${e.message}")
                updateItem(id) { current ->
                    if (current.status == DownloadStatus.FETCHING_INFO) {
                        current.copy(status = DownloadStatus.QUEUED, statusText = "В очереди")
                    } else current
                }
            } finally {
                if (autoStart) {
                    startQueue(context)
                }
            }
        }
    }

    fun startQueue(context: Context) {
        DownloadService.startQueue(context)
    }

    fun notifyItemProgress(id: String, progress: Float, statusText: String, status: DownloadStatus) {
        updateItem(id) {
            it.copy(progress = progress, statusText = statusText, status = status)
        }
    }

    fun notifyItemCompleted(id: String, fileName: String) {
        updateItem(id) {
            it.copy(
                status = DownloadStatus.COMPLETED,
                fileName = fileName,
                progress = 100f,
                statusText = "Сохранено"
            )
        }
        if (activeItemId == id) {
            activeItemId = null
        }
    }

    fun notifyItemFailed(id: String, errorMessage: String) {
        updateItem(id) {
            it.copy(
                status = DownloadStatus.FAILED,
                errorMessage = errorMessage,
                statusText = "Ошибка"
            )
        }
        if (activeItemId == id) {
            activeItemId = null
        }
    }

    fun removeItem(id: String) {
        _items.update { list -> list.filterNot { it.id == id } }
    }

    fun clearCompleted() {
        _items.update { list ->
            list.filterNot { it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.CANCELLED }
        }
    }

    fun cancelItem(id: String, context: Context) {
        if (activeItemId == id) {
            DownloadService.cancelCurrent(context)
        } else {
            removeItem(id)
        }
    }

    fun retryItem(id: String, context: Context) {
        updateItem(id) {
            it.copy(
                status = DownloadStatus.QUEUED,
                progress = 0f,
                statusText = "В очереди",
                errorMessage = null
            )
        }
        startQueue(context)
    }

    private fun updateItem(id: String, transform: (QueueItem) -> QueueItem) {
        _items.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
    }
}
