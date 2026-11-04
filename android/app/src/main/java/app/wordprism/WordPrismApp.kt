package app.wordprism

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.wordprism.di.SdkProvider
import app.wordprism.sdk.WordPrismSdk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class WordPrismApp : Application() {
    lateinit var sdk: WordPrismSdk
        private set
    lateinit var drafts: DraftStore
        private set

    override fun onCreate() {
        super.onCreate()
        sdk = SdkProvider.create(this)
        drafts = DraftStore(this)
    }
}

private val Context.draftDataStore by preferencesDataStore("ui_drafts")

/**
 * UI-only drafts typed before anything is submitted to the backend (e.g. the AI intake box).
 * Answer drafts inside a session are saved through StudyApi.saveDraft instead.
 */
class DraftStore(private val context: Context) {
    fun observe(key: String): Flow<String> = context.draftDataStore.data.map { it[stringPreferencesKey(key)].orEmpty() }
    suspend fun read(key: String): String = observe(key).first()
    suspend fun write(key: String, value: String) { context.draftDataStore.edit { it[stringPreferencesKey(key)] = value } }
}
