package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import io.github.cragcoffee.memoripple.domain.memos.StarterTemplates
import io.github.cragcoffee.memoripple.domain.memos.ThinkTemplates
import kotlinx.coroutines.runBlocking

/**
 * The ＋ picker is an entrance, not a list (2026-09-22): a starter sits in its built-in folder, a
 * custom template under 自分のテンプレート → its folder or 未分類. The journeys drill the same way a
 * person does. The picker must be open (`chat_template_picker`).
 */
object PickerDrill {
    private fun ComposeTestRule.present(tag: String) = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun ComposeTestRule.await(tag: String) = waitUntil(15_000) { present(tag) }

    /** Drills to the row of [id] and leaves the picker on that page; the row is on screen. */
    fun ComposeTestRule.drillToTemplate(id: String, application: MemoRippleApplication) {
        await("chat_template_picker")
        val starter = StarterTemplates.find(id)
        if (starter != null) {
            val folder = when (ThinkTemplates.sectionOf(starter)) { ThinkTemplates.Section.RECORD -> "record"; ThinkTemplates.Section.THINK -> "think"; ThinkTemplates.Section.SEARCH -> "search" }
            onNodeWithTag("chat_template_list").performScrollToNode(hasTestTag("chat_template_folder_$folder"))
            onNodeWithTag("chat_template_folder_$folder").performClick()
        } else {
            val template = runBlocking { application.templateRepository.current() }.first { it.id == id }
            onNodeWithTag("chat_template_list").performScrollToNode(hasTestTag("chat_template_folder_mine"))
            onNodeWithTag("chat_template_folder_mine").performClick()
            val folders = runBlocking { application.templateFolderRepository.current() }
            val sub = template.folderId?.takeIf { fid -> folders.any { it.id == fid } }?.let { "chat_template_folder_mine_$it" } ?: "chat_template_folder_mine_none"
            await(sub)
            onNodeWithTag("chat_template_list").performScrollToNode(hasTestTag(sub))
            onNodeWithTag(sub).performClick()
        }
        await("chat_template_item_$id")
        onNodeWithTag("chat_template_list").performScrollToNode(hasTestTag("chat_template_item_$id"))
    }

    /** Opens the picker from the chat and picks [id]. */
    fun ComposeTestRule.pickTemplateThroughFolders(id: String, application: MemoRippleApplication) {
        onNodeWithTag("chat_plus").performClick()
        drillToTemplate(id, application)
        onNodeWithTag("chat_template_item_$id").performClick()
    }
}
