package com.muza.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "muza_settings")

class PreferencesManager(private val context: Context) {

    companion object {
        private val KEY_SAVE_FOLDER_URI = stringPreferencesKey("save_folder_uri")
        private val KEY_SAVE_FOLDER_NAME = stringPreferencesKey("save_folder_name")
        private val KEY_AUDIO_BITRATE = stringPreferencesKey("audio_bitrate")
        private val KEY_AUTO_DOWNLOAD_ON_SHARE = booleanPreferencesKey("auto_download_on_share")
    }

    val saveFolderUri: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[KEY_SAVE_FOLDER_URI]
    }

    val saveFolderName: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[KEY_SAVE_FOLDER_NAME]
    }

    val audioBitrate: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_AUDIO_BITRATE] ?: Config.DEFAULT_BITRATE
    }

    val autoDownloadOnShare: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_AUTO_DOWNLOAD_ON_SHARE] ?: true
    }

    suspend fun setAutoDownloadOnShare(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_AUTO_DOWNLOAD_ON_SHARE] = enabled
        }
    }

    suspend fun setSaveFolder(uri: String, displayName: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_SAVE_FOLDER_URI] = uri
            preferences[KEY_SAVE_FOLDER_NAME] = displayName
        }
    }

    suspend fun clearSaveFolder() {
        context.dataStore.edit { preferences ->
            preferences.remove(KEY_SAVE_FOLDER_URI)
            preferences.remove(KEY_SAVE_FOLDER_NAME)
        }
    }

    suspend fun setAudioBitrate(bitrate: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_AUDIO_BITRATE] = bitrate
        }
    }
}
