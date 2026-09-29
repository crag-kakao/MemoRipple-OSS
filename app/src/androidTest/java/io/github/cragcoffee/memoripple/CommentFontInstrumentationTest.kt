package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.cragcoffee.memoripple.domain.settings.CommentFont
import io.github.cragcoffee.memoripple.domain.settings.CommentFontSelection
import java.io.ByteArrayInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The user's own comment fonts, end to end: real font bytes go through the store and come
 * back as a drawable typeface, garbage is refused without leaving a file, and the settings
 * dialog hides, restores, selects, and deletes with the selection always landing somewhere
 * sensible.
 */
@RunWith(AndroidJUnit4::class)
class CommentFontInstrumentationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun application(): MemoRippleApplication =
        composeRule.activity.application as MemoRippleApplication

    private fun realFontBytes(): ByteArray =
        composeRule.activity.resources.openRawResource(R.font.kosugi_maru).use { it.readBytes() }

    @org.junit.After
    fun tidy() {
        // Device-local prefs outlive the test process; leave the shelf as it was found.
        runBlocking {
            val repository = application().settingsRepository
            repository.userCommentFonts.first().forEach { application().commentFontStore.delete(it) }
            repository.setUserCommentFonts(emptyList())
            repository.setRemovedBuiltInCommentFonts(emptySet())
            repository.setCommentFontId(CommentFont.DEFAULT.storageId)
        }
    }

    @Before
    fun reset() {
        runBlocking {
            val repository = application().settingsRepository
            repository.userCommentFonts.first().forEach { application().commentFontStore.delete(it) }
            repository.setUserCommentFonts(emptyList())
            repository.setRemovedBuiltInCommentFonts(emptySet())
            repository.setCommentFontId(CommentFont.DEFAULT.storageId)
            repository.setAutoPlayOnLaunch(false)
        }
    }

    @Test
    fun realBytesInstallAndGarbageIsRefusedCleanly() {
        runBlocking {
            val store = application().commentFontStore
            val installed = store.install(ByteArrayInputStream(realFontBytes()), "丸ゴ.ttf")
            assertNotNull(installed)
            installed!!
            assertEquals("丸ゴ", installed.name)
            assertTrue(store.file(installed).isFile)
            assertNotNull(store.loadTypeface(installed))
            store.delete(installed)
            assertFalse(store.file(installed).isFile)

            val refused = store.install(
                ByteArrayInputStream(ByteArray(512) { (it % 251).toByte() }),
                "偽物.ttf",
            )
            assertNull(refused)
            val leftovers = store.file(
                io.github.cragcoffee.memoripple.domain.settings.UserCommentFont(
                    "probe", "probe", "probe.ttf",
                ),
            ).parentFile?.listFiles().orEmpty()
            assertTrue(leftovers.isEmpty())
        }
    }

    private fun openFontDialog() {
        if (composeRule.onAllNodesWithTag("memo_top_menu").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("memo_top_menu").performClick()
            composeRule.onNodeWithTag("memo_open_settings").performClick()
        } else {
            composeRule.onNodeWithContentDescription("設定").performClick()
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("settings_list").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("setting_comment_font"))
        composeRule.onNodeWithTag("setting_comment_font").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("comment_font_dialog").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun builtInFacesCarryNoDeleteButtonAndAlwaysStandOnTheShelf() {
        // The bundled faces live in the APK: deleting them never freed anything, so the
        // button is gone (撤去 2026-09-04). Even a hide left over from the old behaviour
        // is ignored — every built-in face is simply always offered.
        runBlocking {
            application().settingsRepository.setRemovedBuiltInCommentFonts(setOf("mincho"))
        }
        openFontDialog()
        listOf("default", "gothic", "mincho", "rounded").forEach { id ->
            assertTrue(
                "delete button lingers for $id",
                composeRule.onAllNodesWithTag("comment_font_delete_builtin_$id")
                    .fetchSemanticsNodes().isEmpty(),
            )
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("comment_font_builtin_mincho")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("キャンセル").performClick()
    }

    @Test
    fun aUserFontJoinsTheListIsChosenAndLeavesOnRequest() {
        val installed = runBlocking {
            val store = application().commentFontStore
            val font = requireNotNull(
                store.install(ByteArrayInputStream(realFontBytes()), "自前フォント.ttf"),
            )
            application().settingsRepository.setUserCommentFonts(listOf(font))
            font
        }
        openFontDialog()
        composeRule.onNodeWithTag("comment_font_user_${installed.id}").assertIsDisplayed()
        composeRule.onNodeWithTag("comment_font_user_${installed.id}").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking {
                application().settingsRepository.commentFontId.first() ==
                    CommentFontSelection.userStorageId(installed)
            }
        }
        // The settings row now names the user's own font.
        composeRule.onNodeWithTag("setting_comment_font")
            .assertTextContains("自前フォント", substring = true)

        // Deleting asks, then removes the file and lands the selection back on the default.
        composeRule.onNodeWithTag("setting_comment_font").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("comment_font_delete_${installed.id}")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("comment_font_delete_${installed.id}").performClick()
        composeRule.onNodeWithTag("comment_font_delete_confirm").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking {
                application().settingsRepository.commentFontId.first() ==
                    CommentFont.DEFAULT.storageId &&
                    application().settingsRepository.userCommentFonts.first().isEmpty()
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            !application().commentFontStore.file(installed).isFile
        }
    }
}
