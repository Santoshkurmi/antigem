package com.example.gemini.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "auth_prefs")

class AuthPreferences(private val context: Context) {

    companion object {
        val ACCESS_TOKEN = stringPreferencesKey("access_token")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val PROJECT_ID = stringPreferencesKey("project_id")
        val SUBSCRIPTION_TIER = stringPreferencesKey("subscription_tier")
        val USER_EMAIL = stringPreferencesKey("user_email")
        val TERMUX_SSH_HOST = stringPreferencesKey("termux_ssh_host")
        val TERMUX_SSH_PORT = stringPreferencesKey("termux_ssh_port")
        val ENABLED_MODELS = androidx.datastore.preferences.core.stringSetPreferencesKey("enabled_models")
    }

    val accessToken: Flow<String?> = context.dataStore.data.map { it[ACCESS_TOKEN] }
    val refreshToken: Flow<String?> = context.dataStore.data.map { it[REFRESH_TOKEN] }
    val projectId: Flow<String?> = context.dataStore.data.map { it[PROJECT_ID] ?: "rising-fact-p41fc" }
    val subscriptionTier: Flow<String?> = context.dataStore.data.map { it[SUBSCRIPTION_TIER] ?: "pro" }
    val userEmail: Flow<String?> = context.dataStore.data.map { it[USER_EMAIL] }
    val enabledModelIds: Flow<Set<String>?> = context.dataStore.data.map { it[ENABLED_MODELS] }

    suspend fun saveTokens(accessToken: String, refreshToken: String?, email: String? = null) {
        context.dataStore.edit { prefs ->
            prefs[ACCESS_TOKEN] = accessToken
            if (refreshToken != null) {
                prefs[REFRESH_TOKEN] = refreshToken
            }
            if (email != null) {
                prefs[USER_EMAIL] = email
            }
        }
    }

    suspend fun saveProjectInfo(projectId: String, tier: String) {
        context.dataStore.edit { prefs ->
            prefs[PROJECT_ID] = projectId
            prefs[SUBSCRIPTION_TIER] = tier
        }
    }

    suspend fun saveEnabledModelIds(ids: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[ENABLED_MODELS] = ids
        }
    }

    suspend fun clearAuth() {
        context.dataStore.edit { prefs ->
            prefs.remove(ACCESS_TOKEN)
            prefs.remove(REFRESH_TOKEN)
            prefs.remove(USER_EMAIL)
        }
    }
}
