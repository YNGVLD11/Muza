package com.muza.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.muza.app.MainActivity
import com.muza.app.MuzaApp
import com.muza.app.R
import com.muza.app.data.Config
import com.muza.app.data.PreferencesManager
import com.muza.app.util.CookieNetscapeConverter
import com.muza.app.util.FileHelper
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class DownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var queueLoopJob: Job? = null
    private var currentDownloadJob: Job? = null
    private lateinit var notificationManager: NotificationManager

    private var currentItemId: String? = null
    private var serviceStartTime: Long = 0L
    private var lastNotifyTime: Long = 0L
    private var lastReportedPercent: Int = -1
    private var lastReportedStatus: String = ""

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        DownloadQueueManager.isServiceRunning = true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelCurrentDownload()
                return START_NOT_STICKY
            }
            ACTION_START, ACTION_START_QUEUE -> {
                ensureQueueProcessing()
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureQueueProcessing() {
        if (queueLoopJob?.isActive == true) {
            // Already looping through queue; it will naturally pick up any newly enqueued items
            return
        }

        queueLoopJob = serviceScope.launch {
            try {
                while (true) {
                    val item = DownloadQueueManager.dequeueNext() ?: break
                    currentItemId = item.id

                    val preferencesManager = PreferencesManager(this@DownloadService)
                    val folderUri = preferencesManager.saveFolderUri.first()
                    val bitrate = preferencesManager.audioBitrate.first() ?: Config.DEFAULT_BITRATE

                    if (folderUri.isNullOrBlank()) {
                        Log.e(TAG, "Missing folder URI for item ${item.id}")
                        DownloadQueueManager.notifyItemFailed(
                            item.id,
                            "Выберите папку для сохранения в настройках"
                        )
                        continue
                    }

                    serviceStartTime = System.currentTimeMillis()
                    lastNotifyTime = 0L
                    lastReportedPercent = -1
                    lastReportedStatus = ""

                    val notifTitle = formatNotificationTitle(item.title)
                    startForegroundServiceWithNotification(notifTitle)

                    processSingleDownload(item, bitrate, folderUri)
                }
            } finally {
                // When no more items remain in the queue:
                // Stop foreground and release service running state
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                DownloadQueueManager.isServiceRunning = false
                currentItemId = null
                stopSelf()
            }
        }
    }

    private suspend fun processSingleDownload(
        item: QueueItem,
        bitrate: String,
        folderUriString: String
    ) {
        val tempDir = File(cacheDir, "muza_dl_${System.currentTimeMillis()}")
        if (!tempDir.exists()) tempDir.mkdirs()

        currentDownloadJob = serviceScope.launch {
            try {
                DownloadQueueManager.notifyItemProgress(
                    item.id,
                    0f,
                    "Подготовка...",
                    DownloadStatus.DOWNLOADING
                )
                DownloadStateHolder.updateState(DownloadState.Downloading(0f, "Подготовка..."))

                val request = YoutubeDLRequest(item.url).apply {
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                    addOption("--audio-quality", "${bitrate}K")
                    addOption("-f", "ba/b")
                    addOption("--no-playlist")
                    addOption("--socket-timeout", "15")
                    addOption("--no-warnings")
                    addOption("--embed-thumbnail")
                    addOption("--add-metadata")

                    val cookiesFile = CookieNetscapeConverter.getCookiesFile(this@DownloadService)
                    if (cookiesFile.exists() && cookiesFile.length() > 0) {
                        addOption("--cookies", cookiesFile.absolutePath)
                    }

                    addOption("-o", "${tempDir.absolutePath}/%(title)s.%(ext)s")
                }

                YoutubeDL.getInstance().execute(request) { progress, _, line ->
                    val isConverting = progress >= 99f ||
                            line.contains("[ExtractAudio]") ||
                            line.contains("[ffmpeg]")

                    val statusText = if (isConverting) {
                        "Конвертация в MP3..."
                    } else {
                        "Скачивание: ${progress.toInt()}%"
                    }

                    val status = if (isConverting) DownloadStatus.CONVERTING else DownloadStatus.DOWNLOADING

                    DownloadQueueManager.notifyItemProgress(item.id, progress, statusText, status)
                    DownloadStateHolder.updateState(DownloadState.Downloading(progress, statusText))

                    val notifTitle = formatNotificationTitle(item.title)
                    updateNotification(
                        title = notifTitle,
                        content = statusText,
                        progress = progress.toInt(),
                        isIndeterminate = isConverting,
                        force = isConverting
                    )
                }

                DownloadQueueManager.notifyItemProgress(
                    item.id,
                    100f,
                    "Сохранение...",
                    DownloadStatus.SAVING
                )
                DownloadStateHolder.updateState(DownloadState.Downloading(100f, "Сохранение..."))

                val notifTitle = formatNotificationTitle(item.title)
                updateNotification(
                    title = notifTitle,
                    content = "Сохранение в целевую папку...",
                    progress = 100,
                    isIndeterminate = true,
                    force = true
                )

                val savedFileName = FileHelper.moveMp3ToUserFolder(
                    context = this@DownloadService,
                    tempDir = tempDir,
                    targetTreeUri = Uri.parse(folderUriString)
                )

                if (savedFileName != null) {
                    DownloadStateHolder.updateState(DownloadState.Success(savedFileName))
                    DownloadQueueManager.notifyItemCompleted(item.id, savedFileName)
                    val remaining = DownloadQueueManager.items.value.count {
                        it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.FETCHING_INFO
                    }
                    if (remaining == 0) {
                        showFinishedNotification("Скачивание завершено: $savedFileName", isSuccess = true)
                    }
                } else {
                    val errMsg = "Ошибка при сохранении файла в выбранную папку"
                    DownloadStateHolder.updateState(DownloadState.Error(errMsg))
                    DownloadQueueManager.notifyItemFailed(item.id, errMsg)
                    val remaining = DownloadQueueManager.items.value.count {
                        it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.FETCHING_INFO
                    }
                    if (remaining == 0) {
                        showFinishedNotification(errMsg, isSuccess = false)
                    }
                }

            } catch (e: CancellationException) {
                Log.d(TAG, "Item ${item.id} download was cancelled")
                FileHelper.cleanDirectory(tempDir)
                DownloadQueueManager.notifyItemFailed(item.id, "Отменено пользователем")
                DownloadStateHolder.updateState(DownloadState.Idle)
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for item ${item.id}", e)
                FileHelper.cleanDirectory(tempDir)
                val errorMessage = e.localizedMessage ?: "Ошибка скачивания"
                DownloadStateHolder.updateState(DownloadState.Error(errorMessage))
                DownloadQueueManager.notifyItemFailed(item.id, errorMessage)
                val remaining = DownloadQueueManager.items.value.count {
                    it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.FETCHING_INFO
                }
                if (remaining == 0) {
                    showFinishedNotification("Ошибка: $errorMessage", isSuccess = false)
                }
            }
        }

        currentDownloadJob?.join()
    }

    private fun cancelCurrentDownload() {
        currentDownloadJob?.cancel()
        val itemId = currentItemId
        if (itemId != null) {
            DownloadQueueManager.notifyItemFailed(itemId, "Отменено пользователем")
        }
    }

    private fun formatNotificationTitle(title: String): String {
        val remaining = DownloadQueueManager.items.value.count { it.status == DownloadStatus.QUEUED }
        return if (remaining > 0) {
            "Muza (+${remaining} в очереди) • $title"
        } else {
            title
        }
    }

    private fun startForegroundServiceWithNotification(title: String) {
        val initialNotification = buildNotification(
            title = title,
            content = "Подготовка...",
            progress = 0,
            isIndeterminate = true
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }
    }

    private fun buildNotification(
        title: String,
        content: String,
        progress: Int,
        isIndeterminate: Boolean
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, DownloadService::class.java).apply {
            action = ACTION_CANCEL
        }
        val pendingCancel = PendingIntent.getService(
            this, 1, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, MuzaApp.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setWhen(if (serviceStartTime > 0L) serviceStartTime else System.currentTimeMillis())
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отмена", pendingCancel)
            .setProgress(100, progress, isIndeterminate)
            .build()
    }

    private fun updateNotification(
        title: String,
        content: String,
        progress: Int,
        isIndeterminate: Boolean,
        force: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        val percent = progress.coerceIn(0, 100)

        if (!force && content == lastReportedStatus &&
            (now - lastNotifyTime < 1200L || (percent - lastReportedPercent < 3 && percent < 100))
        ) {
            return
        }

        lastNotifyTime = now
        lastReportedPercent = percent
        lastReportedStatus = content

        val notification = buildNotification(title, content, progress, isIndeterminate)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showFinishedNotification(message: String, isSuccess: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Reusing NOTIFICATION_ID atomically replaces the ongoing notification
        // so it cannot remain stuck in the notification shade.
        val notification = NotificationCompat.Builder(this, MuzaApp.CHANNEL_ID)
            .setContentTitle(if (isSuccess) "Muza" else "Ошибка")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(false)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setContentIntent(pendingOpen)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        DownloadQueueManager.isServiceRunning = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        serviceScope.cancel()
    }

    companion object {
        const val ACTION_START = "com.muza.app.action.START_DOWNLOAD"
        const val ACTION_START_QUEUE = "com.muza.app.action.START_QUEUE"
        const val ACTION_CANCEL = "com.muza.app.action.CANCEL_DOWNLOAD"

        private const val NOTIFICATION_ID = 1001
        private const val TAG = "DownloadService"

        fun startQueue(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START_QUEUE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancelCurrent(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }
}
