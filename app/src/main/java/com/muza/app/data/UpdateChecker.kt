package com.muza.app.data

import android.content.Context
import android.util.Log
import com.muza.app.BuildConfig
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed interface UpdateCheckState {
    object Idle : UpdateCheckState
    object Checking : UpdateCheckState
    data class Completed(
        val appHasUpdate: Boolean,
        val latestAppVersion: String?,
        val currentAppVersion: String,
        val releaseUrl: String?,
        val engineStatus: String,
        val errorMessage: String? = null
    ) : UpdateCheckState
}

class UpdateChecker(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun checkAllUpdates(): UpdateCheckState.Completed = withContext(Dispatchers.IO) {
        var engineResult = "Не удалось обновить"
        var errorMessage: String? = null

        // 1. Update YoutubeDL / yt-dlp core binary
        try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel._STABLE)
            engineResult = when (status) {
                YoutubeDL.UpdateStatus.DONE -> "Движок обновлен до последней версии"
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "Движок уже актуален"
                else -> "Статус: $status"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating YoutubeDL", e)
            engineResult = "Ошибка обновления движка: ${e.message}"
            errorMessage = e.localizedMessage
        }

        // 2. Check GitHub for app update
        var appHasUpdate = false
        var latestVersion: String? = null
        var releaseUrl: String? = null
        val currentVersion = BuildConfig.VERSION_NAME

        try {
            val request = Request.Builder()
                .url(Config.GITHUB_RELEASES_API_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "Muza-Android-App")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string()
                    if (!bodyString.isNullOrEmpty()) {
                        val json = JSONObject(bodyString)
                        val tagName = json.optString("tag_name", "").removePrefix("v")
                        releaseUrl = json.optString("html_url", Config.GITHUB_RELEASES_PAGE_URL)
                        latestVersion = tagName

                        if (isVersionNewer(latestVersion, currentVersion)) {
                            appHasUpdate = true
                        }
                    }
                } else {
                    Log.w(TAG, "GitHub API returned status code ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking GitHub release", e)
            if (errorMessage == null) {
                errorMessage = "Не удалось проверить обновления приложения: ${e.message}"
            }
        }

        UpdateCheckState.Completed(
            appHasUpdate = appHasUpdate,
            latestAppVersion = latestVersion,
            currentAppVersion = currentVersion,
            releaseUrl = releaseUrl,
            engineStatus = engineResult,
            errorMessage = errorMessage
        )
    }

    private fun isVersionNewer(latest: String?, current: String): Boolean {
        if (latest.isNullOrBlank()) return false
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }

        val length = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until length) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    companion object {
        private const val TAG = "UpdateChecker"
    }
}
