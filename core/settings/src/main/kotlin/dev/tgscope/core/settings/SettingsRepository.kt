package dev.tgscope.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.SecureRandom
import java.util.UUID

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** User-tunable settings. Mirrors the `.env` of the original Python tool. */
data class AppSettings(
    val apiId: Int? = null,
    val apiHash: String? = null,
    /** How many new posts to scan per run (POST_LIMIT). */
    val postLimit: Int = 50,
    /** How many already stored posts to re-check (INCREMENTAL_LOOKBACK_POSTS). */
    val lookbackPosts: Int = 20,
    /** Pause length (POSTS_PAUSE_SECONDS). */
    val pauseSeconds: Int = 2,
    /** Pause after every N processed posts (POSTS_PAUSE_AFTER_POSTS). */
    val pauseAfterPosts: Int = 10,
    val downloadMedia: Boolean = false,
    val anonymizeExport: Boolean = false,
    val watchEnabled: Boolean = false,
    /** OpenAI-compatible base URL, e.g. https://api.openai.com/v1 or http://192.168.1.5:11434/v1 */
    val llmEndpoint: String = "",
    val llmModel: String = "",
    val llmApiKey: String = "",
    val llmLanguage: String = "ru",
    /** Salt for stable anonymous aliases; generated once per install. */
    val anonymizerSalt: String = "",
) {
    val hasApiCredentials: Boolean get() = apiId != null && apiId > 0 && !apiHash.isNullOrBlank()
    val hasLlm: Boolean get() = llmEndpoint.isNotBlank() && llmModel.isNotBlank()
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val apiId = intPreferencesKey("api_id")
        val apiHash = stringPreferencesKey("api_hash")
        val dbKey = stringPreferencesKey("tdlib_db_key_enc")
        val postLimit = intPreferencesKey("post_limit")
        val lookback = intPreferencesKey("lookback_posts")
        val pauseSeconds = intPreferencesKey("pause_seconds")
        val pauseAfter = intPreferencesKey("pause_after_posts")
        val downloadMedia = booleanPreferencesKey("download_media")
        val anonymize = booleanPreferencesKey("anonymize_export")
        val watch = booleanPreferencesKey("watch_enabled")
        val llmEndpoint = stringPreferencesKey("llm_endpoint")
        val llmModel = stringPreferencesKey("llm_model")
        val llmApiKey = stringPreferencesKey("llm_api_key_enc")
        val llmLanguage = stringPreferencesKey("llm_language")
        val salt = stringPreferencesKey("anonymizer_salt")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    suspend fun saveApiCredentials(apiId: Int, apiHash: String) {
        context.settingsStore.edit {
            it[Keys.apiId] = apiId
            it[Keys.apiHash] = apiHash.trim()
        }
    }

    suspend fun clearApiCredentials() {
        context.settingsStore.edit {
            it.remove(Keys.apiId)
            it.remove(Keys.apiHash)
        }
    }

    /** 32 random bytes that encrypt the TDLib database; stored wrapped by the Keystore key. */
    suspend fun databaseKey(): ByteArray {
        val stored = context.settingsStore.data.first()[Keys.dbKey]
        stored?.let { KeystoreCipher.decrypt(it) }?.let { return it }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        context.settingsStore.edit { it[Keys.dbKey] = KeystoreCipher.encrypt(key) }
        return key
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsStore.edit { prefs ->
            val updated = transform(prefs.toSettings())
            prefs[Keys.postLimit] = updated.postLimit.coerceIn(1, 1000)
            prefs[Keys.lookback] = updated.lookbackPosts.coerceIn(0, 500)
            prefs[Keys.pauseSeconds] = updated.pauseSeconds.coerceIn(0, 120)
            prefs[Keys.pauseAfter] = updated.pauseAfterPosts.coerceIn(1, 500)
            prefs[Keys.downloadMedia] = updated.downloadMedia
            prefs[Keys.anonymize] = updated.anonymizeExport
            prefs[Keys.watch] = updated.watchEnabled
            prefs[Keys.llmEndpoint] = updated.llmEndpoint.trim()
            prefs[Keys.llmModel] = updated.llmModel.trim()
            prefs[Keys.llmLanguage] = updated.llmLanguage
            if (updated.llmApiKey.isEmpty()) {
                prefs.remove(Keys.llmApiKey)
            } else {
                prefs[Keys.llmApiKey] = KeystoreCipher.encrypt(updated.llmApiKey.toByteArray())
            }
        }
    }

    suspend fun ensureSalt() {
        if (current().anonymizerSalt.isEmpty()) {
            context.settingsStore.edit { it[Keys.salt] = UUID.randomUUID().toString() }
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            apiId = this[Keys.apiId],
            apiHash = this[Keys.apiHash],
            postLimit = this[Keys.postLimit] ?: defaults.postLimit,
            lookbackPosts = this[Keys.lookback] ?: defaults.lookbackPosts,
            pauseSeconds = this[Keys.pauseSeconds] ?: defaults.pauseSeconds,
            pauseAfterPosts = this[Keys.pauseAfter] ?: defaults.pauseAfterPosts,
            downloadMedia = this[Keys.downloadMedia] ?: defaults.downloadMedia,
            anonymizeExport = this[Keys.anonymize] ?: defaults.anonymizeExport,
            watchEnabled = this[Keys.watch] ?: defaults.watchEnabled,
            llmEndpoint = this[Keys.llmEndpoint] ?: defaults.llmEndpoint,
            llmModel = this[Keys.llmModel] ?: defaults.llmModel,
            llmApiKey = this[Keys.llmApiKey]?.let { KeystoreCipher.decrypt(it)?.decodeToString() } ?: "",
            llmLanguage = this[Keys.llmLanguage] ?: defaults.llmLanguage,
            anonymizerSalt = this[Keys.salt] ?: "",
        )
    }
}
