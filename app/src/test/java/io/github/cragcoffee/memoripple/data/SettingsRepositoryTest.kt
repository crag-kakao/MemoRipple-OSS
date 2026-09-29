package io.github.cragcoffee.memoripple.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.cragcoffee.memoripple.domain.settings.AppSettings
import io.github.cragcoffee.memoripple.domain.settings.CommentFont
import io.github.cragcoffee.memoripple.domain.settings.CommentSize
import io.github.cragcoffee.memoripple.domain.settings.PlaybackSpeed
import io.github.cragcoffee.memoripple.domain.settings.StageBackground
import io.github.cragcoffee.memoripple.domain.settings.ThemeMode
import io.github.cragcoffee.memoripple.domain.settings.ThemePaletteStyle
import io.github.cragcoffee.memoripple.domain.settings.ThemeSeed
import io.github.cragcoffee.memoripple.domain.speech.SpeechDictionaryEntry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(temporaryFolder.root, "settings.preferences_pb")
        }
        repository = SettingsRepository(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun emptyDataStoreUsesPhaseThreeDefaults() = runBlocking {
        assertEquals(AppSettings.Default, repository.settings.first())
    }

    @Test
    fun stableStringValuesAreSavedAndReadBack() = runBlocking {
        repository.setTheme(ThemeMode.DARK)
        repository.setPlaybackSpeed(PlaybackSpeed.FAST)
        repository.setCommentSize(CommentSize.LARGE)
        repository.setStageBackground(StageBackground.DARK_GRAY)

        assertEquals(
            AppSettings(
                themeMode = ThemeMode.DARK,
                playbackSpeed = PlaybackSpeed.FAST,
                commentSize = CommentSize.LARGE,
                stageBackground = StageBackground.DARK_GRAY,
            ),
            repository.settings.first(),
        )
    }

    @Test
    fun unknownStoredValuesSafelyFallBackToDefaults() = runBlocking {
        dataStore.edit { preferences ->
            preferences[SettingsRepository.Keys.THEME] = "future_theme"
            preferences[SettingsRepository.Keys.PLAYBACK_SPEED] = "warp"
            preferences[SettingsRepository.Keys.COMMENT_SIZE] = "huge"
            preferences[SettingsRepository.Keys.STAGE_BACKGROUND] = "photo"
        }

        assertEquals(AppSettings.Default, repository.settings.first())
    }

    @Test
    fun wallShapeIsDeviceLocalAndSurvivesTheProductReset() = runBlocking {
        assertEquals(false, repository.wallSingleColumn.first())

        repository.setWallSingleColumn(true)
        repository.resetToDefaults()

        assertEquals(true, repository.wallSingleColumn.first())
    }

    @Test
    fun toolbarRowsAreDeviceLocalAndSurviveTheProductReset() = runBlocking {
        assertEquals(false, repository.editorToolbarTwoRows.first())

        repository.setEditorToolbarTwoRows(true)
        repository.resetToDefaults()

        assertEquals(true, repository.editorToolbarTwoRows.first())
    }

    @Test
    fun hiddenTagsAreDeviceLocalAndSurviveTheProductReset() = runBlocking {
        assertEquals(false, repository.editorHideTags.first())

        repository.setEditorHideTags(true)
        repository.resetToDefaults()

        assertEquals(true, repository.editorHideTags.first())
    }

    @Test
    fun themeSeedAndPaletteStyleAreDeviceLocalAndSurviveTheProductReset() = runBlocking {
        assertEquals(ThemeSeed.DEFAULT, repository.themeSeed.first())
        assertEquals(ThemePaletteStyle.TONAL_SPOT, repository.themePaletteStyle.first())

        repository.setThemeSeed(ThemeSeed.INDIGO)
        repository.setThemePaletteStyle(ThemePaletteStyle.VIBRANT)
        repository.resetToDefaults()

        assertEquals(ThemeSeed.INDIGO, repository.themeSeed.first())
        assertEquals(ThemePaletteStyle.VIBRANT, repository.themePaletteStyle.first())
    }

    @Test
    fun commentFontIsDeviceLocalAndSurvivesTheProductReset() = runBlocking {
        assertEquals(CommentFont.DEFAULT, repository.commentFont.first())

        repository.setCommentFont(CommentFont.ROUNDED)
        repository.resetToDefaults()

        assertEquals(CommentFont.ROUNDED, repository.commentFont.first())
    }

    @Test
    fun wallPlaysContentIsOffByDefaultAndSurvivesTheProductReset() = runBlocking {
        assertEquals(false, repository.wallPlaysContent.first())

        repository.setWallPlaysContent(true)
        repository.resetToDefaults()

        assertEquals(true, repository.wallPlaysContent.first())
    }

    @Test
    fun commentBackdropIsOffByDefaultAndSurvivesTheProductReset() = runBlocking {
        assertEquals(false, repository.commentBackdrop.first())

        repository.setCommentBackdrop(true)
        repository.resetToDefaults()

        assertEquals(true, repository.commentBackdrop.first())
    }

    @Test
    fun commentTransparencyIsSolidByDefaultAndSurvivesTheProductReset() = runBlocking {
        assertEquals(0f, repository.commentTransparency.first())

        repository.setCommentTransparency(0.50f)
        repository.resetToDefaults()

        assertEquals(0.50f, repository.commentTransparency.first())
    }

    @Test
    fun wallOverlayPlaybackIsDeviceLocalAndSurvivesTheProductReset() = runBlocking {
        assertEquals(false, repository.wallOverlayPlayback.first())

        repository.setWallOverlayPlayback(true)
        repository.resetToDefaults()

        assertEquals(true, repository.wallOverlayPlayback.first())
    }

    @Test
    fun theSlidersDefaultToTheirNamedSpeedsAndSnapTheBackupVocabulary() = runBlocking {
        assertEquals(1.00f, repository.settings.first().playbackSpeedScale)
        assertEquals(1.00f, repository.settings.first().commentSizeScale)

        repository.setPlaybackSpeedScale(1.80f)
        repository.setCommentSizeScale(0.90f)

        val settings = repository.settings.first()
        assertEquals(1.80f, settings.playbackSpeedScale)
        assertEquals(PlaybackSpeed.FAST, settings.playbackSpeed)
        assertEquals(0.90f, settings.commentSizeScale)
        assertEquals(CommentSize.SMALL, settings.commentSize)
    }

    @Test
    fun aRestoreMakesTheNamedSpeedAuthoritativeAgain() = runBlocking {
        repository.setPlaybackSpeedScale(1.80f)

        repository.replaceAll(AppSettings.Default.copy(playbackSpeed = PlaybackSpeed.SLOW))

        val settings = repository.settings.first()
        assertEquals(PlaybackSpeed.SLOW, settings.playbackSpeed)
        assertEquals(PlaybackSpeed.SLOW.velocityMultiplier, settings.playbackSpeedScale)
    }

    @Test
    fun theProductResetPutsTheSlidersBackToStandard() = runBlocking {
        repository.setPlaybackSpeedScale(1.80f)
        repository.setCommentSizeScale(1.40f)

        repository.resetToDefaults()

        assertEquals(AppSettings.Default, repository.settings.first())
    }

    @Test
    fun theSpeechDictionaryIsRememberedAndResettable() = runBlocking {
        assertEquals(emptyList<SpeechDictionaryEntry>(), repository.speechDictionary.first())

        val entry = SpeechDictionaryEntry(id = "a", surface = "真面目", reading = "まじめ")
        repository.setSpeechDictionary(listOf(entry))
        assertEquals(listOf(entry), repository.speechDictionary.first())

        repository.resetSpeechDictionary()
        assertEquals(emptyList<SpeechDictionaryEntry>(), repository.speechDictionary.first())
    }

    @Test
    fun resetRemovesEveryPhaseFourSetting() = runBlocking {
        repository.setTheme(ThemeMode.DARK)
        repository.setPlaybackSpeed(PlaybackSpeed.FAST)
        repository.setCommentSize(CommentSize.LARGE)
        repository.setStageBackground(StageBackground.THEME)

        repository.resetToDefaults()

        assertEquals(AppSettings.Default, repository.settings.first())
    }

    @Test
    fun replaceAllWritesEverySettingInOneEdit() = runBlocking {
        val restored = AppSettings(
            themeMode = ThemeMode.DARK,
            playbackSpeed = PlaybackSpeed.FAST,
            commentSize = CommentSize.LARGE,
            stageBackground = StageBackground.LIGHT,
        )

        repository.replaceAll(restored)

        assertEquals(restored, repository.settings.first())
    }
}

class SettingsRepositoryCleanupTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repository = SettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope) {
                File(temporaryFolder.root, "settings.preferences_pb")
            },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** Folds and scroll anchors of memos that no longer exist are dropped; the living keep theirs. */
    @Test
    fun outlinerViewStateOfDeletedMemosIsPrunedAndTheRestIsUntouched() = runBlocking {
        repository.setOutlinerFolds(1, setOf("k1", "k2"))
        repository.setOutlinerFolds(2, setOf("k3"))
        repository.setOutlinerScrollAnchor(1, "a1")
        repository.setOutlinerScrollAnchor(3, "a3")

        val pruned = repository.pruneOutlinerViewState(liveMemoIds = setOf(1L))

        assertEquals(2, pruned)
        assertEquals(setOf("k1", "k2"), repository.outlinerFolds(1).first())
        assertEquals(emptySet<String>(), repository.outlinerFolds(2).first())
        assertEquals("a1", repository.outlinerScrollAnchor(1).first())
        assertEquals(null, repository.outlinerScrollAnchor(3).first())
    }

    /** With no memos left, both preferences disappear rather than staying as empty strings. */
    @Test
    fun pruningToNothingRemovesTheKeys() = runBlocking {
        repository.setOutlinerFolds(9, setOf("k"))
        repository.setOutlinerScrollAnchor(9, "a")
        assertEquals(2, repository.pruneOutlinerViewState(liveMemoIds = emptySet()))
        assertEquals(0, repository.pruneOutlinerViewState(liveMemoIds = emptySet()))
        assertEquals(emptySet<String>(), repository.outlinerFolds(9).first())
    }

    /** The font catalog for cleanup: null until the user has ever saved one, so a fresh or unreadable store never looks like "delete every font". */
    @Test
    fun theFontCatalogForCleanupIsNullUntilItWasEverWritten() = runBlocking {
        assertEquals(null, repository.userCommentFontsForCleanup())
        repository.setUserCommentFonts(emptyList())
        assertEquals(emptyList<io.github.cragcoffee.memoripple.domain.settings.UserCommentFont>(), repository.userCommentFontsForCleanup())
    }
}
