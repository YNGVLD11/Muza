package com.muza.app.ui.screens

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.muza.app.data.PreferencesManager
import com.muza.app.service.DownloadQueueManager
import com.muza.app.util.UrlExtractor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesManager = PreferencesManager(application)

    private val _inputUrl = MutableStateFlow("")
    val inputUrl = _inputUrl.asStateFlow()

    val queueItems = DownloadQueueManager.items
    val saveFolderUri = preferencesManager.saveFolderUri
    val saveFolderName = preferencesManager.saveFolderName
    val audioBitrate = preferencesManager.audioBitrate

    fun onInputUrlChanged(url: String) {
        _inputUrl.value = url
        // If a complete YouTube link is entered/pasted into the field, automatically add it to the queue
        val extracted = UrlExtractor.extract(url)
        if (extracted != null && !DownloadQueueManager.containsUrl(extracted)) {
            val context = getApplication<Application>()
            DownloadQueueManager.enqueue(context, extracted, autoStart = false)
            Toast.makeText(context, "Добавлено в очередь", Toast.LENGTH_SHORT).show()
        }
    }

    fun onPaste(rawText: String) {
        if (rawText.isBlank()) return
        _inputUrl.value = rawText
        val urls = UrlExtractor.extractAll(rawText)
        if (urls.isNotEmpty()) {
            val context = getApplication<Application>()
            DownloadQueueManager.enqueueBatch(context, urls, autoStart = false)
            if (urls.size == 1) {
                Toast.makeText(context, "Добавлено в очередь", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Добавлено в очередь: ${urls.size} треков", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(getApplication(), "Не удалось распознать ссылку на YouTube", Toast.LENGTH_SHORT).show()
        }
    }

    fun submitInput() {
        val current = _inputUrl.value
        if (current.isNotBlank()) {
            val urls = UrlExtractor.extractAll(current)
            if (urls.isNotEmpty()) {
                val context = getApplication<Application>()
                DownloadQueueManager.enqueueBatch(context, urls, autoStart = false)
                Toast.makeText(context, "Добавлено в очередь", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(getApplication(), "Не удалось распознать ссылку на YouTube", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun clearInput() {
        _inputUrl.value = ""
    }

    /**
     * Handles incoming intent text from Share Target.
     */
    fun processUrl(rawText: String) {
        val urls = UrlExtractor.extractAll(rawText)
        if (urls.isNotEmpty()) {
            val context = getApplication<Application>()
            viewModelScope.launch {
                val autoStart = preferencesManager.autoDownloadOnShare.first()
                DownloadQueueManager.enqueueBatch(context, urls, autoStart = autoStart)
                if (urls.size == 1) {
                    Toast.makeText(context, "Добавлено в очередь", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Добавлено в очередь: ${urls.size} треков", Toast.LENGTH_SHORT).show()
                }
            }
        } else if (rawText.isNotBlank()) {
            Toast.makeText(getApplication(), "Не удалось распознать ссылку на YouTube", Toast.LENGTH_SHORT).show()
        }
    }

    fun startQueue() {
        DownloadQueueManager.startQueue(getApplication())
    }

    fun removeItem(id: String) {
        DownloadQueueManager.removeItem(id)
    }

    fun cancelItem(id: String) {
        DownloadQueueManager.cancelItem(id, getApplication())
    }

    fun retryItem(id: String) {
        DownloadQueueManager.retryItem(id, getApplication())
    }

    fun clearCompleted() {
        DownloadQueueManager.clearCompleted()
    }
}
