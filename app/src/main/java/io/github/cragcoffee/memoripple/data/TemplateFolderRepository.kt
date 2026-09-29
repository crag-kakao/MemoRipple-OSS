package io.github.cragcoffee.memoripple.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.cragcoffee.memoripple.domain.memos.TemplateFolder
import io.github.cragcoffee.memoripple.domain.memos.TemplateFolderPolicy
import io.github.cragcoffee.memoripple.domain.memos.TemplateFolderStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Template folders (2026-09-22), kept beside the templates in the app's preferences — template
 * organisation, never the memo wall's folders (those are Room). A backup carries them with the
 * templates (format 19, defaulted fields); the template file does not.
 */
class TemplateFolderRepository(private val dataStore: DataStore<Preferences>) : TemplateFolderStore {
    private val json = Json { ignoreUnknownKeys = true }

    override val folders: Flow<List<TemplateFolder>> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { preferences -> decode(preferences[KEY]) }
        .distinctUntilChanged()

    override suspend fun current(): List<TemplateFolder> = decode(dataStore.data.first()[KEY])

    suspend fun save(folder: TemplateFolder) = edit { TemplateFolderPolicy.upsert(it, folder) }
    suspend fun rename(id: String, name: String) = edit { TemplateFolderPolicy.rename(it, id, name) }
    suspend fun move(id: String, direction: Int) = edit { TemplateFolderPolicy.move(it, id, direction) }
    /** The folder goes; its templates are unassigned by the caller (`TemplateRepository.clearFolder`). */
    suspend fun delete(id: String) = edit { TemplateFolderPolicy.remove(it, id) }
    suspend fun replaceAll(folders: List<TemplateFolder>) = edit { folders.take(TemplateFolderPolicy.MAX_FOLDERS) }

    private suspend fun edit(change: (List<TemplateFolder>) -> List<TemplateFolder>) {
        dataStore.edit { preferences -> preferences[KEY] = json.encodeToString(change(decode(preferences[KEY]))) }
    }

    private fun decode(raw: String?): List<TemplateFolder> {
        if (raw.isNullOrBlank()) return emptyList()
        return try { json.decodeFromString<List<TemplateFolder>>(raw) } catch (_: SerializationException) { emptyList() }
    }

    private companion object {
        val KEY = stringPreferencesKey("memo_template_folders")
    }
}
