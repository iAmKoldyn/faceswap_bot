package com.facefusion.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val SETTINGS_STORE_NAME = "facefusion_settings"
private val Context.dataStore by preferencesDataStore(name = SETTINGS_STORE_NAME)

class SettingsDataStore(private val context: Context) {
    val baseUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[BASE_URL_KEY] ?: ""
    }

    val token: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[TOKEN_KEY] ?: ""
    }

    suspend fun setBaseUrl(value: String) {
        context.dataStore.edit { prefs ->
            prefs[BASE_URL_KEY] = value
        }
    }

    suspend fun setToken(value: String) {
        context.dataStore.edit { prefs ->
            prefs[TOKEN_KEY] = value
        }
    }

    private companion object {
        val BASE_URL_KEY: Preferences.Key<String> = stringPreferencesKey("base_url")
        val TOKEN_KEY: Preferences.Key<String> = stringPreferencesKey("token")
    }
}
