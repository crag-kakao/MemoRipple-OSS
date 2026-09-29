package io.github.cragcoffee.memoripple.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.cragcoffee.memoripple.domain.memos.RecentTemplate
import io.github.cragcoffee.memoripple.domain.memos.RecentTemplateStore
import io.github.cragcoffee.memoripple.domain.memos.RecentTemplates
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * 最近使ったテンプレート (docs/CHAT_UI_TEMPLATE_V2.md §14): the ids of the last few templates run and
 * when — kept beside the settings as one preference, never in Room, never in a backup; no body
 * and no typed value is stored. Unreadable storage is an empty list.
 */
class RecentTemplateRepository(private val dataStore: DataStore<Preferences>) : RecentTemplateStore {
    private val json = Json { ignoreUnknownKeys = true }

    override val recent: Flow<List<RecentTemplate>> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { preferences -> decode(preferences[KEY]) }
        .distinctUntilChanged()

    override suspend fun record(templateId: String, at: Long) {
        dataStore.edit { preferences -> preferences[KEY] = json.encodeToString(RecentTemplates.push(decode(preferences[KEY]), templateId, at)) }
    }

    override suspend fun clear() {
        dataStore.edit { preferences -> preferences.remove(KEY) }
    }

    private fun decode(raw: String?): List<RecentTemplate> {
        if (raw.isNullOrBlank()) return emptyList()
        return try { json.decodeFromString<List<RecentTemplate>>(raw) } catch (_: SerializationException) { emptyList() }
    }

    private companion object {
        val KEY = stringPreferencesKey("recent_templates")
    }
}
