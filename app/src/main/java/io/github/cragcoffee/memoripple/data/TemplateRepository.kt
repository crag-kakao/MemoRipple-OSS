package io.github.cragcoffee.memoripple.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplatePolicy
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Templates, kept beside the settings rather than in the database.
 *
 * A template is a convenience for writing rather than something written, so it does not join the
 * memos in Room. It is not a document: a backup carries the list (format 18, `TemplateBackupDto`)
 * so a restore brings the starting points back, but nothing else ever refers to a template by id.
 */
class TemplateRepository(
    private val dataStore: DataStore<Preferences>,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val templates: Flow<List<MemoTemplate>> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences -> decode(preferences[KEY]) }
        .distinctUntilChanged()

    suspend fun save(template: MemoTemplate) {
        dataStore.edit { preferences ->
            preferences[KEY] = encode(
                MemoTemplatePolicy.upsert(decode(preferences[KEY]), template),
            )
        }
    }

    /** The list as stored right now — what a backup carries. */
    suspend fun current(): List<MemoTemplate> = decode(dataStore.data.first()[KEY])

    /** Restore: the file's templates replace this device's, capped the way the editor caps them. */
    suspend fun replaceAll(templates: List<MemoTemplate>) {
        dataStore.edit { preferences ->
            preferences[KEY] = encode(templates.take(MemoTemplatePolicy.MAX_TEMPLATES))
        }
    }

    suspend fun delete(id: String) {
        dataStore.edit { preferences ->
            preferences[KEY] = encode(MemoTemplatePolicy.remove(decode(preferences[KEY]), id))
        }
    }

    /** A deleted folder's templates become 未分類; nothing else about them changes (2026-09-22). */
    suspend fun clearFolder(folderId: String) {
        dataStore.edit { preferences ->
            preferences[KEY] = encode(io.github.cragcoffee.memoripple.domain.memos.TemplateFolderPolicy.unassign(decode(preferences[KEY]), folderId))
        }
    }

    private fun decode(raw: String?): List<MemoTemplate> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<MemoTemplate>>(raw)
        } catch (_: SerializationException) {
            // Unreadable storage is treated as no templates rather than as a crash: nothing the
            // user wrote as a memo is at stake here.
            emptyList()
        }
    }

    private fun encode(templates: List<MemoTemplate>): String = json.encodeToString(templates)

    private companion object {
        val KEY = stringPreferencesKey("memo_templates")
    }
}
