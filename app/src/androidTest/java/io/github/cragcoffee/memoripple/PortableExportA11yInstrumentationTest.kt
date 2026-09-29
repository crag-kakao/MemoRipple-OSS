package io.github.cragcoffee.memoripple

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import io.github.cragcoffee.memoripple.portableexport.PortableExportEngine
import io.github.cragcoffee.memoripple.ui.settings.PortableExportProgressDialog
import io.github.cragcoffee.memoripple.ui.settings.PortableExportResultDialog
import io.github.cragcoffee.memoripple.ui.settings.PortableExportSheet
import io.github.cragcoffee.memoripple.ui.settings.PortableExportViewModel
import io.github.cragcoffee.memoripple.ui.theme.MemoRippleTheme
import org.junit.Rule
import org.junit.Test

/**
 * The export sheet at the font scales people actually use, and with the semantics TalkBack
 * needs: at 1.0 / 1.3 / 1.5 every control still reaches the screen, the switches announce
 * themselves as toggleable, and the actions are real actions.
 */
class PortableExportA11yInstrumentationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val counts = PortableExportEngine.ExportCounts(
        activeMemos = 12,
        archivedMemos = 3,
        trashedMemos = 2,
        diaries = 5,
        notes = 1,
        photos = 4,
        estimatedPhotoBytes = 2_500_000,
    )

    private fun showSheet(fontScale: Float) {
        composeRule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalDensity provides Density(density, fontScale),
            ) {
                MemoRippleTheme {
                    PortableExportSheet(
                        counts = counts,
                        includeTrash = false,
                        onIncludeTrashChange = {},
                        stripPhotoMetadata = false,
                        onStripPhotoMetadataChange = {},
                        format = PortableExportViewModel.Format.ZIP,
                        onFormatChange = {},
                        onChooseDestination = {},
                        onDismiss = {},
                    )
                }
            }
        }
    }

    private fun everyControlStillWorks() {
        composeRule.onNodeWithTag("portable_export_counts").assertIsDisplayed()
        composeRule.onNodeWithTag("portable_export_format_zip")
            .performScrollTo().assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithTag("portable_export_format_pdf")
            .performScrollTo().assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithTag("portable_export_trash_toggle")
            .performScrollTo().assertIsDisplayed().assertIsToggleable()
        composeRule.onNodeWithTag("portable_export_strip_toggle")
            .performScrollTo().assertIsDisplayed().assertIsToggleable()
        composeRule.onNodeWithTag("portable_export_notes").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("portable_export_choose")
            .performScrollTo().assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithTag("portable_export_cancel")
            .performScrollTo().assertIsDisplayed().assertHasClickAction()
        // What TalkBack reads: the toggles are named by their visible labels.
        composeRule.onNodeWithText("ゴミ箱のメモも含める").assertIsDisplayed()
        composeRule.onNodeWithText("写真の位置情報などを取り除く").assertIsDisplayed()
    }

    @Test
    fun theSheetHoldsAtStandardScale() {
        showSheet(1.0f)
        everyControlStillWorks()
    }

    @Test
    fun theSheetHoldsAtLargeScale() {
        showSheet(1.3f)
        everyControlStillWorks()
    }

    @Test
    fun theSheetHoldsAtLargestScale() {
        showSheet(1.5f)
        everyControlStillWorks()
    }

    @Test
    fun thePdfChoiceExplainsItselfInsteadOfTheStripToggle() {
        composeRule.setContent {
            MemoRippleTheme {
                PortableExportSheet(
                    counts = counts,
                    includeTrash = false,
                    onIncludeTrashChange = {},
                    stripPhotoMetadata = false,
                    onStripPhotoMetadataChange = {},
                    format = PortableExportViewModel.Format.PDF,
                    onFormatChange = {},
                    onChooseDestination = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithTag("portable_export_pdf_note")
            .performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithTag("portable_export_strip_toggle")
            .fetchSemanticsNodes().let { assert(it.isEmpty()) }
    }

    @Test
    fun progressAndResultSpeakForThemselves() {
        composeRule.setContent {
            MemoRippleTheme {
                PortableExportProgressDialog(done = 3, total = 10, onCancel = {})
            }
        }
        composeRule.onNodeWithTag("portable_export_progress").assertIsDisplayed()
        composeRule.onNodeWithText("3 / 10").assertIsDisplayed()
        composeRule.onNodeWithTag("portable_export_stop")
            .assertIsDisplayed().assertHasClickAction()
    }

    @Test
    fun theResultDialogReadsItsMessage() {
        composeRule.setContent {
            MemoRippleTheme {
                PortableExportResultDialog(message = "読める形式で書き出しました", onDismiss = {})
            }
        }
        composeRule.onNodeWithTag("portable_export_result").assertIsDisplayed()
        composeRule.onNodeWithTag("portable_export_result_ok")
            .assertIsDisplayed().assertHasClickAction()
    }
}
