package io.github.cragcoffee.memoripple

import android.app.Activity
import android.app.Instrumentation
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import io.github.cragcoffee.memoripple.data.FutureDiaryCommentEntity
import io.github.cragcoffee.memoripple.data.AttachmentBlobEntity
import io.github.cragcoffee.memoripple.data.AttachmentBlobStore
import io.github.cragcoffee.memoripple.data.AttachmentKind
import io.github.cragcoffee.memoripple.data.DiaryPhotoAttachmentEntity
import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.MemoPhotoAttachmentEntity
import io.github.cragcoffee.memoripple.data.MemoTagCrossRef
import io.github.cragcoffee.memoripple.data.NoteEntity
import io.github.cragcoffee.memoripple.data.TagEntity
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.domain.settings.CommentSize
import io.github.cragcoffee.memoripple.domain.settings.PlaybackSpeed
import io.github.cragcoffee.memoripple.domain.settings.StageBackground
import io.github.cragcoffee.memoripple.overlay.OverlayPlaybackOptions
import io.github.cragcoffee.memoripple.overlay.OverlayPlaybackRequest
import io.github.cragcoffee.memoripple.overlay.OverlayPlaybackStatus
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.Espresso
import androidx.room.withTransaction
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtraWithKey
import androidx.test.espresso.intent.matcher.IntentMatchers.hasType
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.anyOf
import org.junit.Rule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

class MainActivityNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetSettingsBeforeTest() {
        runBlocking {
            // Every test starts from an empty app. Some cleared the tables themselves and some did
            // not, so what a test met depended on which tests ran before it: by the end of a suite
            // the wall held everything anyone had written, and screens that are fast alone were
            // slow enough to lose races here. Isolation is cheaper than chasing that.
            application().database.clearAllTables()
            application().settingsRepository.resetToDefaults()
            // Templates live beside the settings and outlive resetToDefaults; a template a test kept
            // must not greet the next run's 「テンプレートはまだありません」.
            application().templateRepository.replaceAll(emptyList())
            // The wall display is a device-local view preference, deliberately outside
            // resetToDefaults; tests share the device, so it is put back by hand.
            application().settingsRepository.setWallDisplayMode(
                io.github.cragcoffee.memoripple.domain.memos.WallDisplayMode.COMBINED,
            )
            application().settingsRepository.resetPlaybackStyle()
            application().settingsRepository.setDiaryCalendarCompact(true)
            application().settingsRepository.setKeepCommentExpression(false)
            application().settingsRepository.resetFavoriteComments()
            // The one-per-launch greeting would race any test that reads the wall's playback
            // state, so tests run with it off; its own test flips it back on deliberately.
            application().settingsRepository.setAutoPlayOnLaunch(false)
            application().settingsRepository.setWallSingleColumn(false)
            application().settingsRepository.setEditorToolbarTwoRows(false)
            application().settingsRepository.setEditorHideTags(false)
            // The editor's other device-local ways of working, also outside resetToDefaults:
            // whatever another test class chose must not reach this one.
            application().settingsRepository.setEditorToolbarOrder("")
            application().settingsRepository.setEditorToolbarNoteOrder("")
            application().settingsRepository.setEditorMenuCopyAll(true)
            application().settingsRepository.setEditorMenuPdfExport(true)
            application().settingsRepository.setEditorExportDocx(false)
            application().settingsRepository.setOutlineSymbolSet("")
            application().settingsRepository.setOutlineChipLabel("")
            application().settingsRepository.setSpeechRangeSupport("")
            application().settingsRepository.setThemeSeed(
                io.github.cragcoffee.memoripple.domain.settings.ThemeSeed.DEFAULT,
            )
            application().settingsRepository.setThemePaletteStyle(
                io.github.cragcoffee.memoripple.domain.settings.ThemePaletteStyle.TONAL_SPOT,
            )
            application().settingsRepository.setCommentFont(
                io.github.cragcoffee.memoripple.domain.settings.CommentFont.DEFAULT,
            )
            application().settingsRepository.setWallOverlayPlayback(false)
            application().settingsRepository.setCommentBackdrop(false)
            application().settingsRepository.setCommentTransparency(0f)
            application().settingsRepository.setWallPlaysContent(false)
            application().settingsRepository.resetSpeechDictionary()
            application().driveBackupCoordinator.setAutoBackupEnabled(false)
        }
        application().overlayPlaybackStateStore.idle()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(
                "app_settings_system_standard_standard_black",
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @After
    fun resetSettingsAfterTest() {
        runBlocking {
            application().settingsRepository.resetToDefaults()
            application().driveBackupCoordinator.setAutoBackupEnabled(false)
        }
        application().overlayPlaybackStateStore.idle()
    }

    @Test
    fun backupActionsLaunchCreateAndOpenDocumentsContracts() {
        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(
                Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null),
            )
            intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(
                Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null),
            )
            openSettingsFromTopLevel()
            composeRule.onNodeWithTag("settings_list")
                .performScrollToNode(hasTestTag("create_backup"))
            composeRule.onNodeWithTag("create_backup").performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                runCatching {
                    intended(allOf(hasAction(Intent.ACTION_CREATE_DOCUMENT), hasType("application/octet-stream")))
                }.isSuccess
            }
            composeRule.onNodeWithTag("settings_list")
                .performScrollToNode(hasTestTag("restore_backup"))
            composeRule.onNodeWithTag("restore_backup").performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                runCatching {
                    intended(
                        allOf(
                            hasAction(Intent.ACTION_OPEN_DOCUMENT),
                            hasExtraWithKey(Intent.EXTRA_MIME_TYPES),
                        ),
                    )
                }.isSuccess
            }
        } finally {
            Intents.release()
        }
    }

    @Test
    fun settingsShowsManualBackupAndRestoreActions() {
        openSettingsFromTopLevel()

        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("open_android_tts_settings"))
        composeRule.onNodeWithTag("open_android_tts_settings").assertIsDisplayed()
        composeRule.onNodeWithText("Androidの読み上げ設定を開く").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("create_backup"))
        composeRule.onNodeWithTag("create_backup").assertIsDisplayed()
        composeRule.onNodeWithText("バックアップを作成").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("restore_backup"))
        composeRule.onNodeWithTag("restore_backup").assertIsDisplayed()
        composeRule.onNodeWithText("バックアップから復元").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("drive_auto_backup"))
        composeRule.onNodeWithTag("drive_auto_backup").assertIsDisplayed()
        composeRule.onNodeWithText("自動バックアップ").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("drive_backup_now"))
        composeRule.onNodeWithTag("drive_backup_now").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("drive_restore"))
        composeRule.onNodeWithTag("drive_restore").assertIsDisplayed()
        // The portable rows sit with the manual backup section, clearly not the backup itself.
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("setting_portable_export"))
        composeRule.onNodeWithTag("setting_portable_export").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("setting_portable_import"))
        composeRule.onNodeWithTag("setting_portable_import").assertIsDisplayed()
    }

    @Test
    fun appInformationNavigatesToAccessibleAboutAndPrivacySurfaces() {
        openSettingsFromTopLevel()
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("settings_about"))

        composeRule.onNodeWithTag("settings_app_information_section").assertIsDisplayed()
        composeRule.onNode(isHeading() and hasText("アプリ情報")).assertIsDisplayed()
        composeRule.onNodeWithTag("settings_about")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        composeRule.onNodeWithTag("about_screen").assertIsDisplayed()
        composeRule.onNode(isHeading() and hasText("MemoRipple")).assertIsDisplayed()
        composeRule.onNodeWithText(
            "メモや日記を残し、時間を越えて自分の言葉と再会するためのアプリです。",
            substring = true,
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("バージョン、${BuildConfig.VERSION_NAME}")
            .performScrollTo()
            .assertIsDisplayed()
        val brandMark = composeRule.onNodeWithTag("about_brand_mark", useUnmergedTree = true)
            .fetchSemanticsNode()
        assertTrue(SemanticsProperties.ContentDescription !in brandMark.config)
        assertTrue(composeRule.onAllNodesWithTag("nav_memos").fetchSemanticsNodes().isEmpty())

        composeRule.onNodeWithContentDescription("戻る").performClick()
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("settings_privacy"))
        composeRule.onNodeWithTag("settings_privacy")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        composeRule.onNodeWithTag("privacy_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("privacy_screen")
            .performScrollToNode(hasText("端末内の記録"))
        composeRule.onNodeWithText("端末内の記録").assertIsDisplayed()
        composeRule.onNodeWithTag("privacy_screen")
            .performScrollToNode(hasText("封印中や受取待ちの未来コメント", substring = true))
        composeRule.onNodeWithText("封印中や受取待ちの未来コメント", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag("privacy_screen")
            .performScrollToNode(hasText("通常のDriveファイル一覧には表示されません。", substring = true))
        composeRule.onNodeWithText("通常のDriveファイル一覧には表示されません。", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("トークンはMemoRippleの永続データには保存しません。", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag("privacy_screen")
            .performScrollToNode(hasText("読み取ったり記録したりしません。", substring = true))
        composeRule.onNodeWithText("読み取ったり記録したりしません。", substring = true)
            .assertIsDisplayed()

        composeRule.onNodeWithContentDescription("戻る").performClick()
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("settings_version"))
        composeRule.onNodeWithContentDescription("バージョン、${BuildConfig.VERSION_NAME}")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("settings_version").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun appInformationOpensOfflineOssLicenseListAndFullText() {
        openSettingsFromTopLevel()
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("settings_oss_licenses"))
        composeRule.onNodeWithTag("settings_oss_licenses")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        composeRule.onNodeWithTag("oss_license_list").assertIsDisplayed()
        composeRule.onNodeWithText("オープンソースライセンス").assertIsDisplayed()
        composeRule.onNodeWithTag("oss_license_androidx")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        composeRule.onNodeWithTag("oss_license_detail").assertIsDisplayed()
        composeRule.onNode(isHeading() and hasText("AndroidX / Jetpack Compose"))
            .assertIsDisplayed()
        composeRule.onNodeWithText("Apache License 2.0").assertIsDisplayed()
        composeRule.onNodeWithTag("oss_license_detail")
            .performScrollToNode(hasTestTag("oss_license_text_0"))
        composeRule.onNodeWithTag("oss_license_text_0").fetchSemanticsNode()
        assertTrue(
            composeRule.onAllNodesWithText("Apache License", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )

        composeRule.onNodeWithContentDescription("戻る").performClick()
        composeRule.onNodeWithTag("oss_license_list").assertIsDisplayed()
    }

    @Test
    fun settingsShowsOverlayPermissionStatusAndDisclosureBeforeSystemSettings() {
        val settingsActions = anyOf(
            hasAction(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            hasAction(Settings.ACTION_SETTINGS),
        )
        Intents.init()
        try {
            intending(settingsActions).respondWith(
                Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null),
            )
            openSettingsFromTopLevel()
            composeRule.onNodeWithTag("settings_list")
                .performScrollToNode(hasTestTag("overlay_permission_settings"))
            composeRule.onNodeWithTag("overlay_permission_settings")
                .assertIsDisplayed()
            composeRule.onNodeWithText(
                if (Settings.canDrawOverlays(composeRule.activity)) "許可済み" else "未許可",
            ).assertIsDisplayed()
            composeRule.onNodeWithTag("overlay_permission_settings").performClick()
            composeRule.onNodeWithTag("settings_overlay_disclosure").assertIsDisplayed()
            composeRule.onNodeWithText("読み取ったり記録したりしません。", substring = true)
                .assertIsDisplayed()
            composeRule.onNodeWithTag("settings_open_overlay_permission").performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                runCatching { intended(settingsActions) }.isSuccess
            }
        } finally {
            Intents.release()
        }
    }

    @Test
    fun overlaySetupShowsRegionsDensitiesPreviewThenPermissionDisclosure() {
        composeRule.onNodeWithTag("create_memo").performClick()
        composeRule.onNodeWithTag("memo_body").performTextInput("# Overlay test")
        composeRule.onNodeWithTag("open_playback_settings").performClick()
        awaitNode("comment_playback_settings_sheet")
        selectPersistedChip("playback_mode_overlay")
        composeRule.onNodeWithTag("close_playback_settings").performClick()
        composeRule.onNodeWithTag("play_work_comments").performClick()
        composeRule.onNodeWithTag("overlay_setup_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("overlay_region_full").assertIsSelected()
        selectPersistedChip("overlay_region_top_half")
        selectPersistedChip("overlay_region_center")
        selectPersistedChip("overlay_region_bottom_half")
        selectPersistedChip("overlay_density_sparse")
        selectPersistedChip("overlay_density_standard")
        selectPersistedChip("overlay_density_dense")
        composeRule.onNodeWithTag("overlay_preview_item_count")
            .assertTextContains("1件", substring = true)
        composeRule.onNodeWithTag("overlay_preview_duration")
            .assertTextContains("約", substring = true)
        composeRule.onNodeWithTag("start_overlay_from_setup").assertIsEnabled().performClick()

        val notificationDisclosureExpected =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                composeRule.activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        when {
            !Settings.canDrawOverlays(composeRule.activity) -> composeRule.waitUntil(
                timeoutMillis = 5_000,
            ) {
                composeRule.onAllNodesWithTag("overlay_permission_disclosure")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            notificationDisclosureExpected -> composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("overlay_notification_disclosure")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            else -> composeRule.waitForIdle()
        }
        when {
            !Settings.canDrawOverlays(composeRule.activity) -> {
                composeRule.onNodeWithTag("overlay_permission_disclosure").assertIsDisplayed()
                composeRule.onNodeWithText("読み取ったり記録したりしません。", substring = true)
                    .assertIsDisplayed()
                composeRule.onNodeWithTag("cancel_overlay_disclosure").performClick()
            }
            notificationDisclosureExpected ->
                composeRule.onNodeWithTag("skip_overlay_notifications").performClick()
        }
        if (Settings.canDrawOverlays(composeRule.activity)) {
            composeRule.waitUntil(timeoutMillis = 5_000) {
                application().overlayPlaybackStateStore.state.value.status ==
                    OverlayPlaybackStatus.PLAYING
            }
            composeRule.onNodeWithTag("memo_body")
                .performClick()
                .performTextInput("\nオーバーレイ越しの入力")
            composeRule.onNodeWithTag("memo_body")
                .assertTextContains("オーバーレイ越しの入力", substring = true)
                .performTouchInput {
                    swipe(
                        start = center,
                        end = Offset(center.x, center.y - 400f),
                        durationMillis = 300,
                    )
                }
        }
        if (composeRule.onAllNodesWithTag("stop_overlay_playback").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("stop_overlay_playback").performClick()
        }
    }

    @Test
    fun commentFontIsChosenFromTheSettingsListAndShowsItsChoice() {
        openSettingsFromTopLevel()
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("setting_comment_font"))
        composeRule.onNodeWithTag("setting_comment_font")
            .assertTextContains("デフォルト", substring = true)
            .performClick()
        fun chooseFont(optionTag: String, expected: String) {
            awaitNode(optionTag)
            composeRule.onNodeWithTag(optionTag).performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule
                    .onAllNodesWithContentDescription("コメントのフォント、$expected")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("setting_comment_font").performClick()
        }
        chooseFont("comment_font_builtin_gothic", "ゴシック体")
        chooseFont("comment_font_builtin_mincho", "明朝体")
        awaitNode("comment_font_builtin_rounded")
        composeRule.onNodeWithTag("comment_font_builtin_rounded").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodesWithContentDescription("コメントのフォント、丸文字体")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun wallOverlayModeSendsThePlayButtonThroughTheOverlayGates() {
        val memoId = runBlocking {
            application().settingsRepository.setWallOverlayPlayback(true)
            val now = System.currentTimeMillis()
            application().database.memoDao().insert(
                MemoEntity(title = "壁", body = "# 骨\n流れる言葉", createdAt = now, updatedAt = now),
            )
        }
        awaitNode("memo_card_$memoId")
        composeRule.onNodeWithTag("wall_play").assertIsEnabled().performClick()

        val notificationDisclosureExpected =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                composeRule.activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        when {
            !Settings.canDrawOverlays(composeRule.activity) -> {
                composeRule.waitUntil(timeoutMillis = 5_000) {
                    composeRule.onAllNodesWithTag("overlay_permission_disclosure")
                        .fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithTag("cancel_overlay_disclosure").performClick()
            }
            notificationDisclosureExpected -> {
                composeRule.waitUntil(timeoutMillis = 5_000) {
                    composeRule.onAllNodesWithTag("overlay_notification_disclosure")
                        .fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithTag("skip_overlay_notifications").performClick()
                composeRule.waitUntil(timeoutMillis = 10_000) {
                    application().overlayPlaybackStateStore.state.value.status ==
                        OverlayPlaybackStatus.PLAYING
                }
            }
            else -> composeRule.waitUntil(timeoutMillis = 10_000) {
                application().overlayPlaybackStateStore.state.value.status ==
                    OverlayPlaybackStatus.PLAYING
            }
        }
        // The wall's own sky stayed quiet either way: the press went to the overlay's gates,
        // never to the inline stream.
        composeRule.onAllNodesWithTag("wall_pause").assertCountEquals(0)
        // No in-app control stops the overlay any more; the service route does, as the
        // notification's 停止 would.
        io.github.cragcoffee.memoripple.overlay.OverlayServiceStarter(
            application().overlayPlaybackStateStore,
        ).stop(application())
    }

    @Test
    fun emptyOverlaySetupShowsZeroPreviewAndDisablesStart() {
        composeRule.onNodeWithTag("create_memo").performClick()
        composeRule.onNodeWithTag("open_playback_settings").performClick()
        awaitNode("comment_playback_settings_sheet")
        composeRule.onNodeWithTag("playback_mode_overlay").performScrollTo().performClick()
        composeRule.onNodeWithTag("close_playback_settings").performClick()

        composeRule.onNodeWithTag("play_work_comments").assertIsEnabled().performClick()

        composeRule.onNodeWithTag("overlay_setup_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("overlay_preview_item_count")
            .assertTextContains("0件", substring = true)
        composeRule.onNodeWithTag("start_overlay_from_setup").assertIsNotEnabled()
    }

    @Test
    fun over120SecondResolvedOverlayPreviewDisablesStart() {
        // ニコニコ式は並ばない: 時間を作るのは射出の間隔だけ。見出しの溜め(500ms)と
        // 深さ5の段差(5×150ms)を足すと1行1550msになるので、90行で2分を優に超える。
        // 平文を数百行打ち込むより速く、編集画面を重くしない。
        val longOutline = (1..90).joinToString("\n") { "###### 長時間コメント$it" }
        composeRule.onNodeWithTag("create_memo").performClick()
        composeRule.onNodeWithTag("memo_body").performTextInput(longOutline)
        composeRule.onNodeWithTag("open_playback_settings").performClick()
        awaitNode("comment_playback_settings_sheet")
        composeRule.onNodeWithTag("playback_mode_overlay").performScrollTo().performClick()
        composeRule.onNodeWithTag("close_playback_settings").performClick()

        composeRule.onNodeWithTag("play_work_comments").performClick()

        composeRule.onNodeWithTag("overlay_preview_duration")
            .assertTextContains("2分以上", substring = true)
        composeRule.onNodeWithTag("start_overlay_from_setup").assertIsNotEnabled()
    }

    @Test
    fun activeOverlayRequiresConfirmationBeforeStartingAnotherSession() {
        val active = OverlayPlaybackRequest(99L, OverlayPlaybackOptions(), "active-session")
        application().overlayPlaybackStateStore.playing(
            request = active,
            itemCount = 1,
            durationMillis = 30_000L,
            startedElapsedRealtime = android.os.SystemClock.elapsedRealtime(),
        )
        composeRule.onNodeWithTag("create_memo").performClick()
        composeRule.onNodeWithTag("memo_body").performTextInput("# 新しい再生")
        composeRule.onNodeWithTag("open_playback_settings").performClick()
        awaitNode("comment_playback_settings_sheet")
        composeRule.onNodeWithTag("playback_mode_overlay").performScrollTo().performClick()
        composeRule.onNodeWithTag("close_playback_settings").performClick()

        composeRule.onNodeWithTag("start_new_overlay_playback").performClick()
        composeRule.onNodeWithTag("start_overlay_from_setup").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("replace_overlay_confirmation")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("replace_overlay_confirmation").assertIsDisplayed()
        composeRule.onNodeWithTag("cancel_replace_overlay").performClick()
        assertEquals(OverlayPlaybackStatus.PLAYING, application().overlayPlaybackStateStore.state.value.status)
    }

    @Test
    fun driveDisclosureAppearsBeforeAnyAuthorizationAction() {
        openSettingsFromTopLevel()
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("drive_auto_backup"))
        composeRule.onNodeWithTag("drive_auto_backup_switch").performClick()

        composeRule.onNodeWithTag("drive_backup_disclosure").assertIsDisplayed()
        composeRule.onNodeWithText("端末外のGoogle Driveへ送信します。", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("未来コメント（封印中の本文を含む）", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("通常のDriveファイル一覧には表示されません。", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("パスワード暗号化は行いません。", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag("cancel_drive_disclosure").performClick()
        composeRule.onNodeWithTag("drive_auto_backup_switch").assertIsDisplayed()
    }

    @Test
    fun androidSpeechSettingsActionIsResolvedBeforeLaunch() {
        val settingsActions = anyOf(
            hasAction("com.android.settings.TTS_SETTINGS"),
            hasAction(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            hasAction(Settings.ACTION_SETTINGS),
        )
        Intents.init()
        try {
            intending(settingsActions).respondWith(
                Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null),
            )
            openSettingsFromTopLevel()
            composeRule.onNodeWithTag("settings_list")
                .performScrollToNode(hasTestTag("open_android_tts_settings"))
            composeRule.onNodeWithTag("open_android_tts_settings").performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                runCatching { intended(settingsActions) }.isSuccess
            }
        } finally {
            Intents.release()
        }
    }

    @Test
    fun settingsPersistAcrossRecreationResetAndDriveEveryMemoStagePalette() {
        fun choose(rowTag: String, optionTag: String, expectedDescription: String) {
            composeRule.onNodeWithTag("settings_list")
                .performScrollToNode(hasTestTag(rowTag))
            composeRule.onNodeWithTag(rowTag).performClick()
            composeRule.onNodeWithTag(optionTag).performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithContentDescription(expectedDescription)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }

        openSettingsFromTopLevel()
        composeRule.onNodeWithText("設定").assertIsDisplayed()

        choose("setting_theme", "setting_theme_option_light", "外観、ライト")
        composeRule.onNodeWithContentDescription("外観、ライト").assertIsDisplayed()
        choose("setting_theme", "setting_theme_option_system", "外観、システム設定")
        composeRule.onNodeWithContentDescription("外観、システム設定").assertIsDisplayed()
        choose("setting_theme", "setting_theme_option_dark", "外観、ダーク")
        // The sliders write exact amounts; the backup vocabulary snaps to the nearest name,
        // which is what the scaffold's settings tag speaks.
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("setting_playback_speed"))
        composeRule.onNodeWithTag("playback_speed_slider").performSemanticsAction(
            SemanticsActions.SetProgress,
        ) { setProgress -> setProgress(1.25f) }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription("再生速度、×1.25")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("setting_comment_size"))
        composeRule.onNodeWithTag("comment_size_slider").performSemanticsAction(
            SemanticsActions.SetProgress,
        ) { setProgress -> setProgress(1.15f) }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription("コメントサイズ、115%")
                .fetchSemanticsNodes().isNotEmpty()
        }
        choose(
            "setting_stage_background",
            "setting_stage_background_option_dark_gray",
            "コメントステージ背景、ダークグレー",
        )
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("app_settings_dark_fast_large_dark_gray")
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.activityRule.scenario.recreate()
        // The list restores its scroll, so the theme row is not composed until it is asked for.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("settings_list").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("setting_theme"))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription("外観、ダーク")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("setting_playback_speed"))
        composeRule.onNodeWithContentDescription("再生速度、×1.25").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("setting_comment_size"))
        composeRule.onNodeWithContentDescription("コメントサイズ、115%").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("setting_stage_background"))
        composeRule.onNodeWithContentDescription("コメントステージ背景、ダークグレー")
            .assertIsDisplayed()

        composeRule.onNodeWithContentDescription("戻る").performClick()
        composeRule.onNodeWithTag("nav_calendar").performClick()
        composeRule.onNodeWithText("今日").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_memos").performClick()
        composeRule.onNodeWithTag("create_memo").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("open_playback_settings").performClick()
        awaitNode("comment_playback_settings_sheet")
        composeRule.onNodeWithTag("playback_mode_stage").performClick()
        composeRule.onNodeWithTag("close_playback_settings").performClick()
        StageBackground.entries.forEach { background ->
            runBlocking { application().settingsRepository.setStageBackground(background) }
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag(
                    "comment_stage_background_${background.storageId}",
                ).fetchSemanticsNodes().isNotEmpty()
            }
        }

        composeRule.onNodeWithContentDescription("戻る").performClick()
        openSettingsFromTopLevel()
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("reset_settings"))
        composeRule.onNodeWithTag("reset_settings").performClick()
        composeRule.onNodeWithTag("confirm_reset_settings").performClick()
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("setting_theme"))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription("外観、システム設定")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("setting_playback_speed"))
        composeRule.onNodeWithContentDescription("再生速度、×1.00").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("setting_comment_size"))
        composeRule.onNodeWithContentDescription("コメントサイズ、100%").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("setting_stage_background"))
        composeRule.onNodeWithContentDescription("コメントステージ背景、黒")
            .assertIsDisplayed()
    }

    @Test
    fun theReaderDrawsRubyOverItsWordsInsteadOfShowingTheMarks() {
        val ids = runBlocking {
            val now = System.currentTimeMillis()
            val noteId = application().database.noteDao().insert(
                NoteEntity(title = "ルビの本", coverColor = "teal", createdAt = now, updatedAt = now),
            )
            val memoId = application().database.memoDao().insert(
                MemoEntity(
                    title = "第一話",
                    body = "｜言葉《ことば》が来た。",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            application().database.noteDao().placeEpisode(memoId, noteId, null, 0)
            noteId to memoId
        }
        composeRule.onNodeWithTag("memo_view_note").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("open_note_${ids.first}").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("open_note_${ids.first}").performClick()
        awaitNode("note_episode_${ids.second}")
        composeRule.onNodeWithTag("note_episode_${ids.second}").performClick()
        awaitNode("note_reader_body")

        // The reading stands over the words; the written marks stay in the manuscript.
        composeRule.onNodeWithText("ことば").assertIsDisplayed()
        composeRule.onAllNodesWithText("《", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("｜", substring = true).assertCountEquals(0)
    }

    @Test
    fun theSpeechDictionaryLearnsAReadingAndKeepsItAcrossRecreation() {
        openSettingsFromTopLevel()
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("setting_speech_dictionary"))
        composeRule.onNodeWithTag("setting_speech_dictionary").performClick()
        awaitNode("speech_dictionary_empty")

        composeRule.onNodeWithTag("add_speech_dictionary_entry").performClick()
        awaitNode("speech_dictionary_dialog")
        composeRule.onNodeWithTag("speech_dictionary_surface_field")
            .performTextInput("兎にも角にも")
        composeRule.onNodeWithTag("speech_dictionary_reading_field")
            .performTextInput("とにもかくにも")
        composeRule.onNodeWithTag("speech_dictionary_save").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("兎にも角にも").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("とにもかくにも").assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("兎にも角にも").fetchSemanticsNodes().isNotEmpty()
        }

        // The entry is what the speech engine will actually be handed.
        val entries = runBlocking {
            application().settingsRepository.speechDictionary.first()
        }
        assertEquals(1, entries.size)
        assertEquals(
            "とにもかくにも、朝。",
            io.github.cragcoffee.memoripple.domain.speech.SpeechDictionary.apply(
                "兎にも角にも、朝。",
                entries,
            ),
        )
    }

    @Test
    fun bottomNavigationMovesBetweenMemoAndCalendar() {
        composeRule.onNodeWithTag("create_memo").assertIsDisplayed()
        composeRule.onNodeWithTag("memo_compact_top_bar").assertHeightIsAtLeast(56.dp)
        composeRule.onNodeWithTag("memo_view_memo").assertIsSelected()
        composeRule.onNodeWithTag("memo_view_note").assertIsDisplayed()
        // メモ / カレンダー are the only two destinations; the 日記 tab is gone (HANDOFF §16.18).
        assertTrue(composeRule.onAllNodesWithTag("nav_diary").fetchSemanticsNodes().isEmpty())
        val calendarNavigationBounds = composeRule.onNodeWithTag("nav_calendar")
            .getUnclippedBoundsInRoot()
        assertEquals(
            64.dp,
            calendarNavigationBounds.bottom - calendarNavigationBounds.top,
        )
        assertEquals(
            88.dp,
            calendarNavigationBounds.right - calendarNavigationBounds.left,
        )
        composeRule.onNodeWithTag("nav_memos").onParent()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))
        composeRule.onNodeWithTag("nav_calendar").performClick()
        composeRule.onNodeWithTag("calendar_compact_top_bar").assertHeightIsAtLeast(56.dp)
        // One page: the month grid is at the top, today is selected, and the bar stays.
        composeRule.onNodeWithTag("timeline_list").assertIsDisplayed()
        composeRule.onNodeWithTag("timeline_day_${application().timeProvider.currentLocalDate().toEpochDay()}")
            .assertIsSelected()
        composeRule.onNodeWithTag("nav_calendar").assertIsSelected()
        composeRule.onNodeWithTag("nav_memos").performClick()
        composeRule.onNodeWithTag("create_memo").assertIsDisplayed()
    }

    @Test
    fun theBarIsTheSearchFieldAndTypingNarrowsTheWallInPlace() {
        val memoId = runBlocking {
            application().database.clearAllTables()
            application().database.memoDao().insert(
                MemoEntity(title = "検索できるメモ", body = "本文", createdAt = 1, updatedAt = 1),
            )
        }
        awaitNode("memo_search")

        // Searching is not a mode: the field is the bar, and the first line takes the words.
        composeRule.onNodeWithTag("memo_search")
            .assertIsDisplayed()
            .assertContentDescriptionContains("メモを検索", substring = true)
            .performTextInput("検索")
        awaitNode("memo_card_$memoId")
        composeRule.onNodeWithTag("memo_card_$memoId").assertIsDisplayed()
        composeRule.onNodeWithTag("memo_search_clear").performClick()
        composeRule.onNodeWithTag("memo_search").assertIsDisplayed()
        // The tabs share the bar with the field the whole time.
        composeRule.onNodeWithTag("memo_view_memo").assertIsSelected()
    }

    @Test
    fun deliveredFutureCommentIsAnnouncedAtTheTopOfTheDiaryPage() {
        val application = application()
        runBlocking {
            application.database.clearAllTables()
            val today = application.timeProvider.currentLocalDate().toEpochDay()
            val diaryId = application.database.diaryDao().insertIgnoringConflict(
                DiaryEntryEntity(
                    diaryDateEpochDay = today,
                    body = "未来コメントを受け取る日記",
                    state = DiaryState.DRAFT,
                    createdAt = 1,
                    updatedAt = 1,
                ),
            )
            val now = application.timeProvider.nowMillis()
            application.database.futureDiaryCommentDao().insert(
                FutureDiaryCommentEntity(
                    diaryEntryId = diaryId,
                    text = "カレンダーからは見えない未来本文",
                    sealedAt = now - 2_000,
                    revealAt = now - 1_000,
                    deliveredAt = now,
                ),
            )
        }

        composeRule.onNodeWithTag("nav_calendar").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("receive_future_comment").fetchSemanticsNodes().isNotEmpty()
        }
        // One page now: the banner is the first thing on the Calendar tab, above the month, so a
        // delivery can never be on a different tab from the reader.
        composeRule.onNodeWithTag("future_delivery_banner").assertIsDisplayed()
        composeRule.onNodeWithTag("timeline_list").assertExists()
    }

    @Test
    fun memoArchiveAndTrashRoutesMoveEditAndRestoreWithoutBottomNavigation() {
        val memoId = runBlocking {
            application().database.clearAllTables()
            application().database.memoDao().insert(
                MemoEntity(title = "保管するメモ", body = "本文", createdAt = 10, updatedAt = 10),
            )
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("memo_card_$memoId").fetchSemanticsNodes().isNotEmpty()
        }
        // A card carries no controls of its own; holding it is how a memo is acted on.
        composeRule.onAllNodesWithTag("memo_more_$memoId").assertCountEquals(0)
        composeRule.onNodeWithTag("memo_card_$memoId").performTouchInput { longClick() }
        composeRule.onNodeWithTag("memo_action_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("memo_archive_$memoId").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("memo_card_$memoId").fetchSemanticsNodes().isEmpty()
        }

        composeRule.onNodeWithTag("memo_top_menu").performClick()
        composeRule.onNodeWithTag("open_archive").performClick()
        assertTrue(composeRule.onAllNodesWithTag("nav_memos").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("archive_memo_$memoId").performClick()
        composeRule.onNodeWithTag("memo_archived_indicator").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("戻る").performClick()
        composeRule.onNodeWithTag("archive_trash_$memoId").performClick()
        composeRule.onNodeWithContentDescription("戻る").performClick()

        composeRule.onNodeWithTag("memo_top_menu").performClick()
        composeRule.onNodeWithTag("open_trash").performClick()
        composeRule.onNodeWithTag("trash_memo_$memoId").assertIsDisplayed()
        composeRule.onNodeWithTag("restore_$memoId").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("trash_memo_$memoId").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithContentDescription("戻る").performClick()
        composeRule.onNodeWithTag("create_memo").assertIsDisplayed()
    }

    @Test
    fun diaryCalendarSelectsTodayNavigatesMonthsAndSurvivesRecreation() {
        runBlocking { application().database.clearAllTables() }
        val today = application().timeProvider.currentLocalDate()
        val currentMonth = YearMonth.from(today)
        val previousMonth = currentMonth.minusMonths(1)
        val pastEmptyDate = previousMonth.atDay(minOf(today.dayOfMonth, previousMonth.lengthOfMonth()))

        composeRule.onNodeWithTag("nav_calendar").performClick()
        awaitNode("open_journal_list")
        composeRule.onNodeWithTag("open_journal_list").performClick()
        // The diary page's calendar opens as one week; the title is the hinge to the whole month.
        composeRule.onNodeWithTag("diary_calendar").assertIsDisplayed()
        composeRule.onNodeWithTag("calendar_mode_toggle").performClick()
        awaitNode("calendar_previous_month")

        composeRule.onNodeWithTag("calendar_day_${today.toEpochDay()}")
            .assertIsSelected()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assertContentDescriptionContains("今日", substring = true)
            .assertContentDescriptionContains("選択中", substring = true)
        if (today.dayOfMonth < currentMonth.lengthOfMonth()) {
            composeRule.onNodeWithTag("calendar_day_${today.plusDays(1).toEpochDay()}")
                .assertIsNotEnabled()
                .assertContentDescriptionContains("未来", substring = true)
        }
        composeRule.onNodeWithTag("calendar_next_month").assertIsNotEnabled()

        composeRule.onNodeWithTag("calendar_previous_month").performClick()
        composeRule.onNodeWithText(
            previousMonth.format(DateTimeFormatter.ofPattern("yyyy年M月")),
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("calendar_next_month").assertIsEnabled()
        composeRule.onNodeWithTag("calendar_day_${pastEmptyDate.toEpochDay()}").performClick()
        composeRule.onNodeWithTag("diary_list")
            .performScrollToNode(hasTestTag("calendar_selected_summary"))
        composeRule.onNodeWithText("この日の日記はありません").assertIsDisplayed()

        composeRule.onNodeWithTag("diary_list")
            .performScrollToNode(hasTestTag("diary_calendar"))
        composeRule.onNodeWithTag("calendar_today").performClick()
        composeRule.onNodeWithTag("calendar_day_${today.toEpochDay()}").assertIsSelected()
        composeRule.onNodeWithTag("diary_list")
            .performScrollToNode(hasTestTag("calendar_selected_summary"))
        composeRule.onNodeWithTag("calendar_create_today").assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithTag("diary_list")
            .performScrollToNode(hasTestTag("calendar_selected_summary"))
        composeRule.onNodeWithTag("calendar_create_today").performClick()
        composeRule.onNodeWithTag("diary_body").assertIsDisplayed()
    }

    @Test
    fun theCalendarOpensAsOneWeekAndTheTitleUnfoldsTheMonth() {
        runBlocking { application().database.clearAllTables() }
        val today = application().timeProvider.currentLocalDate()

        composeRule.onNodeWithTag("nav_calendar").performClick()
        awaitNode("open_journal_list")
        composeRule.onNodeWithTag("open_journal_list").performClick()
        awaitNode("calendar_day_${today.toEpochDay()}")

        // One week: today is here, its own week around it, and nothing else of the month.
        composeRule.onNodeWithTag("calendar_day_${today.toEpochDay()}").assertIsSelected()
        // A same-month day at least a week away, so it exists in the month view whatever
        // today's date is, and never in today's week row.
        val outsideThisWeek = if (today.dayOfMonth >= 15) {
            today.withDayOfMonth(1)
        } else {
            today.withDayOfMonth(today.lengthOfMonth())
        }
        composeRule.onAllNodesWithTag("calendar_day_${outsideThisWeek.toEpochDay()}")
            .assertCountEquals(0)
        // The arrows step by week and carry the selection with them.
        composeRule.onNodeWithTag("calendar_next_week").assertIsNotEnabled()
        composeRule.onNodeWithTag("calendar_previous_week").performClick()
        val lastWeek = today.minusDays(7)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithTag("calendar_day_${lastWeek.toEpochDay()}")
                    .assertIsSelected()
            }.isSuccess
        }
        composeRule.onNodeWithTag("calendar_next_week").assertIsEnabled()
        composeRule.onNodeWithTag("calendar_today").performClick()
        composeRule.onNodeWithTag("calendar_day_${today.toEpochDay()}").assertIsSelected()

        // The title unfolds the month, and folds it away again.
        composeRule.onNodeWithTag("calendar_mode_toggle").performClick()
        awaitNode("calendar_day_${outsideThisWeek.toEpochDay()}")
        composeRule.onNodeWithTag("calendar_mode_toggle").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("calendar_day_${outsideThisWeek.toEpochDay()}")
                .fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun diaryPhotoRelationSurvivesRecreationAndOpenViewerClosesSafely() {
        val today = application().timeProvider.currentLocalDate()
        runBlocking {
            application().database.clearAllTables()
            val diaryId = application().database.diaryDao().insertIgnoringConflict(
                DiaryEntryEntity(
                    diaryDateEpochDay = today.toEpochDay(),
                    body = "写真付き日記",
                    state = DiaryState.DRAFT,
                    createdAt = 1,
                    updatedAt = 1,
                ),
            )
            val temp = application().attachmentBlobStore.newTempFile("diary-recreation")
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.eraseColor(Color.CYAN)
                temp.outputStream().use { output ->
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
                bitmap.recycle()
            }
            val size = temp.length()
            val sha = AttachmentBlobStore.hash(temp)
            // File and rows land under the repository's file lock, as production installs do:
            // outside it, the invalidation-scheduled garbage collector can sweep the file or
            // the blob row out from between the steps.
            application().attachmentRepository.installForRestore(
                listOf(Triple(temp, sha, size)),
            ) {
                application().database.withTransaction {
                    application().database.attachmentDao().insertBlob(
                        AttachmentBlobEntity(
                            sha256 = sha,
                            kind = AttachmentKind.IMAGE,
                            mimeType = "image/png",
                            sizeBytes = size,
                            widthPx = 8,
                            heightPx = 8,
                            createdAt = 1,
                        ),
                    )
                    application().database.attachmentDao().insertDiaryRelation(
                        DiaryPhotoAttachmentEntity(
                            diaryEntryId = diaryId,
                            blobSha256 = sha,
                            sortOrder = 0,
                            createdAt = 1,
                        ),
                    )
                }
            }
        }

        composeRule.onNodeWithTag("nav_calendar").performClick()
        awaitNode("open_journal_list")
        composeRule.onNodeWithTag("open_journal_list").performClick()
        awaitNode("calendar_open_diary")
        composeRule.onNodeWithTag("calendar_open_diary").performClick()
        // The editor arrives by navigation; asserting in the same frame as the click races it.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription("添付写真 1 / 1")
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The diary writes in blocks since Room 27 (docs/MEMO_CONTENT_BLOCKS.md §11): the picture
        // takes no tap while writing; its ⋮ opens the viewer.
        composeRule.onNodeWithContentDescription("添付写真 1 / 1").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("写真の操作").performClick()
        composeRule.onNodeWithTag("memo_photo_fullscreen").performClick()
        composeRule.onNodeWithContentDescription("閉じる").assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithText("写真付き日記").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("添付写真 1 / 1").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithContentDescription("閉じる").fetchSemanticsNodes().isEmpty())
        assertEquals(
            1,
            runBlocking { application().attachmentRepository.diaryPhotoCount(
                requireNotNull(application().database.diaryDao().findByDate(today.toEpochDay())).id,
            ) },
        )
        runBlocking {
            application().database.clearAllTables()
            application().attachmentRepository.garbageCollect()
        }
    }

    @Test
    fun memoPhotoReorderSheetMovesAccessiblyPersistsAndCancelKeepsStoredOrder() {
        val fixture = runBlocking {
            application().database.clearAllTables()
            val memoId = application().database.memoDao().insert(
                MemoEntity(title = "写真を並べ替えるメモ", body = "本文", createdAt = 1, updatedAt = 1),
            )
            val ids = listOf(Color.RED, Color.GREEN, Color.BLUE).mapIndexed { index, color ->
                val temp = java.io.File(
                    application().cacheDir,
                    "photo-reorder-$index-${System.nanoTime()}.png",
                )
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(color)
                    temp.outputStream().use { output ->
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    }
                    bitmap.recycle()
                }
                val size = temp.length()
                val sha = AttachmentBlobStore.hash(temp)
                application().attachmentRepository.installForRestore(
                    listOf(Triple(temp, sha, size)),
                ) {
                    application().database.withTransaction {
                        application().database.attachmentDao().insertBlob(
                            AttachmentBlobEntity(
                                sha256 = sha,
                                kind = AttachmentKind.IMAGE,
                                mimeType = "image/png",
                                sizeBytes = size,
                                widthPx = 8,
                                heightPx = 8,
                                createdAt = 2L + index,
                            ),
                        )
                        application().database.attachmentDao().insertMemoRelation(
                            MemoPhotoAttachmentEntity(
                                memoId = memoId,
                                blobSha256 = sha,
                                sortOrder = index,
                                createdAt = 10L + index,
                            ),
                        )
                    }
                }
            }
            memoId to ids
        }
        val (memoId, originalIds) = fixture
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("memo_card_$memoId").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("memo_card_$memoId").performClick()
        leaveReadingModeIfShown()
        composeRule.onNodeWithTag("memo_editor_more").performClick()
        composeRule.onNodeWithTag("open_photo_reorder").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("写真を並べ替え").assertIsDisplayed()
        assertTrue(
            composeRule.onNodeWithTag("photo_reorder_row_${originalIds[0]}")
                .fetchSemanticsNode().config[SemanticsActions.CustomActions]
                .any { it.label == "後ろへ移動" },
        )
        composeRule.onNodeWithTag(
            "photo_drag_handle_${originalIds[0]}",
            useUnmergedTree = true,
        ).performTouchInput {
            down(center)
            advanceEventTime(700)
            moveTo(Offset(center.x, center.y + 300f), delayMillis = 300)
            up()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("photo_reorder_row_${originalIds[0]}")
            .assertContentDescriptionEquals("写真 2 / 3")
        composeRule.onNodeWithTag("save_photo_order").performClick()

        composeRule.waitUntil(5_000) {
            runBlocking {
                application().attachmentRepository.observeMemoPhotos(memoId).first()
                    .map { it.id } == listOf(originalIds[1], originalIds[0], originalIds[2])
            }
        }
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithTag("memo_editor_more").performClick()
        composeRule.onNodeWithTag("open_photo_reorder").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("photo_reorder_row_${originalIds[1]}")
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == "後ろへ移動" }.action()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("キャンセル").performClick()
        assertEquals(
            listOf(originalIds[1], originalIds[0], originalIds[2]),
            runBlocking {
                application().attachmentRepository.observeMemoPhotos(memoId).first().map { it.id }
            },
        )
        runBlocking {
            application().database.clearAllTables()
            application().attachmentRepository.garbageCollect()
        }
    }

    @Test
    fun diaryPhotoReorderIsOfferedWhileEditableAndWithheldOnALockedEntry() {
        val today = application().timeProvider.currentLocalDate()
        runBlocking {
            application().database.clearAllTables()
            val diaryId = application().database.diaryDao().insertIgnoringConflict(
                DiaryEntryEntity(
                    diaryDateEpochDay = today.toEpochDay(),
                    body = "写真順序を確認する日記",
                    state = DiaryState.DRAFT,
                    createdAt = 1,
                    updatedAt = 1,
                ),
            )
            listOf(Color.CYAN, Color.MAGENTA).forEachIndexed { index, color ->
                val source = java.io.File(
                    application().cacheDir,
                    "diary-photo-reorder-$index-${System.nanoTime()}.png",
                )
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(color)
                    source.outputStream().use { output ->
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    }
                    bitmap.recycle()
                }
                val size = source.length()
                val sha = AttachmentBlobStore.hash(source)
                application().attachmentRepository.installForRestore(
                    listOf(Triple(source, sha, size)),
                ) {
                    application().database.withTransaction {
                        application().database.attachmentDao().insertBlob(
                            AttachmentBlobEntity(
                                sha,
                                AttachmentKind.IMAGE,
                                "image/png",
                                size,
                                8,
                                8,
                                2L + index,
                            ),
                        )
                        application().database.attachmentDao().insertDiaryRelation(
                            DiaryPhotoAttachmentEntity(
                                diaryEntryId = diaryId,
                                blobSha256 = sha,
                                sortOrder = index,
                                createdAt = 10L + index,
                            ),
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("nav_calendar").performClick()
        awaitNode("open_journal_list")
        composeRule.onNodeWithTag("open_journal_list").performClick()
        awaitNode("calendar_open_diary")
        composeRule.onNodeWithTag("calendar_open_diary").performClick()
        // Since Room 27 the page counts nothing: 並べ替え lives in a photo's ⋮ → 写真一覧.
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithContentDescription("写真の操作").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(composeRule.onAllNodesWithTag("open_photo_reorder").fetchSemanticsNodes().isEmpty())
        composeRule.onAllNodesWithContentDescription("写真の操作")[0].performClick()
        composeRule.onNodeWithTag("memo_photo_overview").performClick()
        awaitNode("open_photo_reorder")
        composeRule.onNodeWithTag("open_photo_reorder").assertIsDisplayed()
        Espresso.pressBack()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("memo_photo_overview_sheet").fetchSemanticsNodes().isEmpty() }

        // A LOCKED row (the retired lifecycle's end state) is the one that withholds the action.
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitNode("calendar_open_diary")
        runBlocking {
            val entry = requireNotNull(application().database.diaryDao().findByDate(today.toEpochDay()))
            application().database.diaryDao().update(entry.copy(state = DiaryState.LOCKED, lockedAt = 5))
        }
        composeRule.onNodeWithTag("calendar_open_diary").performClick()
        awaitNode("diary_state_label")
        composeRule.onAllNodesWithContentDescription("写真の操作")[0].performClick()
        assertTrue(composeRule.onAllNodesWithTag("memo_photo_delete").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("memo_photo_overview").performClick()
        awaitNode("memo_photo_overview_sheet")
        assertTrue(composeRule.onAllNodesWithTag("open_photo_reorder").fetchSemanticsNodes().isEmpty())
        runBlocking {
            application().database.clearAllTables()
            application().attachmentRepository.garbageCollect()
        }
    }

    @Test
    fun calendarAndPastTodayOpenExistingLockedDiaryWithoutFutureSendAction() {
        runBlocking { application().database.clearAllTables() }
        val today = application().timeProvider.currentLocalDate()
        val pastToday = LocalDate.of(today.year - 1, today.month, today.dayOfMonth)
        val previousMonth = YearMonth.from(today).minusMonths(1)
        val monthDiaryDate = previousMonth.atDay(minOf(10, previousMonth.lengthOfMonth()))
        val (pastTodayId, monthDiaryId) = runBlocking {
            listOf(
                pastToday to "一年前の同じ日の日記",
                monthDiaryDate to "前月のカレンダー日記",
            ).map { (date, body) ->
                application().database.diaryDao().insertIgnoringConflict(
                    DiaryEntryEntity(
                        diaryDateEpochDay = date.toEpochDay(),
                        body = body,
                        state = DiaryState.LOCKED,
                        createdAt = 1,
                        updatedAt = 1,
                        lockedAt = 1,
                    ),
                )
            }
        }

        // 過去の今日 lives on the Calendar tab now, under the day's rows (scrolled to: a lazy list).
        composeRule.onNodeWithTag("nav_calendar").performClick()
        awaitNode("timeline_list")
        composeRule.onNodeWithTag("timeline_list")
            .performScrollToNode(hasTestTag("past_today_${pastToday.toEpochDay()}"))
        composeRule.onNodeWithText("過去の今日").assertIsDisplayed()
        composeRule.onNodeWithTag("past_today_open_${pastToday.toEpochDay()}")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("diary_body").assertTextContains("一年前の同じ日の日記")
        assertTrue(
            composeRule.onAllNodesWithTag("open_future_comment_creator")
                .fetchSemanticsNodes().isEmpty(),
        )
        assertNotEquals(0L, pastTodayId)

        // Back on the tab: the previous month's day carries the journal mark and its row opens it.
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitNode("timeline_list")
        composeRule.onNodeWithTag("timeline_list")
            .performScrollToNode(hasTestTag("timeline_previous_month"))
        composeRule.onNodeWithTag("timeline_previous_month").performClick()
        awaitNode("timeline_day_${monthDiaryDate.toEpochDay()}")
        composeRule.onNodeWithTag("timeline_day_${monthDiaryDate.toEpochDay()}")
            .assertContentDescriptionContains("日記", substring = true)
            .performClick()
        awaitNode("timeline_item_journal_$monthDiaryId")
        composeRule.onNodeWithTag("timeline_item_journal_$monthDiaryId").performClick()
        awaitNode("diary_body")
        composeRule.onNodeWithTag("diary_body").assertTextContains("前月のカレンダー日記")
        assertTrue(
            composeRule.onAllNodesWithTag("open_future_comment_creator")
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun memoDiaryAndSearchEmptyStatesExplainWhatToDoNext() {
        runBlocking { application().database.clearAllTables() }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_empty_state").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("まだメモがありません").assertIsDisplayed()
        composeRule.onNodeWithText("メモを書いて、読み返した自分のコメントを流せます。")
            .assertIsDisplayed()
        // The floating button is the only place that offers to write, on a full wall and an
        // empty one alike, so there is exactly one of it here too.
        composeRule.onAllNodesWithTag("create_memo").assertCountEquals(1)
        composeRule.onNodeWithTag("create_memo").assertIsDisplayed()
        // The bar stays: a stable frame, even over an empty wall.
        composeRule.onNodeWithTag("memo_search").assertIsDisplayed()

        runBlocking {
            application().database.memoDao().insert(
                MemoEntity(
                    title = "検索対象",
                    body = "本文",
                    createdAt = 1,
                    updatedAt = 1,
                ),
            )
        }
        awaitNode("memo_card_" + runBlocking {
            application().database.memoDao().observeMemos("").first().first().id
        })
        composeRule.onNodeWithTag("memo_search").performTextInput("見つからない検索語")
        composeRule.onNodeWithTag("memo_search_empty_state").assertIsDisplayed()
        // The empty result is where the search syntax is taught, so the copy names what to try.
        composeRule.onNodeWithText("語を減らすと広がります", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("#タグでタグだけ", substring = true).assertIsDisplayed()

        composeRule.onNodeWithTag("nav_calendar").performClick()
        // 「すべて」 is the month (2026-09-23): an empty calendar speaks about the month
        awaitNode("timeline_month_empty")
        composeRule.onNodeWithTag("timeline_month_empty").assertIsDisplayed()
        composeRule.onNodeWithTag("open_journal_list").performClick()
        awaitNode("diary_list")
        composeRule.onNodeWithText("今日の日記を書く").assertIsDisplayed()
        composeRule.onNodeWithTag("diary_past_empty_state").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("書いた日記はここから読み返せます。").assertIsDisplayed()
    }

    @Test
    fun memoOrganizationControlsComposeAndSurviveConfiguration() {
        val ids = runBlocking {
            application().database.clearAllTables()
            val alpha = application().database.memoDao().insert(
                MemoEntity(title = "Alpha", body = "sort target", createdAt = 10, updatedAt = 300),
            )
            val zulu = application().database.memoDao().insert(
                MemoEntity(
                    title = "Zulu",
                    body = "already pinned",
                    createdAt = 20,
                    updatedAt = 100,
                    isPinned = true,
                ),
            )
            val beta = application().database.memoDao().insert(
                MemoEntity(title = "Beta", body = "pin target", createdAt = 30, updatedAt = 200),
            )
            listOf(alpha, zulu, beta)
        }
        val (alphaId, zuluId, betaId) = ids
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_card_$alphaId").fetchSemanticsNodes().isNotEmpty()
        }
        val generationBeforePin =
            application().driveBackupCoordinator.persistedState.value.changeGeneration

        composeRule.onNodeWithTag("memo_card_$betaId").performTouchInput { longClick() }
        composeRule.onNodeWithTag("memo_pin_$betaId").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            application().driveBackupCoordinator.persistedState.value.changeGeneration >
                generationBeforePin
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            comesBefore("memo_card_$betaId", "memo_card_$zuluId")
        }

        openMemoDisplayOptions()
        composeRule.onNodeWithTag("memo_filter_PINNED").performClick()
        awaitDisplayed("memo_card_$betaId")
        composeRule.onNodeWithTag("memo_card_$betaId").assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_card_$alphaId").fetchSemanticsNodes().isEmpty()
        }
        assertTrue(composeRule.onAllNodesWithTag("memo_card_$alphaId").fetchSemanticsNodes().isEmpty())

        chooseSort("memo_sort_TITLE_ASC")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            comesBefore("memo_card_$betaId", "memo_card_$zuluId")
        }

        openMemoSearch()
        composeRule.onNodeWithTag("memo_search").performTextInput("Alpha")
        awaitNode("memo_search_empty_state")
        composeRule.onNodeWithTag("memo_search_empty_state").assertIsDisplayed()
        openMemoDisplayOptions()
        composeRule.onNodeWithTag("memo_filter_ALL").performClick()
        awaitDisplayed("memo_card_$alphaId")
        composeRule.onNodeWithTag("memo_card_$alphaId").assertIsDisplayed()
        // an order (タイトル順 here) is a way of looking, not a narrowing (2026-09-21 23:15): no 「タイトル順　クリア」 strip
        composeRule.onAllNodesWithTag("memo_active_filters").assertCountEquals(0)

        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithTag("memo_search").assertTextContains("Alpha")
        openMemoDisplayOptions()
        composeRule.onNodeWithTag("memo_filter_ALL").assertIsSelected()
        dismissDisplayOptions()
        composeRule.onNodeWithTag("memo_overflow").performClick()
        awaitNode("overflow_sort")
        composeRule.onNodeWithTag("overflow_sort").performClick()
        awaitNode("memo_sort_TITLE_ASC")
        composeRule.onNodeWithTag("memo_sort_TITLE_ASC").assertIsSelected()
        composeRule.onNodeWithTag("memo_sort_TITLE_ASC").performClick()

        // Pinning from inside the editor reaches the same state the card menu does.
        composeRule.onNodeWithTag("memo_card_$alphaId").performClick()
        leaveReadingModeIfShown()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_editor_more").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("memo_editor_more").performClick()
        composeRule.onNodeWithTag("memo_editor_pin").performClick()
        composeRule.onNodeWithContentDescription("戻る").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { application().database.memoDao().findById(alphaId) }?.isPinned == true
        }
    }

    @Test
    fun memoTagDisplayFilterAndOrganizationCompositionStayReactive() {
        val ids = runBlocking {
            application().database.clearAllTables()
            val plainId = application().database.memoDao().insert(
                MemoEntity(
                    title = "小説Alpha",
                    body = "検索対象",
                    createdAt = 1,
                    updatedAt = 3,
                ),
            )
            val pinnedId = application().database.memoDao().insert(
                MemoEntity(
                    title = "小説Beta",
                    body = "別の対象",
                    createdAt = 2,
                    updatedAt = 2,
                    isPinned = true,
                ),
            )
            val otherId = application().database.memoDao().insert(
                MemoEntity(title = "買い物", body = "日用品", createdAt = 3, updatedAt = 1),
            )
            val tagId = application().database.tagDao().insert(
                TagEntity(name = "小説", normalizedName = "小説", createdAt = 4),
            )
            application().database.tagDao().attach(MemoTagCrossRef(plainId, tagId))
            application().database.tagDao().attach(MemoTagCrossRef(pinnedId, tagId))
            listOf(plainId, pinnedId, otherId, tagId)
        }
        val (plainId, pinnedId, otherId, tagId) = ids
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("小説").fetchSemanticsNodes().size >= 2
        }

        openMemoDisplayOptions()
        composeRule.onNodeWithTag("memo_tag_filter").performClick()
        composeRule.onNodeWithTag("tag_filter_search").performTextInput("小説")
        // Injecting a tap while the IME is still animating races with the sheet layout, so settle
        // the keyboard first. The sheet itself now keeps its actions reachable regardless.
        closeSoftKeyboard()
        composeRule.onNodeWithTag("tag_filter_$tagId").performClick()
        composeRule.onNodeWithTag("apply_tag_filter").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_card_$otherId").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("memo_card_$plainId").assertIsDisplayed()
        composeRule.onNodeWithTag("memo_card_$pinnedId").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("memo_card_$otherId").fetchSemanticsNodes().isEmpty())

        openMemoSearch()
        composeRule.onNodeWithTag("memo_search").performTextInput("Alpha")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_card_$pinnedId").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("memo_card_$plainId").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("memo_card_$pinnedId").fetchSemanticsNodes().isEmpty())
        openMemoDisplayOptions()
        composeRule.onNodeWithTag("memo_filter_ALL").performClick()
        composeRule.onNodeWithTag("memo_card_$plainId").assertIsDisplayed()

        composeRule.onNodeWithTag("memo_search").performTextClearance()
        openMemoDisplayOptions()
        composeRule.onNodeWithTag("memo_filter_PINNED").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_card_$plainId").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("memo_card_$pinnedId").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("memo_card_$plainId").fetchSemanticsNodes().isEmpty())

        composeRule.activityRule.scenario.recreate()
        openMemoDisplayOptions()
        composeRule.onNodeWithTag("memo_tag_filter")
            .assertContentDescriptionContains("小説", substring = true)
        composeRule.onNodeWithTag("memo_filter_PINNED").assertIsSelected()
        composeRule.onNodeWithContentDescription("閉じる").performClick()

        runBlocking { application().database.tagDao().rename(tagId, "物語", "物語") }
        openMemoDisplayOptions()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithTag("memo_tag_filter")
                    .assertContentDescriptionContains("物語", substring = true)
            }.isSuccess
        }
        composeRule.onNodeWithContentDescription("閉じる").performClick()

        runBlocking {
            application().tagRepository.delete(
                requireNotNull(application().database.tagDao().findById(tagId)),
            )
        }
        openMemoDisplayOptions()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithTag("memo_tag_filter")
                    .assertContentDescriptionContains("タグで絞り込み、指定なし", substring = false)
            }.isSuccess
        }
        composeRule.onNodeWithContentDescription("閉じる").performClick()
        composeRule.onNodeWithTag("memo_card_$pinnedId").assertIsDisplayed()
    }

    @Test
    fun editorTagPickerAndManagementSupportAssignmentDuplicateRenameAndDelete() {
        val ids = runBlocking {
            application().database.clearAllTables()
            val memoId = application().database.memoDao().insert(
                MemoEntity(title = "タグ編集", body = "本文", createdAt = 1, updatedAt = 1),
            )
            val coffeeId = application().database.tagDao().insert(
                TagEntity(name = "Coffee", normalizedName = "coffee", createdAt = 2),
            )
            val materialId = application().database.tagDao().insert(
                TagEntity(name = "資料", normalizedName = "資料", createdAt = 3),
            )
            application().database.tagDao().attach(MemoTagCrossRef(memoId, coffeeId))
            listOf(memoId, coffeeId, materialId)
        }
        val (memoId, coffeeId, materialId) = ids
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_card_$memoId").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("memo_card_$memoId").performClick()
        leaveReadingModeIfShown()
        composeRule.onNodeWithTag("editor_tag_$coffeeId").assertIsDisplayed()

        // A chip is drawn chip-sized and stays reachable: Material stretches the touch target
        // past the box, so the row does not have to be 48dp tall to be usable.
        val chipHeight = composeRule.onNodeWithTag("open_tag_picker")
            .getUnclippedBoundsInRoot().height
        assertTrue("the tag chip was drawn $chipHeight tall", chipHeight < 40.dp)
        composeRule.onNodeWithTag("open_tag_picker").assertTouchHeightIsEqualTo(48.dp)

        composeRule.onNodeWithTag("open_tag_picker").performClick()
        composeRule.onNodeWithTag("tag_picker_$materialId").performClick()
        composeRule.onNodeWithContentDescription("閉じる").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("editor_tag_$materialId").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("editor_tag_$materialId").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("editor_tag_$materialId").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithContentDescription("戻る").performClick()

        composeRule.onNodeWithTag("memo_top_menu").performClick()
        composeRule.onNodeWithTag("manage_tags").performClick()
        composeRule.onNodeWithTag("create_tag").performClick()
        composeRule.onNodeWithTag("tag_name_input").performTextInput("アイデア")
        composeRule.onNodeWithTag("confirm_tag_name").performClick()
        composeRule.onNodeWithText("アイデア").assertIsDisplayed()

        composeRule.onNodeWithTag("create_tag").performClick()
        composeRule.onNodeWithTag("tag_name_input").performTextInput("ＣＯＦＦＥＥ")
        composeRule.onNodeWithTag("confirm_tag_name").performClick()
        composeRule.onNodeWithText("同じ名前のタグがあります").assertIsDisplayed()
        composeRule.onNodeWithText("キャンセル").performClick()

        composeRule.onNodeWithTag("tag_more_$coffeeId").performClick()
        composeRule.onNodeWithTag("rename_tag_$coffeeId").performClick()
        composeRule.onNodeWithTag("tag_name_input").performTextClearance()
        composeRule.onNodeWithTag("tag_name_input").performTextInput("小説")
        composeRule.onNodeWithTag("confirm_tag_name").performClick()
        composeRule.onNodeWithText("小説").assertIsDisplayed()

        composeRule.onNodeWithTag("tag_more_$coffeeId").performClick()
        composeRule.onNodeWithTag("delete_tag_$coffeeId").performClick()
        composeRule.onNodeWithText("タグを削除").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("tag_more_$coffeeId").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithContentDescription("戻る").performClick()
        composeRule.onNodeWithTag("memo_card_$memoId").assertIsDisplayed()
    }

    @Test
    fun memoSelectionBulkTagsMultiFilterSelectAllArchiveAndUndo() {
        val ids = runBlocking {
            application().database.clearAllTables()
            val first = application().database.memoDao().insert(
                MemoEntity(title = "一括A", body = "", createdAt = 1, updatedAt = 3),
            )
            val second = application().database.memoDao().insert(
                MemoEntity(title = "一括B", body = "", createdAt = 2, updatedAt = 2),
            )
            val third = application().database.memoDao().insert(
                MemoEntity(title = "一括C", body = "", createdAt = 3, updatedAt = 1),
            )
            val a = application().database.tagDao().insert(
                TagEntity(name = "A", normalizedName = "a", createdAt = 4),
            )
            val b = application().database.tagDao().insert(
                TagEntity(name = "B", normalizedName = "b", createdAt = 5),
            )
            application().database.tagDao().attach(MemoTagCrossRef(first, a))
            application().database.tagDao().attach(MemoTagCrossRef(second, a))
            application().database.tagDao().attach(MemoTagCrossRef(second, b))
            application().database.tagDao().attach(MemoTagCrossRef(third, b))
            listOf(first, second, third, a, b)
        }
        val (first, second, third, a, b) = ids
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("memo_card_$first").fetchSemanticsNodes().isNotEmpty()
        }

        // Holding asks about that memo; picking several is one of the answers.
        composeRule.onNodeWithTag("memo_card_$first").performTouchInput { longClick() }
        composeRule.onNodeWithTag("memo_select_$first").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("1件を選択").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("memo_card_$first").assertIsSelected()
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText("1件を選択").assertIsDisplayed()
        composeRule.onNodeWithTag("memo_card_$first").assertIsSelected()
        // Selection replaces the whole bar: nothing is searched while memos are being picked.
        composeRule.onAllNodesWithTag("memo_search").assertCountEquals(0)
        composeRule.onNodeWithTag("memo_bulk_menu").performClick()
        composeRule.onNodeWithTag("bulk_add_tags").performClick()
        composeRule.onNodeWithTag("bulk_tag_$b").performClick()
        composeRule.onNodeWithTag("apply_bulk_tags").performClick()
        composeRule.waitUntil(5_000) {
            runBlocking {
                application().tagRepository.observeTagsForMemo(first).first().any { it.id == b }
            }
        }
        composeRule.onNodeWithTag("memo_search").assertIsDisplayed()

        openMemoDisplayOptions()
        composeRule.onNodeWithTag("memo_tag_filter").performClick()
        composeRule.onNodeWithTag("tag_filter_$a").performClick()
        composeRule.onNodeWithTag("tag_filter_$b").performClick()
        composeRule.onNodeWithTag("tag_match_all").performClick()
        composeRule.onNodeWithTag("apply_tag_filter").performClick()
        composeRule.onNodeWithTag("memo_card_$first").assertIsDisplayed()
        composeRule.onNodeWithTag("memo_card_$second").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("memo_card_$third").fetchSemanticsNodes().isEmpty())

        composeRule.onNodeWithTag("memo_overflow").performClick()
        awaitNode("request_memo_selection")
        composeRule.onNodeWithTag("request_memo_selection").performClick()
        composeRule.onNodeWithText("0件を選択").assertIsDisplayed()
        composeRule.onNodeWithTag("memo_bulk_menu").performClick()
        composeRule.onNodeWithTag("select_all_visible").performClick()
        composeRule.onNodeWithText("2件を選択").assertIsDisplayed()
        composeRule.onNodeWithTag("memo_bulk_menu").performClick()
        composeRule.onNodeWithTag("bulk_archive").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("memo_card_$first").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText("元に戻す").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("memo_card_$first").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun aNoteIsNamedBeforeItExistsAndItsCoverColourIsTheWritersToPick() {
        runBlocking { application().database.clearAllTables() }
        composeRule.onNodeWithTag("memo_view_note").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("note_empty_state").fetchSemanticsNodes().isNotEmpty()
        }

        // The button is a plus; what it makes is said in the label a screen reader hears.
        composeRule.onNodeWithTag("create_note").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("新しいノート").assertExists()
        composeRule.onNodeWithTag("create_note").performClick()

        // Nothing is made until it is named, and leaving makes nothing.
        awaitNode("note_title_dialog")
        composeRule.onNodeWithTag("note_title_close").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("note_title_dialog").fetchSemanticsNodes().isEmpty()
        }
        runBlocking {
            assertTrue(application().database.noteDao().observeSummaries().first().isEmpty())
        }

        composeRule.onNodeWithTag("create_note").performClick()
        awaitNode("note_title_field")
        composeRule.onNodeWithTag("note_title_field").performTextInput("夜明け前に君と")
        awaitEnabled("note_title_next")
        composeRule.onNodeWithTag("note_title_next").performClick()
        // The second page asks for the optional line, so it is asked second and can be left empty.
        awaitNode("note_subtitle_field")
        composeRule.onNodeWithTag("note_subtitle_field").performTextInput("港の話")
        composeRule.onNodeWithTag("note_subtitle_done").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("note_detail_title").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("note_detail_title").assertTextContains("夜明け前に君と")
        composeRule.onNodeWithTag("note_detail_subtitle").assertTextContains("港の話")
        // The first one is written; the ones after it come next.
        composeRule.onNodeWithTag("note_write_next_episode").assertTextContains("エピソードを執筆")
        composeRule.onNodeWithText("章とエピソード").assertIsDisplayed()
        composeRule.onNodeWithTag("note_arrange_mode").assertTextContains("章と並び順の編集")

        // The colour is reachable, and picking one keeps it.
        composeRule.onNodeWithTag("note_detail_menu").performClick()
        awaitNode("note_cover_color")
        composeRule.onNodeWithTag("note_cover_color").performClick()
        awaitNode("note_cover_indigo")
        composeRule.onNodeWithTag("note_cover_indigo").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking {
                application().database.noteDao().observeSummaries().first()
                    .any { summary -> summary.coverColor == "indigo" }
            }
        }
    }

    @Test
    fun holdingANoteAsksAboutThatNoteTheWayHoldingAMemoDoes() {
        val noteId = runBlocking {
            application().database.clearAllTables()
            application().database.noteDao().insert(
                NoteEntity(
                    title = "夜明け前に君と",
                    subtitle = "港の話",
                    coverColor = "plum",
                    createdAt = 1,
                    updatedAt = 1,
                ),
            )
        }

        composeRule.onNodeWithTag("memo_view_note").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("note_row_$noteId").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("open_note_$noteId").performTouchInput { longClick() }
        awaitNode("note_action_sheet")

        // The cover colour is reachable from the shelf too, without opening the note.
        composeRule.onNodeWithTag("note_sheet_cover_$noteId").performClick()
        awaitNode("note_cover_indigo")
        composeRule.onNodeWithTag("note_cover_indigo").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking {
                application().database.noteDao().observeSummaries().first()
                    .any { it.coverColor == "indigo" }
            }
        }

        composeRule.onNodeWithTag("open_note_$noteId").performTouchInput { longClick() }
        awaitNode("note_sheet_subtitle_$noteId")
        composeRule.onNodeWithTag("note_sheet_subtitle_$noteId").performClick()
        awaitNode("note_subtitle_from_list_field")
        composeRule.onNodeWithTag("note_subtitle_from_list_field").performTextInput("と灯台")
        composeRule.onNodeWithTag("note_subtitle_from_list_confirm").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking {
                application().database.noteDao().observeSummaries().first()
                    .any { it.subtitle.contains("灯台") }
            }
        }

        // Deleting a note is offered here too, and it says what survives.
        composeRule.onNodeWithTag("open_note_$noteId").performTouchInput { longClick() }
        awaitNode("note_sheet_delete_$noteId")
        composeRule.onNodeWithTag("note_sheet_delete_$noteId").performClick()
        awaitNode("note_delete_from_list")
        composeRule.onNodeWithText("話はメモとして残ります。消えるのは並び順と章だけです。")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("note_delete_from_list_confirm").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { application().database.noteDao().observeSummaries().first().isEmpty() }
        }
    }

    @Test
    fun anOutlineBecomesANoteWithoutTypingItsHeadingsAgain() {
        val ids = runBlocking {
            application().database.clearAllTables()
            val bones = application().database.memoDao().insert(
                MemoEntity(
                    title = "短編のための覚書",
                    body = "この話は港から始まる\n" +
                        "■ 発端\n- 港に着いた日のこと\n" +
                        "  ■ 港の場面\n  - 灯台守はもういない\n" +
                        "■ 結末",
                    createdAt = 1,
                    updatedAt = 2,
                ),
            )
            val prose = application().database.memoDao().insert(
                MemoEntity(title = "散文だけ", body = "記法のない一行。", createdAt = 1, updatedAt = 1),
            )
            bones to prose
        }
        val (bonesId, proseId) = ids

        // A memo with no heading is not asked what note it should become.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_card_$proseId").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("memo_card_$proseId").performTouchInput { longClick() }
        composeRule.onAllNodesWithTag("memo_make_episodes_$proseId").assertCountEquals(0)
        composeRule.onNodeWithTag("memo_action_sheet").performTouchInput { swipeDown() }

        // The same wall holds the memo with bones; no tab stands between them any more.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_card_$bonesId").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("memo_card_$bonesId").performTouchInput { longClick() }
        awaitNode("memo_make_episodes_$bonesId")
        composeRule.onNodeWithTag("memo_make_episodes_$bonesId").performClick()
        awaitNode("note_picker_sheet")
        composeRule.onNodeWithText("見出しから話を作る").assertIsDisplayed()
        composeRule.onNodeWithTag("note_pick_new").performClick()

        composeRule.onNodeWithTag("memo_view_note").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("短編のための覚書").fetchSemanticsNodes().isNotEmpty()
        }
        // Every heading became an episode, nesting and all, and the words came with them.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking {
                application().database.memoDao().observeMemos("").first()
                    .any { it.title == "港の場面" && it.body == "- 灯台守はもういない" }
            }
        }
        runBlocking {
            val all = application().database.memoDao().observeMemos("").first()
            val made = all.filter { it.noteId != null }.sortedBy { it.episodeOrder }
            assertEquals(listOf("発端", "港の場面", "結末"), made.map(MemoEntity::title))
            assertEquals("- 港に着いた日のこと", made[0].body)
            // The outline is left exactly as it was; the bridge runs one way.
            val source = requireNotNull(all.firstOrNull { it.id == bonesId })
            assertEquals(null, source.noteId)
            assertTrue(source.body.startsWith("この話は港から始まる"))
        }
    }

    @Test
    fun proseSymbolsAreWrittenByTheToolbarAndBracketsCloseThemselves() {
        // The prose tools belong to prose: an episode carries them and a loose memo does not,
        // just as the outline machinery stays with the loose memo and leaves the episode.
        val ids = runBlocking {
            application().database.clearAllTables()
            val noteId = application().database.noteDao().insert(
                NoteEntity(title = "夜明け前に君と", coverColor = "plum", createdAt = 1, updatedAt = 1),
            )
            val episode = application().database.memoDao().insert(
                MemoEntity(title = "港の灯", body = "", createdAt = 1, updatedAt = 1),
            )
            application().database.noteDao().placeEpisode(episode, noteId, null, 0)
            noteId to episode
        }
        val (noteId, episodeId) = ids

        composeRule.onNodeWithTag("memo_view_note").performClick()
        awaitNode("toggle_note_$noteId")
        composeRule.onNodeWithTag("toggle_note_$noteId").performClick()
        awaitNode("episode_row_$episodeId")
        composeRule.onNodeWithTag("episode_row_$episodeId").performClick()
        // An episode opens to be read; writing is one door further, through 編集.
        awaitNode("reader_edit")
        composeRule.onNodeWithTag("reader_edit").performClick()
        awaitNode("memo_body")
        composeRule.onNodeWithTag("memo_body").performTextInput("行くぞ")

        // A bracket opened by hand gets its partner, and the caret stays between the two.
        composeRule.onNodeWithTag("memo_body").performTextInput("「")
        composeRule.onNodeWithTag("memo_body").assertTextContains("行くぞ「」", substring = true)
        composeRule.onNodeWithTag("memo_body").performTextInput("と彼は言った")
        composeRule.onNodeWithTag("memo_body")
            .assertTextContains("行くぞ「と彼は言った」", substring = true)

        // The marks a novel needs on every other line, none of which is one flick away.
        composeRule.onNodeWithTag("toolbar_ellipsis").performScrollTo().performClick()
        composeRule.onNodeWithTag("toolbar_dash").performScrollTo().performClick()
        composeRule.onNodeWithTag("memo_body").assertTextContains("……――", substring = true)

        composeRule.onNodeWithTag("toolbar_ruby").performScrollTo().performClick()
        composeRule.onNodeWithTag("memo_body").assertTextContains("｜《》", substring = true)

        // The episode's toolbar has no outline machinery.
        composeRule.onAllNodesWithTag("outline_helper_0").assertCountEquals(0)
        composeRule.onAllNodesWithTag("toolbar_task").assertCountEquals(0)

        // And a loose memo has no prose symbols. Back once to the reader, once to the wall.
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitNode("reader_edit")
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitNode("memo_view_memo")
        composeRule.onNodeWithTag("memo_view_memo").performClick()
        composeRule.onNodeWithTag("create_memo").performClick()
        awaitNode("memo_body")
        composeRule.onNodeWithTag("memo_body").performClick()
        composeRule.onNodeWithTag("toolbar_task").assertExists()
        composeRule.onAllNodesWithTag("toolbar_ruby").assertCountEquals(0)
    }

    @Test
    fun anEpisodeIsReadInItsNoteAndIsNotAlsoOnTheWall() {
        val ids = runBlocking {
            application().database.clearAllTables()
            val noteId = application().database.noteDao().insert(
                NoteEntity(title = "夜明け前に君と", coverColor = "plum", createdAt = 1, updatedAt = 1),
            )
            // Each tab is asked separately, so each needs a memo of its own kind: what a body is
            // marked with decides which of the two it is read on.
            val loose = application().database.memoDao().insert(
                MemoEntity(title = "買い物", body = "牛乳", createdAt = 1, updatedAt = 1),
            )
            val looseOutline = application().database.memoDao().insert(
                MemoEntity(title = "構想", body = "■ 発端\n- 港", createdAt = 2, updatedAt = 2),
            )
            val episode = application().database.memoDao().insert(
                MemoEntity(title = "港の灯", body = "一話", createdAt = 3, updatedAt = 3),
            )
            val episodeOutline = application().database.memoDao().insert(
                MemoEntity(title = "二話の骨", body = "■ 場面\n- 灯台", createdAt = 4, updatedAt = 4),
            )
            application().database.noteDao().placeEpisode(episode, noteId, null, 0)
            application().database.noteDao().placeEpisode(episodeOutline, noteId, null, 1)
            listOf(noteId, loose, looseOutline, episode, episodeOutline)
        }
        val (noteId, looseId, looseOutlineId, episodeId, episodeOutlineId) = ids

        composeRule.onNodeWithTag("memo_view_memo").performClick()
        awaitNode("memo_card_$looseId")
        // One wall holds both kinds of loose memo, and neither episode.
        composeRule.onNodeWithTag("memo_card_$looseOutlineId").assertExists()
        composeRule.onAllNodesWithTag("memo_card_$episodeId").assertCountEquals(0)
        composeRule.onAllNodesWithTag("memo_card_$episodeOutlineId").assertCountEquals(0)

        // They are not gone, only spoken for: the note they belong to still reads them.
        runBlocking {
            assertEquals(
                listOf(episodeId, episodeOutlineId),
                application().database.noteDao().observeEpisodes(noteId).first().map { it.id },
            )
        }
    }

    @Test
    fun episodesAreMovedPastEachOtherAndTheNumbersFollow() {
        val ids = runBlocking {
            application().database.clearAllTables()
            val noteId = application().database.noteDao().insert(
                NoteEntity(title = "夜明け前に君と", coverColor = "plum", createdAt = 1, updatedAt = 1),
            )
            val first = application().database.memoDao().insert(
                MemoEntity(title = "港の灯", body = "一話", createdAt = 1, updatedAt = 1),
            )
            val second = application().database.memoDao().insert(
                MemoEntity(title = "手紙", body = "二話", createdAt = 2, updatedAt = 2),
            )
            application().database.noteDao().placeEpisode(first, noteId, null, 0)
            application().database.noteDao().placeEpisode(second, noteId, null, 1)
            Triple(noteId, first, second)
        }
        val (noteId, firstMemoId, secondMemoId) = ids

        composeRule.onNodeWithTag("memo_view_note").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("note_row_$noteId").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("open_note_$noteId").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("note_episode_$firstMemoId").fetchSemanticsNodes()
                .isNotEmpty()
        }

        // A row is something to read until arranging is asked for, so no row carries a handle yet.
        composeRule.onAllNodesWithTag("note_drag_episode_$firstMemoId").assertCountEquals(0)
        assertTrue(comesBefore("note_episode_$firstMemoId", "note_episode_$secondMemoId"))

        composeRule.onNodeWithTag("note_arrange_mode").assertTextContains("章と並び順の編集")
        composeRule.onNodeWithTag("note_arrange_mode").performClick()
        composeRule.onNodeWithTag("note_drag_episode_$firstMemoId").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("港の灯を並べ替える").assertExists()
        // Adding a chapter belongs to the mode that arranges, so it appears with the handles.
        composeRule.onNodeWithTag("note_add_chapter_inline").assertIsDisplayed()

        composeRule.onNodeWithTag("note_drag_episode_$secondMemoId")
            .performTouchInput { swipeUp(startY = centerY, endY = centerY - 400f) }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            comesBefore("note_episode_$secondMemoId", "note_episode_$firstMemoId")
        }

        // A heading crossing an episode takes it over: the note is one run of rows, and what an
        // episode belongs to is read from what stands above it.
        composeRule.onNodeWithTag("note_add_chapter_inline").performClick()
        awaitNode("note_add_chapter_dialog_field")
        composeRule.onNodeWithTag("note_add_chapter_dialog_field").performTextInput("章")
        composeRule.onNodeWithTag("note_add_chapter_at_end").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("章").fetchSemanticsNodes().isNotEmpty()
        }
        // The heading is last, so both episodes are still outside it.
        runBlocking {
            val all = application().database.memoDao().observeMemos("").first()
            assertTrue(all.filter { it.noteId != null }.all { it.chapterId == null })
        }
        val tailChapter = runBlocking {
            application().database.noteDao().chapters(noteId).last().id
        }
        // The row lands where it is let go: carried a little over one row up, its middle is
        // nearest the place one episode above, so that one episode is stepped over.
        val episodeRow = composeRule.onNodeWithTag("note_episode_$firstMemoId").getUnclippedBoundsInRoot().height
        val oneRowUp = with(composeRule.density) { (episodeRow * 1.2f).toPx() }
        composeRule.onNodeWithTag("note_drag_chapter_$tailChapter")
            .performTouchInput { swipeUp(startY = centerY, endY = centerY - oneRowUp) }
        // Having stepped over one episode, the heading now has that episode under it.
        fun underHeading() = runBlocking {
            application().database.memoDao().observeMemos("").first().count { it.chapterId != null }
        }
        runCatching {
            composeRule.waitUntil(timeoutMillis = 5_000) { underHeading() == 1 }
        }.onFailure {
            throw AssertionError("one episode should stand under the heading, found ${underHeading()}", it)
        }

        // A chapter answers to the same mode: its own handle and its own menu appear with it,
        // and a new one can be put at either end of the note.
        composeRule.onNodeWithTag("note_add_chapter_inline").performClick()
        awaitNode("note_add_chapter_dialog_field")
        composeRule.onNodeWithTag("note_add_chapter_dialog_field").performTextInput("序")
        composeRule.onNodeWithTag("note_add_chapter_at_start").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("序").fetchSemanticsNodes().isNotEmpty()
        }
        val headChapter = runBlocking {
            application().database.noteDao().chapters(noteId).first().id
        }
        composeRule.onNodeWithTag("note_drag_chapter_$headChapter").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("序を並べ替える").assertExists()

        // Leaving the mode puts the handles away again, the chapter's menu with them.
        composeRule.onNodeWithTag("note_arrange_mode").assertTextContains("完了")
        composeRule.onNodeWithTag("note_arrange_mode").performClick()
        composeRule.onAllNodesWithTag("note_drag_episode_$firstMemoId").assertCountEquals(0)
        composeRule.onAllNodesWithTag("note_add_chapter_inline").assertCountEquals(0)
        composeRule.onAllNodesWithTag("note_drag_chapter_$headChapter").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("序の操作").assertCountEquals(0)
    }

    @Test
    fun outlineHelpersMeetMinimumTouchTargetAndExposeTheirMeaning() {
        composeRule.onNodeWithTag("create_memo").performClick()
        // The writing aids come with the writing posture: they appear when a field takes focus.
        composeRule.onNodeWithTag("memo_body").performClick()

        listOf("見出し", "項目", "補足", "重要", "疑問")
            .forEachIndexed { index, label ->
                // A chip is drawn at chip height and reaches 48dp through its touch target, so
                // that is where the minimum has to be read.
                composeRule.onNodeWithTag("outline_helper_$index")
                    .performScrollTo()
                    .assertIsDisplayed()
                    .assertWidthIsAtLeast(48.dp)
                    .assertTouchHeightIsEqualTo(48.dp)
                // The chip wears its glyph before the word now (■ 見出し), so the word is
                // found as a substring.
                composeRule.onNodeWithText(label, substring = true).assertIsDisplayed()
            }

        listOf(
            "toolbar_add_photo" to "写真を追加",
            "toolbar_undo" to "取り消す",
            "toolbar_redo" to "やり直す",
            "toolbar_outdent" to "階層を上げる",
            "toolbar_indent" to "階層を下げる",
        ).forEach { (tag, description) ->
            composeRule.onNodeWithTag(tag)
                .performScrollTo()
                .assertHeightIsAtLeast(48.dp)
                .assertWidthIsAtLeast(48.dp)
            composeRule.onNodeWithContentDescription(description).assertExists()
        }

    }

    @Test
    fun outlineHelperMarksTheLineHoldingTheCursorAndTogglesBackOff() {
        composeRule.onNodeWithTag("create_memo").performClick()
        composeRule.onNodeWithTag("memo_body").performTextInput("一行目\n二行目")
        closeSoftKeyboard()
        composeRule.waitForIdle()

        // Caret sits on the second line after typing, so the marker must land there, not at the end.
        composeRule.onNodeWithTag("outline_helper_0").performScrollTo().performClick()
        composeRule.onNodeWithTag("memo_body").assertTextContains("一行目\n■ 二行目")

        composeRule.onNodeWithTag("outline_helper_0").performClick()
        composeRule.onNodeWithTag("memo_body").assertTextContains("一行目\n二行目")
    }

    @Test
    fun checkboxGoesRoundOnTheCursorLineAndTheCountFollowsWhatIsWritten() {
        composeRule.onNodeWithTag("create_memo").performClick()
        composeRule.onNodeWithTag("memo_body").performTextInput("買い物")
        closeSoftKeyboard()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("editor_stats").assertTextContains("3文字", substring = true)

        composeRule.onNodeWithTag("toolbar_task").performScrollTo().performClick()
        composeRule.onNodeWithTag("memo_body").assertTextContains("- [ ] 買い物")
        composeRule.onNodeWithTag("editor_stats").assertTextContains("タスク 0/1", substring = true)

        composeRule.onNodeWithTag("toolbar_task").performClick()
        composeRule.onNodeWithTag("memo_body").assertTextContains("- [x] 買い物")
        composeRule.onNodeWithTag("editor_stats").assertTextContains("タスク 1/1", substring = true)

        composeRule.onNodeWithTag("toolbar_task").performClick()
        composeRule.onNodeWithTag("memo_body").assertTextContains("買い物")
        composeRule.onNodeWithTag("editor_stats").assertTextContains("3文字", substring = true)
    }

    @Test
    fun aSecondSearchWordNarrowsAndTheCardShowsTheLineItMatched() {
        runBlocking { application().database.clearAllTables() }
        val ids = runBlocking {
            val both = application().database.memoDao().insert(
                MemoEntity(
                    title = "打ち合わせ",
                    body = "冒頭の行\n**会議**の資料をまとめる",
                    createdAt = 1,
                    updatedAt = 2,
                ),
            )
            val onlyOne = application().database.memoDao().insert(
                MemoEntity(title = "会議だけ", body = "会議の予定", createdAt = 1, updatedAt = 1),
            )
            both to onlyOne
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_card_${ids.first}").fetchSemanticsNodes().isNotEmpty()
        }

        openMemoSearch()
        composeRule.onNodeWithTag("memo_search").performTextInput("会議 資料")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_card_${ids.second}").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("memo_card_${ids.first}").assertIsDisplayed()
        // The card shows the line that was hit, with the markers off — and only that line,
        // not the top of the memo: while a search is on, the card answers where it landed.
        composeRule.onNodeWithTag("memo_preview_${ids.first}", useUnmergedTree = true)
            .assertTextContains("会議の資料をまとめる", substring = true)
        val previewText = composeRule.onNodeWithTag(
            "memo_preview_${ids.first}",
            useUnmergedTree = true,
        ).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        assertFalse(previewText.contains("冒頭の行"))
    }

    @Test
    fun aLinkedMemoIsReachableFromBothEndsOfTheLink() {
        runBlocking { application().database.clearAllTables() }
        val targetId = runBlocking {
            application().database.memoDao().insert(
                MemoEntity(title = "買い物リスト", body = "牛乳", createdAt = 1, updatedAt = 1),
            )
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_card_$targetId").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("create_memo").performClick()
        composeRule.onNodeWithTag("memo_title").performTextInput("週末の予定")
        composeRule.onNodeWithTag("memo_body").performTextInput("あとで ")
        closeSoftKeyboard()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("toolbar_link").performScrollTo().performClick()
        composeRule.onNodeWithTag("memo_link_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("memo_link_target_$targetId").performClick()
        composeRule.onNodeWithTag("memo_body").assertTextContains("[[買い物リスト]]", substring = true)

        closeSoftKeyboard()
        composeRule.waitForIdle()
        // The reference resolves, so the memo it names can be reached from here.
        composeRule.onNodeWithTag("memo_link_out_買い物リスト").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("買い物リスト").fetchSemanticsNodes().isNotEmpty()
        }
        // And from the memo that was named, the memo naming it is reachable in turn.
        composeRule.onNodeWithTag("memo_links").assertIsDisplayed()
        composeRule.onNodeWithText("このメモを参照").assertIsDisplayed()
    }

    @Test
    fun aTemplateIsKeptAndThenWrittenBackIntoAnotherMemo() {
        runBlocking { application().database.clearAllTables() }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_empty_state").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("create_memo").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_body").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("memo_body").performTextInput("# 週報\n- 今週やったこと")
        closeSoftKeyboard()
        composeRule.waitForIdle()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_editor_more").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("memo_editor_more").performClick()
        composeRule.onNodeWithTag("memo_editor_save_template").performClick()
        composeRule.onNodeWithTag("template_save_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("template_name_field").performTextInput("週報のかたち")
        composeRule.onNodeWithTag("template_save_confirm").performClick()
        closeSoftKeyboard()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("toolbar_template").performScrollTo().performClick()
        composeRule.onNodeWithTag("memo_template_sheet").assertIsDisplayed()
        composeRule.onNodeWithText("週報のかたち", substring = true).performClick()
        composeRule.onNodeWithTag("memo_body")
            .assertTextContains("- 今週やったこと", substring = true)
    }

    @Test
    fun proseFlowsOnlyWhenTheScopeSaysTheWholeBodyDoes() {
        runBlocking { application().database.clearAllTables() }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_empty_state").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("create_memo").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_body").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("memo_body")
            .performTextInput("■ 見出し\n記法のない本文です。二つ目の文です。")
        closeSoftKeyboard()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("open_playback_settings").performClick()
        composeRule.onNodeWithTag("comment_scope_outline").performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag("comment_scope_body").performScrollTo().performClick()
        composeRule.onNodeWithTag("close_playback_settings").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("open_playback_settings").performClick()
        // The choice is kept, so it is the setting rather than a one-off.
        composeRule.onNodeWithTag("comment_scope_body").performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag("close_playback_settings").performClick()

        // Prose now has something to send, so playback has something to start.
        closeSoftKeyboard()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("play_work_comments").assertIsEnabled()

        runBlocking {
            application().settingsRepository.setCommentScope(
                io.github.cragcoffee.memoripple.domain.WorkCommentScope.OUTLINE,
            )
        }
    }

    @Test
    fun theWallIsOneAndTheDisplayOptionsOnlyNarrowIt() {
        runBlocking { application().database.clearAllTables() }
        val ids = runBlocking {
            val plain = application().database.memoDao().insert(
                MemoEntity(title = "散文のメモ", body = "ただの本文です。", createdAt = 1, updatedAt = 2),
            )
            val outlined = application().database.memoDao().insert(
                MemoEntity(title = "骨のメモ", body = "■ 見出し\n- 項目", createdAt = 1, updatedAt = 1),
            )
            plain to outlined
        }
        val (plainId, outlinedId) = ids
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_card_$plainId").fetchSemanticsNodes().isNotEmpty()
        }

        // One wall holds both, and the structured one draws its bones inside its card.
        composeRule.onNodeWithTag("memo_card_$plainId").assertIsDisplayed()
        composeRule.onNodeWithTag("memo_card_$outlinedId").assertIsDisplayed()

        // Narrowing to メモのみ is the old memo tab, kept as a way of looking.
        switchWallDisplay("memo_wall_MEMO")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_card_$outlinedId").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("memo_card_$plainId").assertIsDisplayed()
        // The chosen view is a mode, not a narrowing: no banner, no クリア.
        composeRule.onAllNodesWithTag("memo_active_filters").assertCountEquals(0)

        // アウトラインのみ is the old shelf: same sizes, only bones.
        switchWallDisplay("memo_wall_OUTLINE")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_sheet_$outlinedId").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithTag("memo_sheet_$plainId").assertCountEquals(0)

        // A narrowed view that is empty says so instead of lying about the wall.
        switchWallDisplay("memo_wall_MEMO")
        runBlocking {
            application().database.memoDao().updateContent(plainId, "散文のメモ", "■ 見出しを付けた", 3)
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_card_$plainId").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("memo_plain_empty_state").assertIsDisplayed()
        composeRule.onAllNodesWithTag("memo_empty_state").assertCountEquals(0)

        // Choosing メモとアウトライン restores the whole wall.
        switchWallDisplay("memo_wall_COMBINED")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_card_$plainId").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun holdingAMemoActsOnItOnBothWallsWithoutAnyControlOnTheCard() {
        runBlocking { application().database.clearAllTables() }
        val memoId = runBlocking {
            application().database.memoDao().insert(
                MemoEntity(title = "押さえるメモ", body = "本文だけ", createdAt = 1, updatedAt = 1),
            )
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_card_$memoId").fetchSemanticsNodes().isNotEmpty()
        }
        // A card at rest carries nothing to press but itself.
        composeRule.onAllNodesWithTag("memo_more_$memoId").assertCountEquals(0)
        composeRule.onAllNodesWithTag("memo_selected_$memoId", useUnmergedTree = true)
            .assertCountEquals(0)

        // Holding asks about this memo, and the answer is about it.
        composeRule.onNodeWithTag("memo_card_$memoId").performTouchInput { longClick() }
        composeRule.onNodeWithTag("memo_action_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("memo_pin_$memoId").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { application().database.memoDao().findById(memoId) }?.isPinned == true
        }

        // Picking several is one of the answers rather than the only one.
        composeRule.onNodeWithTag("memo_card_$memoId").performTouchInput { longClick() }
        composeRule.onNodeWithTag("memo_select_$memoId").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("1件を選択").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("memo_selected_$memoId", useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag("memo_bulk_menu").performClick()
        composeRule.onNodeWithTag("bulk_unpin").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { application().database.memoDao().findById(memoId) }?.isPinned == false
        }

        // The shelf display is held the same way, on a memo that shows there.
        val outlinedId = runBlocking {
            application().database.memoDao().insert(
                MemoEntity(title = "骨のメモ", body = "■ 見出し", createdAt = 1, updatedAt = 5),
            )
        }
        switchWallDisplay("memo_wall_OUTLINE")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_sheet_$outlinedId").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("memo_sheet_$outlinedId").performTouchInput { longClick() }
        composeRule.onNodeWithTag("memo_action_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("memo_duplicate_$outlinedId").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("骨のメモのコピー").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun theWallSpeaksOnlyWhenAskedAndSaysDifferentThingsOnEachTab() {
        runBlocking { application().database.clearAllTables() }
        val ids = runBlocking {
            val bones = application().database.memoDao().insert(
                MemoEntity(
                    title = "骨のあるメモ",
                    body = "■ 見出しの言葉\n散文の言葉です。",
                    createdAt = 1,
                    updatedAt = 2,
                ),
            )
            val prose = application().database.memoDao().insert(
                MemoEntity(title = "散文だけ", body = "記法のない一行。", createdAt = 1, updatedAt = 1),
            )
            bones to prose
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_card_${ids.second}").fetchSemanticsNodes().isNotEmpty()
        }

        // Nothing flows until it is asked for. The one wall holds both memos.
        composeRule.onNodeWithTag("memo_card_${ids.second}").assertIsDisplayed()
        composeRule.onNodeWithTag("memo_card_${ids.first}").assertIsDisplayed()
        composeRule.onNodeWithTag("wall_play").assertIsDisplayed().assertIsEnabled()
        composeRule.onAllNodesWithTag("wall_pause").assertCountEquals(0)

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("wall_play").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag("wall_pause").assertIsDisplayed().performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag("wall_stop").assertIsDisplayed().performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.autoAdvance = true
        composeRule.onNodeWithTag("wall_play").assertIsDisplayed()

        // Narrowed to bones, the wall still has something to say about a fully marked memo.
        switchWallDisplay("memo_wall_OUTLINE")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_sheet_${ids.first}").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("wall_play").assertIsEnabled()

        // The note tab is not a wall of memos, so it has no stream of its own.
        composeRule.onNodeWithTag("memo_view_note").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("note_empty_state").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithTag("wall_play").assertCountEquals(0)
    }

    @Test
    fun aNoteOpensWhereItStandsAndAgainOnItsOwnPage() {
        runBlocking { application().database.clearAllTables() }
        val ids = runBlocking {
            val noteId = application().database.noteDao().insert(
                NoteEntity(title = "夜明け前に君と", coverColor = "plum", createdAt = 1, updatedAt = 1),
            )
            val first = application().database.memoDao().insert(
                MemoEntity(
                    title = "はじまりの駅",
                    body = "■ 朝\n改札を抜けた。",
                    createdAt = 1,
                    updatedAt = 10,
                ),
            )
            val second = application().database.memoDao().insert(
                MemoEntity(title = "", body = "書きかけ", createdAt = 1, updatedAt = 20),
            )
            application().database.noteDao().placeEpisode(first, noteId, null, 0)
            application().database.noteDao().placeEpisode(second, noteId, null, 1)
            Triple(noteId, first, second)
        }
        val (noteId, firstMemoId, secondMemoId) = ids

        composeRule.onNodeWithTag("memo_view_note").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("note_row_$noteId").fetchSemanticsNodes().isNotEmpty()
        }
        // The button makes what the tab is about.
        composeRule.onNodeWithTag("create_note").assertIsDisplayed()
        composeRule.onAllNodesWithTag("create_memo").assertCountEquals(0)
        // Closed, a note is a cover and a title. Nothing about its episodes yet.
        composeRule.onAllNodesWithTag("episode_row_$firstMemoId").assertCountEquals(0)

        composeRule.onNodeWithTag("toggle_note_$noteId").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("episode_row_$firstMemoId").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("第1話").assertIsDisplayed()

        // The cover leads to the note itself, where it is arranged rather than read.
        composeRule.onNodeWithTag("open_note_$noteId").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("note_detail_summary").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("note_detail_summary").assertTextContains("全2話", substring = true)

        // A chapter is a heading placed over the run; the numbering still goes straight through.
        composeRule.onNodeWithTag("note_detail_menu").performClick()
        composeRule.onNodeWithTag("note_add_chapter").performClick()
        composeRule.onNodeWithTag("note_add_chapter_dialog_field").performTextInput("第一章")
        composeRule.onNodeWithTag("note_add_chapter_at_end").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("第一章").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("note_select_episode_$secondMemoId").performClick()
        composeRule.onNodeWithTag("note_move_to_chapter").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("chapter_picker_dialog").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithTag("chapter_pick_none").assertCountEquals(1)
        // The chapter's own heading is on the page behind the dialog, so pick it by its row.
        val chapterId = runBlocking {
            application().database.noteDao().chapters(noteId).single().id
        }
        composeRule.onNodeWithTag("chapter_pick_$chapterId").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { application().database.memoDao().findById(secondMemoId) }?.chapterId != null
        }
        composeRule.onNodeWithTag("note_detail_summary").assertTextContains("全2話", substring = true)

        // Opening an episode reads it. Nothing flows until it is asked for.
        composeRule.onNodeWithTag("note_episode_$firstMemoId").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("note_reader_body").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("はじまりの駅").assertIsDisplayed()
        // Reading shows the words, not the marks that made them, and a marked line stands alone.
        composeRule.onNodeWithText("朝", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("改札を抜けた。", substring = true).assertIsDisplayed()
        // The episode has a marked line, so there is something to send across it.
        composeRule.onNodeWithTag("reader_play").assertIsDisplayed().assertIsEnabled()
        composeRule.onAllNodesWithTag("reader_pause").assertCountEquals(0)
        composeRule.onNodeWithTag("reader_next").assertIsEnabled()
        composeRule.onNodeWithTag("reader_previous").assertIsNotEnabled()

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("reader_play").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag("reader_pause").assertIsDisplayed().performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.autoAdvance = true
        composeRule.onNodeWithTag("reader_stop").assertIsDisplayed()
    }

    @Test
    fun deletingANoteLeavesItsEpisodesAsMemos() {
        runBlocking { application().database.clearAllTables() }
        val ids = runBlocking {
            val noteId = application().database.noteDao().insert(
                NoteEntity(title = "消えるノート", coverColor = "moss", createdAt = 1, updatedAt = 1),
            )
            val memoId = application().database.memoDao().insert(
                MemoEntity(title = "残る話", body = "本文は残る", createdAt = 1, updatedAt = 10),
            )
            application().database.noteDao().placeEpisode(memoId, noteId, null, 0)
            noteId to memoId
        }
        val (noteId, memoId) = ids

        composeRule.onNodeWithTag("memo_view_note").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("note_row_$noteId").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("open_note_$noteId").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("note_detail_menu").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("note_detail_menu").performClick()
        composeRule.onNodeWithTag("note_delete").performClick()
        composeRule.onNodeWithTag("note_delete_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("confirm_note_delete").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { application().database.noteDao().findById(noteId) } == null
        }
        // The writing was a memo before the note and it stays one.
        val released = runBlocking { application().database.memoDao().findById(memoId) }
        assertEquals("残る話", released?.title)
        assertEquals("本文は残る", released?.body)
        assertEquals(null, released?.noteId)
    }

    @Test
    fun theDrawerCarriesTheScopesAndTheTagsAMemoBelongsTo() {
        runBlocking { application().database.clearAllTables() }
        val ids = runBlocking {
            val tagId = application().database.tagDao().insert(
                TagEntity(name = "仕事", normalizedName = "仕事", createdAt = 1),
            )
            val tagged = application().database.memoDao().insert(
                MemoEntity(title = "会議", body = "本文", createdAt = 1, updatedAt = 2),
            )
            val untagged = application().database.memoDao().insert(
                MemoEntity(title = "買い物", body = "本文", createdAt = 1, updatedAt = 1),
            )
            application().database.tagDao().attach(MemoTagCrossRef(tagged, tagId))
            Triple(tagId, tagged, untagged)
        }
        val (tagId, taggedId, untaggedId) = ids
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_card_$taggedId").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("memo_top_menu").performClick()
        composeRule.onNodeWithTag("memo_drawer").assertIsDisplayed()
        composeRule.onNodeWithTag("drawer_all_memos").assertIsSelected()
        // A tag list is not a menu: it grows with the memos, so it lives here.
        composeRule.onAllNodesWithTag("drawer_tags_empty").assertCountEquals(0)
        composeRule.onNodeWithTag("drawer_tag_$tagId").assertIsDisplayed().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_card_$untaggedId").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("memo_card_$taggedId").assertIsDisplayed()

        // The same entry lets go of the tag, and the drawer says which scope is showing.
        composeRule.onNodeWithTag("memo_top_menu").performClick()
        composeRule.onNodeWithTag("drawer_tag_$tagId").assertIsSelected()
        composeRule.onAllNodesWithTag("drawer_all_memos").assertCountEquals(1)
        composeRule.onNodeWithTag("drawer_tag_$tagId").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_card_$untaggedId").fetchSemanticsNodes().isNotEmpty()
        }

        // Pinning is the scope the star used to be, and it is reached from the same place.
        composeRule.onNodeWithTag("memo_card_$untaggedId").performTouchInput { longClick() }
        composeRule.onNodeWithTag("memo_pin_$untaggedId").performClick()
        composeRule.onNodeWithTag("memo_top_menu").performClick()
        composeRule.onNodeWithTag("drawer_pinned_memos").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_card_$taggedId").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("memo_card_$untaggedId").assertIsDisplayed()

        // Nothing marks a memo with a star any more, here or in the editor.
        composeRule.onAllNodesWithTag("memo_favorite_$untaggedId").assertCountEquals(0)
        composeRule.onNodeWithTag("memo_card_$untaggedId").performClick()
        leaveReadingModeIfShown()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_editor_more").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithTag("memo_editor_favorite").assertCountEquals(0)
    }

    @Test
    fun aRecordWithoutPhotosShowsNoPhotoStripAndStillOffersToAddOne() {
        runBlocking { application().database.clearAllTables() }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_empty_state").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("create_memo").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_body").fetchSemanticsNodes().isNotEmpty()
        }
        closeSoftKeyboard()
        composeRule.waitForIdle()

        // Nothing stands in for the photos a memo does not have.
        composeRule.onAllNodesWithText("写真", substring = true).assertCountEquals(0)
        // Adding one is a writing action, so it sits with the tools that come with the caret.
        composeRule.onNodeWithTag("memo_body").performClick()
        composeRule.onNodeWithTag("toolbar_add_photo").performScrollTo().assertIsDisplayed()

        // The diary has no toolbar above its keyboard, so its action row carries the same thing.
        composeRule.onNodeWithContentDescription("戻る").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("nav_calendar").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("nav_calendar").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("timeline_create_journal").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("timeline_create_journal").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("diary_body").fetchSemanticsNodes().isNotEmpty()
        }
        closeSoftKeyboard()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("写真", substring = true).assertCountEquals(0)
        composeRule.onNodeWithTag("diary_add_photo")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("写真を追加").assertExists()
    }

    @Test
    fun aHeadingFoldsWhicheverWayItIsWritten() {
        runBlocking { application().database.clearAllTables() }
        val memoId = runBlocking {
            application().database.memoDao().insert(
                MemoEntity(
                    title = "見出しの書き方",
                    // The first is what this app writes; the rest is what an imported file brings.
                    body = "■ 自前の見出し\n中身一\n# 取り込んだ見出し\n中身二\n## その下\n中身三",
                    createdAt = 1,
                    updatedAt = 1,
                ),
            )
        }
        // The one wall holds it; its card opens it.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_card_$memoId").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("memo_card_$memoId").performClick()
        leaveReadingModeIfShown()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_editor_more").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("memo_editor_more").performClick()
        composeRule.onNodeWithTag("memo_editor_reading_mode").performClick()

        // All three are headings, so all three offer to fold.
        composeRule.onNodeWithTag("reading_fold_0").assertIsDisplayed()
        composeRule.onNodeWithTag("reading_fold_2").assertIsDisplayed()
        composeRule.onNodeWithTag("reading_fold_4").assertIsDisplayed()

        // The hash heading owns the deeper one under it, so folding it takes both away.
        composeRule.onNodeWithTag("reading_fold_2").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("その下").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("reading_hidden_count_2", useUnmergedTree = true)
            .assertTextContains("3")
        composeRule.onNodeWithText("自前の見出し").assertIsDisplayed()
        composeRule.onNodeWithText("中身一").assertIsDisplayed()
    }

    @Test
    fun readingFoldsAHeadingAwayAndStillTakesACheckboxTap() {
        runBlocking { application().database.clearAllTables() }
        val memoId = runBlocking {
            application().database.memoDao().insert(
                MemoEntity(
                    title = "折りたたみ",
                    body = "# 見出し一\n- [ ] やること\n本文\n# 見出し二\nあと",
                    createdAt = 1,
                    updatedAt = 1,
                ),
            )
        }
        // The one wall holds it; its card opens it.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_card_$memoId").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("memo_card_$memoId").performClick()
        leaveReadingModeIfShown()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_editor_more").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("memo_editor_more").performClick()
        composeRule.onNodeWithTag("memo_editor_reading_mode").performClick()

        composeRule.onNodeWithTag("memo_reading_view").assertIsDisplayed()
        // Nothing here writes, so the field that would take the caret is not on screen at all.
        composeRule.onAllNodesWithTag("memo_body").assertCountEquals(0)
        composeRule.onNodeWithText("やること").assertIsDisplayed()

        // Folding the first heading hides what it owns, and says how much.
        composeRule.onNodeWithTag("reading_fold_0").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("やること").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("reading_hidden_count_0", useUnmergedTree = true)
            .assertTextContains("2")
        composeRule.onNodeWithText("見出し二").assertIsDisplayed()

        composeRule.onNodeWithTag("reading_fold_0").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("やること").fetchSemanticsNodes().isNotEmpty()
        }

        // The checkbox is tapped where it sits, and the memo is what changes.
        composeRule.onNodeWithTag("reading_task_1").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { application().database.memoDao().findById(memoId) }
                ?.body?.contains("- [x] やること") == true
        }

        composeRule.onNodeWithTag("reading_edit").performClick()
        composeRule.onNodeWithTag("memo_body").assertTextContains("- [x] やること", substring = true)
    }

    @Test
    fun everyFoldOpensAndClosesFromTheOneAction() {
        runBlocking { application().database.clearAllTables() }
        val memoId = runBlocking {
            application().database.memoDao().insert(
                MemoEntity(
                    title = "一括",
                    body = "# 一\n中身\n# 二\n中身",
                    createdAt = 1,
                    updatedAt = 1,
                ),
            )
        }
        // The one wall holds it; its card opens it.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_card_$memoId").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("memo_card_$memoId").performClick()
        leaveReadingModeIfShown()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_editor_more").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("memo_editor_more").performClick()
        composeRule.onNodeWithTag("memo_editor_reading_mode").performClick()

        composeRule.onNodeWithTag("reading_fold_all").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("中身").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText("一").assertIsDisplayed()
        composeRule.onNodeWithText("二").assertIsDisplayed()

        composeRule.onNodeWithTag("reading_fold_all").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("中身").fetchSemanticsNodes().size == 2
        }
    }

    @Test
    fun longMemoCommentDiaryAndFutureContentKeepActionsReachable() {
        runBlocking { application().database.clearAllTables() }
        val longMemoTitle = "長いタイトル".repeat(24)
        val longMemoBody = List(24) { index -> "# 長い見出し$index\n- 本文を確認する項目が続きます" }
            .joinToString("\n")
        val longComment = "長いコメントでも入力と操作が画面外へ失われないことを確認します。".repeat(10)
        val longDiary = "長い日記本文でも編集領域と確定操作へ到達できます。".repeat(30)
        val longFuture = "未来へ送る長いコメントの入力欄と送信操作を確認します。".repeat(20)

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_empty_state").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("create_memo").performClick()
        composeRule.onNodeWithTag("memo_title").performTextInput(longMemoTitle)
        composeRule.onNodeWithTag("memo_body").performTextInput(longMemoBody)
        closeSoftKeyboard()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithTag("open_user_comments").assertIsEnabled()
            }.isSuccess
        }
        composeRule.onNodeWithTag("open_user_comments").performClick()
        composeRule.onNodeWithTag("user_comments_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("user_comment_input").performTextInput(longComment)
        closeSoftKeyboard()
        awaitEnabled("add_user_comment")
        composeRule.onNodeWithTag("add_user_comment").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(longComment).fetchSemanticsNodes().isNotEmpty()
        }
        dismissCommentsSheet()
        composeRule.onNodeWithContentDescription("戻る").performClick()

        composeRule.onNodeWithTag("nav_calendar").performClick()
        awaitNode("timeline_create_journal")
        composeRule.onNodeWithTag("timeline_create_journal").performClick()
        awaitNode("diary_body")
        composeRule.onNodeWithTag("diary_body").performTextInput(longDiary)
        closeSoftKeyboard()
        awaitNode("open_future_comment_creator")
        composeRule.onNodeWithTag("open_future_comment_creator").performClick()
        composeRule.onNodeWithTag("future_comment_input").performTextInput(longFuture)
        closeSoftKeyboard()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("send_future_comment")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
    }

    @Test
    fun memoAndDiaryExposeSpeechActionsForNonEmptyContent() {
        composeRule.onNodeWithTag("create_memo").performClick()
        composeRule.onNodeWithTag("memo_body").performTextInput("読み上げるメモ")
        closeSoftKeyboard()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("memo_speech_action").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("memo_speech_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("start_memo_speech").assertIsDisplayed()
        composeRule.onNodeWithText("キャンセル").performClick()
        composeRule.onNodeWithContentDescription("戻る").performClick()

        // The Calendar tab always offers a new journal for today (several a day are allowed).
        composeRule.onNodeWithTag("nav_calendar").performClick()
        awaitNode("timeline_create_journal")
        composeRule.onNodeWithTag("timeline_create_journal").performClick()
        composeRule.onNodeWithTag("diary_body").assertIsDisplayed()
        composeRule.onNodeWithTag("diary_body").performTextInput("読み上げる日記")
        closeSoftKeyboard()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("diary_speech_action").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("diary_speech_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("start_diary_speech").assertIsDisplayed()
    }

    @Test
    fun aJournalIsWrittenWithoutAnyLifecycleAndSendsAFutureComment() {
        composeRule.onNodeWithTag("nav_calendar").performClick()
        awaitNode("timeline_create_journal")
        composeRule.onNodeWithTag("timeline_create_journal").performClick()
        awaitNode("diary_body")
        composeRule.onNodeWithTag("diary_body").performTextInput("今日は日記機能を作った。確認もした")
        closeSoftKeyboard()
        composeRule.waitForIdle()

        // No 確定, no 修正, no lock: a journal entry is written like a memo (HANDOFF §16.18).
        assertTrue(composeRule.onAllNodesWithTag("finalize_diary").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("diary_state_label").fetchSemanticsNodes().isEmpty())
        awaitNode("open_future_comment_creator")
        composeRule.onNodeWithTag("future_comment_empty_state").assertIsDisplayed()

        composeRule.onNodeWithTag("open_future_comment_creator").performClick()
        composeRule.onNodeWithTag("future_comment_input").performTextInput("封印中は見えない未来本文")
        awaitEnabled("send_future_comment")
        composeRule.onNodeWithTag("send_future_comment").performClick()
        awaitNode("confirm_send_future_comment")
        composeRule.onNodeWithTag("confirm_send_future_comment").performClick()
        composeRule.waitForIdle()
        assertTrue(
            composeRule.onAllNodesWithText("封印中は見えない未来本文")
                .fetchSemanticsNodes().isEmpty(),
        )
        composeRule.onNodeWithText("未来へ送信済み").performScrollTo().assertIsDisplayed()

        val application = composeRule.activity.application as MemoRippleApplication
        val deliveredText = "公開後に初めて流れる本文"
        val deliveredId = runBlocking {
            val today = application.timeProvider.currentLocalDate().toEpochDay()
            val diary = requireNotNull(application.database.diaryDao().findByDate(today))
            val now = application.timeProvider.nowMillis()
            application.database.futureDiaryCommentDao().insert(
                FutureDiaryCommentEntity(
                    diaryEntryId = diary.id,
                    text = deliveredText,
                    sealedAt = now - 2_000,
                    revealAt = now - 1_000,
                    deliveredAt = now,
                ),
            )
        }
        assertTrue(composeRule.onAllNodesWithText(deliveredText).fetchSemanticsNodes().isEmpty())
        assertTrue(
            composeRule.onAllNodesWithTag("future_speech_action").fetchSemanticsNodes().isEmpty(),
        )

        runBlocking {
            application.settingsRepository.setPlaybackSpeed(PlaybackSpeed.FAST)
            application.settingsRepository.setCommentSize(CommentSize.LARGE)
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("app_settings_system_fast_large_black")
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithContentDescription("戻る").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("receive_future_comment").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("receive_future_comment").performClick()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.onNodeWithTag("future_comment_stage").assertIsDisplayed()
        composeRule.onNodeWithTag("future_comment_background_black").assertIsDisplayed()
        composeRule.onNodeWithTag("future_comment_playback_fast_large").assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.onAllNodesWithTag("source_diary_date")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("source_diary_date").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("source_diary_body")
            .performScrollTo()
            .assertTextContains("今日は日記機能を作った。確認もした")
        assertTrue(composeRule.onAllNodesWithText(deliveredText).fetchSemanticsNodes().isEmpty())

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.onAllNodesWithTag("stop_future_comment")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("stop_future_comment").performClick()
        assertEquals(
            null,
            runBlocking {
                application.database.futureDiaryCommentDao().findEntityById(deliveredId)
                    ?.firstPresentedAt
            },
        )
        composeRule.mainClock.autoAdvance = true
        composeRule.onNodeWithContentDescription("戻る").performClick()
        val writtenId = runBlocking {
            application().database.diaryDao()
                .entriesForDate(application().timeProvider.currentLocalDate().toEpochDay()).single().id
        }
        awaitNode("timeline_item_journal_$writtenId")
        composeRule.onNodeWithTag("timeline_item_journal_$writtenId").performClick()
        assertTrue(composeRule.onAllNodesWithText(deliveredText).fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithText("受け取りを続ける").performScrollTo().assertIsDisplayed()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("replay_future_comment").performClick()
        composeRule.mainClock.advanceTimeBy(1_000)
        assertTrue(composeRule.onAllNodesWithText(deliveredText).fetchSemanticsNodes().isEmpty())

        composeRule.mainClock.advanceTimeBy(11_000)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("future_comment_static").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("future_comment_static").assertTextContains(deliveredText)
        composeRule.onNodeWithTag("future_speech_action").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithTag("replay_future_comment_context").assertIsEnabled()
        composeRule.onNodeWithText("に受け取りました", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("replay_future_comment_context").assertIsDisplayed()

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("replay_future_comment_context").performClick()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.onNodeWithTag("future_comment_static").assertTextContains(deliveredText)
        composeRule.onNodeWithTag("stop_future_comment").assertIsDisplayed().performClick()
        composeRule.mainClock.autoAdvance = true
        composeRule.onNodeWithContentDescription("戻る").performClick()

        composeRule.onNodeWithText("今日は日記機能を作った。確認もした").performClick()
        composeRule.onNodeWithText(deliveredText).assertIsDisplayed()
        composeRule.onNodeWithTag("replay_future_comment").performClick()
        composeRule.onNodeWithTag("future_comment_stage").assertIsDisplayed()
        composeRule.onNodeWithTag("source_diary_body")
            .performScrollTo()
            .assertTextContains("今日は日記機能を作った。確認もした")
        composeRule.onNodeWithTag("future_comment_static").assertTextContains(deliveredText)
        composeRule.onNodeWithContentDescription("戻る").performClick()
    }

    @Test
    fun workCommentPlaybackCanPauseAndResume() {
        composeRule.onNodeWithTag("create_memo").performClick()
        composeRule.onNodeWithTag("memo_body").performTextInput("# 見出し\n- 項目")
        closeSoftKeyboard()
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("play_work_comments").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag("memo_speech_action").assertIsEnabled()
        composeRule.onNodeWithTag("pause_work_comments").assertIsDisplayed().performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag("resume_work_comments").assertIsDisplayed()
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun memoCommentPlaybackAndSpeechRunAndStopIndependentlyWhenVoiceIsAvailable() {
        fun startSpeech() {
            composeRule.onNodeWithTag("memo_speech_action").performClick()
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.onNodeWithTag("start_memo_speech").performClick()
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.onNodeWithContentDescription("読み上げを停止").assertIsDisplayed()
        }

        composeRule.onNodeWithTag("create_memo").performClick()
        composeRule.onNodeWithTag("memo_body").performTextInput("# 主人公\n- 独立再生を確認する本文")
        closeSoftKeyboard()
        composeRule.waitForIdle()
        closeSoftKeyboard()
        composeRule.mainClock.autoAdvance = false

        composeRule.onNodeWithTag("play_work_comments").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag("pause_work_comments").assertIsDisplayed()
        startSpeech()
        composeRule.onNodeWithTag("pause_work_comments").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("再生を終了").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag("play_work_comments").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("読み上げを停止").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("読み上げを停止").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag("memo_speech_action")
            .assertContentDescriptionEquals("読み上げ")

        closeSoftKeyboard()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("play_work_comments").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        startSpeech()
        composeRule.onNodeWithContentDescription("読み上げを停止").assertIsDisplayed()
        composeRule.onNodeWithTag("pause_work_comments").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("読み上げを停止").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag("memo_speech_action")
            .assertContentDescriptionEquals("読み上げ")
        composeRule.onNodeWithTag("pause_work_comments").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("再生を終了").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag("play_work_comments").assertIsDisplayed()
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun stageModeKeepsBodySeparateAndSupportsPlayback() {
        composeRule.onNodeWithTag("create_memo").performClick()
        composeRule.onNodeWithTag("open_playback_settings").performClick()
        awaitNode("comment_playback_settings_sheet")
        selectPersistedChip("playback_mode_stage")
        composeRule.onNodeWithTag("close_playback_settings").performClick()
        composeRule.onNodeWithTag("comment_stage").assertIsDisplayed()
        composeRule.onNodeWithTag("memo_body").performTextInput("# 主人公\n- 長いコメント")
        closeSoftKeyboard()
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("play_work_comments").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag("pause_work_comments").assertIsDisplayed().performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.autoAdvance = true
        composeRule.onNodeWithTag("open_playback_settings").performClick()
        awaitNode("comment_playback_settings_sheet")
        composeRule.onNodeWithTag("playback_mode_inline").performClick()
        composeRule.onNodeWithTag("close_playback_settings").performClick()
        composeRule.onNodeWithTag("play_work_comments").assertIsDisplayed()
    }

    @Test
    fun presetCommentCanBeEditedSavedReorderedAndResetWithNormalComment() {
        composeRule.onNodeWithTag("create_memo").performClick()
        composeRule.onNodeWithTag("memo_body").performTextInput("読み返す本文")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithTag("open_user_comments").assertIsEnabled()
            }.isSuccess
        }

        composeRule.onNodeWithTag("open_user_comments").performClick()
        composeRule.onNodeWithTag("comment_empty_state").assertIsDisplayed()
        composeRule.onNodeWithTag("comment_preset_grass").assertIsDisplayed()
        composeRule.onNodeWithTag("user_comment_input").performTextInput("新機能")
        composeRule.onNodeWithTag("comment_preset_kita")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("user_comment_input")
            .assertTextContains("新機能ｷﾀ━━━━(ﾟ∀ﾟ)━━━━!!")
        awaitEnabled("add_user_comment")
        composeRule.onNodeWithTag("add_user_comment").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("新機能ｷﾀ━━━━(ﾟ∀ﾟ)━━━━!!")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("新機能ｷﾀ━━━━(ﾟ∀ﾟ)━━━━!!").assertIsDisplayed()

        composeRule.onNodeWithTag("user_comment_input").performTextInput("やっぱりこの案に戻した")
        awaitEnabled("add_user_comment")
        composeRule.onNodeWithTag("add_user_comment").performClick()
        // A row at rest carries nothing to press: the handles come with the arranging mode.
        closeSoftKeyboard()
        awaitNode("user_comments_arrange_mode")
        composeRule.onNodeWithTag("user_comments_arrange_mode").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription("コメントを並べ替え")
                .fetchSemanticsNodes().size == 2
        }
        val firstText = composeRule.onNodeWithText("新機能ｷﾀ━━━━(ﾟ∀ﾟ)━━━━!!")
        val secondText = composeRule.onNodeWithText("やっぱりこの案に戻した")
        assertTrue(
            composeRule.onAllNodesWithContentDescription("コメントを並べ替え")[0]
                .getUnclippedBoundsInRoot().left > firstText.getUnclippedBoundsInRoot().left,
        )

        composeRule.onAllNodesWithContentDescription("コメントを並べ替え")[1]
            .performTouchInput {
                swipe(
                    start = center,
                    end = Offset(center.x, center.y - 600f),
                    durationMillis = 500,
                )
            }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            secondText.getUnclippedBoundsInRoot().top < firstText.getUnclippedBoundsInRoot().top
        }

        composeRule.onNodeWithTag("reset_user_comment_order").performClick()
        composeRule.onNodeWithTag("confirm_reset_user_comment_order").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            firstText.getUnclippedBoundsInRoot().top < secondText.getUnclippedBoundsInRoot().top
        }
    }

    @Test
    fun favoriteCommentsBelongToTheOwnerAndTakeAdditionsAndRemovals() {
        composeRule.onNodeWithTag("create_memo").performClick()
        composeRule.onNodeWithTag("memo_body").performTextInput("お気に入りを育てる本文")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching { composeRule.onNodeWithTag("open_user_comments").assertIsEnabled() }
                .isSuccess
        }
        composeRule.onNodeWithTag("open_user_comments").performClick()

        // The + at the end of the row takes a new favourite.
        composeRule.onNodeWithTag("favorite_comment_add").performScrollTo().performClick()
        awaitNode("favorite_comment_input")
        composeRule.onNodeWithTag("favorite_comment_input").performTextInput("それな")
        composeRule.onNodeWithTag("favorite_comment_confirm").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("それな").fetchSemanticsNodes().isNotEmpty()
        }
        // Tapping it writes it, like any chip.
        composeRule.onNodeWithText("それな").performScrollTo().performClick()
        composeRule.onNodeWithTag("user_comment_input").assertTextContains("それな")

        // Holding a chip asks about removing it — the built-ins are the owner's too.
        composeRule.onNodeWithTag("comment_preset_grass").performScrollTo()
            .performTouchInput { longClick() }
        awaitNode("favorite_delete_confirm")
        composeRule.onNodeWithTag("favorite_delete_confirm").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("comment_preset_grass").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun commentExpressionCanBePreviewedSavedResetAndEditedWithoutChangingPresetChoice() {
        composeRule.onNodeWithTag("create_memo").performClick()
        composeRule.onNodeWithTag("memo_body").performTextInput("表現を確認する本文")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching { composeRule.onNodeWithTag("open_user_comments").assertIsEnabled() }
                .isSuccess
        }
        composeRule.onNodeWithTag("open_user_comments").performClick()
        composeRule.onNodeWithTag("user_comment_input").performTextInput("ここ好き")
        composeRule.onNodeWithTag("edit_composer_appearance").performClick()
        awaitNode("comment_appearance_sheet")
        composeRule.onNodeWithTag("comment_appearance_preview").assertIsDisplayed()
        composeRule.onNodeWithTag("appearance_color_red").performClick().assertIsSelected()
        composeRule.onNodeWithTag("appearance_size_large").performClick().assertIsSelected()
        composeRule.onNodeWithTag("appearance_emphasis_strong").performClick().assertIsSelected()
        composeRule.onNodeWithTag("motion_speed_fast").performScrollTo().performClick()
            .assertIsSelected()
        composeRule.onNodeWithTag("motion_placement_top").performScrollTo().performClick()
            .assertIsSelected()
        composeRule.onNodeWithTag("motion_direction_ltr").performScrollTo().performClick()
            .assertIsSelected()
        composeRule.onNodeWithTag("motion_effect_wave").performScrollTo().performClick()
            .assertIsSelected()
        composeRule.onNodeWithTag("comment_motion_preview_label")
            .assertTextContains("速い・上側・左から右・波")
        composeRule.onNodeWithTag("confirm_comment_appearance").performScrollTo().performClick()
        // The palette is an icon now, so what it is set to is in the label a screen reader hears.
        composeRule.onNodeWithTag("edit_composer_appearance")
            .assertContentDescriptionContains("赤・大きめ・強調・速い・上側・左から右・波", substring = true)

        composeRule.onNodeWithTag("comment_preset_grass").performClick()
        composeRule.onNodeWithTag("edit_composer_appearance")
            .assertContentDescriptionContains("赤・大きめ・強調・速い・上側・左から右・波", substring = true)
        awaitEnabled("add_user_comment")
        composeRule.onNodeWithTag("add_user_comment").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("赤・大きめ・強調・速い・上側・左から右・波")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("edit_composer_appearance")
            .assertContentDescriptionContains("標準", substring = true)

        // A comment answers to holding, like every card in the app; the palette row is the
        // same door the composer's palette icon opens.
        composeRule.onNodeWithText("赤・大きめ・強調・速い・上側・左から右・波")
            .performTouchInput { longClick() }
        awaitNode("comment_action_expression")
        composeRule.onNodeWithTag("comment_action_expression").performClick()
        awaitNode("comment_appearance_sheet")
        composeRule.onNodeWithTag("appearance_color_blue").performClick()
        composeRule.onNodeWithTag("appearance_size_small").performClick()
        composeRule.onNodeWithTag("appearance_emphasis_normal").performClick()
        composeRule.onNodeWithTag("motion_speed_slow").performScrollTo().performClick()
        composeRule.onNodeWithTag("motion_placement_bottom").performScrollTo().performClick()
        composeRule.onNodeWithTag("confirm_comment_appearance").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("青・小さめ・ゆっくり・下側・左から右・波")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun fixedCommentModeHidesFlowControlsPreservesThemAndResetsComposerAfterSave() {
        composeRule.onNodeWithTag("create_memo").performClick()
        composeRule.onNodeWithTag("memo_body").performTextInput("固定表示を確認する本文")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching { composeRule.onNodeWithTag("open_user_comments").assertIsEnabled() }
                .isSuccess
        }
        composeRule.onNodeWithTag("open_user_comments").performClick()
        composeRule.onNodeWithTag("user_comment_input")
            .performTextInput("複数行になっても全文を表示する固定コメント")
        composeRule.onNodeWithTag("edit_composer_appearance").performClick()
        awaitNode("comment_appearance_sheet")
        composeRule.onNodeWithTag("motion_speed_fast").performScrollTo().performClick()
        composeRule.onNodeWithTag("motion_placement_bottom").performScrollTo().performClick()
        composeRule.onNodeWithTag("motion_direction_ltr").performScrollTo().performClick()
        composeRule.onNodeWithTag("motion_effect_wave").performScrollTo().performClick()
        composeRule.onNodeWithTag("motion_mode_fixed_top").performScrollTo().performClick()
        composeRule.onNodeWithTag("comment_motion_preview_label").assertTextContains("上に固定")
        assertTrue(composeRule.onAllNodesWithTag("motion_speed_fast").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("motion_direction_ltr").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("motion_effect_wave").fetchSemanticsNodes().isEmpty())

        composeRule.onNodeWithTag("motion_mode_flow").performClick()
        composeRule.onNodeWithTag("motion_speed_fast").performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag("motion_placement_bottom").performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag("motion_direction_ltr").performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag("motion_effect_wave").performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag("motion_mode_fixed_bottom").performScrollTo().performClick()
        composeRule.onNodeWithTag("confirm_comment_appearance").performScrollTo().performClick()
        awaitEnabled("add_user_comment")
        composeRule.onNodeWithTag("add_user_comment").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("下に固定").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("edit_composer_appearance").performClick()
        awaitNode("comment_appearance_sheet")
        composeRule.onNodeWithTag("motion_mode_flow").performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag("motion_direction_rtl").performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag("motion_effect_straight").performScrollTo().assertIsSelected()
    }

    @Test
    fun kitaPresetPlaysInInlineAndStageModes() {
        composeRule.onNodeWithTag("create_memo").performClick()
        composeRule.onNodeWithTag("memo_body").performTextInput("通常の本文")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithTag("open_user_comments").assertIsEnabled()
            }.isSuccess
        }
        composeRule.onNodeWithTag("open_user_comments").performClick()
        composeRule.onNodeWithTag("comment_preset_kita").performScrollTo().performClick()
        awaitEnabled("add_user_comment")
        composeRule.onNodeWithTag("add_user_comment").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("ｷﾀ━━━━(ﾟ∀ﾟ)━━━━!!")
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissCommentsSheet()

        composeRule.onNodeWithTag("open_playback_settings").performClick()
        composeRule.onNodeWithTag("playback_content_user_only").performScrollTo().performClick()
        composeRule.onNodeWithTag("close_playback_settings").performClick()
        closeSoftKeyboard()
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("play_work_comments").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag("pause_work_comments").assertIsDisplayed().performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithContentDescription("再生を終了").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.autoAdvance = true

        composeRule.onNodeWithTag("open_playback_settings").performClick()
        // The mode row scrolls sideways like the content row above it, so it is reached the same way.
        composeRule.onNodeWithTag("playback_mode_stage").performScrollTo().performClick()
        composeRule.onNodeWithTag("close_playback_settings").performClick()
        composeRule.onNodeWithTag("comment_stage").assertIsDisplayed()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("play_work_comments").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag("pause_work_comments").assertIsDisplayed().performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.autoAdvance = true
    }

    private fun chooseSort(tag: String) {
        composeRule.onNodeWithTag("memo_overflow").performClick()
        awaitNode("overflow_sort")
        composeRule.onNodeWithTag("overflow_sort").performClick()
        awaitNode(tag)
        composeRule.onNodeWithTag(tag).performClick()
    }

    private fun dismissDisplayOptions() {
        Espresso.pressBack()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_display_options_sheet")
                .fetchSemanticsNodes().isEmpty()
        }
    }

    private fun openMemoDisplayOptions() {
        composeRule.onNodeWithTag("memo_overflow").performClick()
        awaitNode("overflow_display_options")
        composeRule.onNodeWithTag("overflow_display_options").performClick()
        composeRule.onNodeWithTag("memo_display_options_sheet").assertIsDisplayed()
    }

    private fun openMemoSearch() {
        composeRule.onNodeWithTag("memo_search").assertIsDisplayed()
    }

    private fun openSettingsFromTopLevel() {
        if (composeRule.onAllNodesWithTag("memo_top_menu").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("memo_top_menu").performClick()
            composeRule.onNodeWithTag("memo_open_settings").performClick()
        } else {
            composeRule.onNodeWithContentDescription("設定").performClick()
        }
    }

    /**
     * The memos are a wall of cards two columns wide, so "before" is reading order: higher up, and
     * on a tie, further left.
     */
    /**
     * Waits for a node that a dialog brought with it.
     *
     * A dialog is its own window, and the window registers its semantics a frame after the click
     * that opened it. Reaching straight for a button inside one races that frame: it passes most
     * runs and fails on a loaded machine. This is synchronisation, not a delay; nothing here waits
     * for a fixed length of time.
     */
    /**
     * Waits for a control to become usable before pressing it.
     *
     * `performClick` does not check whether a node is enabled: pressing a disabled button reports
     * success and does nothing. Buttons here are gated on state that arrives through a flow after
     * the typing that fills it, so a click can land on the disabled moment and be swallowed. What
     * follows then waits for a consequence that is never coming, and the failure surfaces somewhere
     * else entirely. This waits for the precondition, not for a length of time.
     */
    private fun awaitEnabled(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching { composeRule.onNodeWithTag(tag).assertIsEnabled() }.isSuccess
        }
    }

    /**
     * A filter reaches the wall through the store, so the card it keeps is asserted once the
     * wall has caught up rather than in the same frame the chip was tapped — on a loaded host
     * that frame is too early (2026-09-16, full run ending at load 12.8; 3/3 green alone).
     */
    private fun awaitDisplayed(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching { composeRule.onNodeWithTag(tag).assertIsDisplayed() }.isSuccess
        }
    }

    /** Opens 表示条件 from the bar's overflow and picks one way of drawing the wall. */
    private fun switchWallDisplay(chipTag: String) {
        composeRule.onNodeWithTag("memo_overflow").performClick()
        awaitNode("overflow_display_options")
        composeRule.onNodeWithTag("overflow_display_options").performClick()
        awaitNode(chipTag)
        composeRule.onNodeWithTag(chipTag).performClick()
    }

    /**
     * Clicks a chip whose selection round-trips through DataStore and waits for it to land.
     * A chained assertIsSelected() right after the click races the asynchronous write.
     */
    private fun selectPersistedChip(tag: String) {
        composeRule.onNodeWithTag(tag).performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching { composeRule.onNodeWithTag(tag).assertIsSelected() }.isSuccess
        }
    }


    /**
     * An existing memo now opens reading, the way a document app does. Most flows here want
     * the pen: wait for either surface and step back to writing when the page is up. Quietly
     * does nothing when the click led somewhere else (selection mode, another screen).
     */
    private fun leaveReadingModeIfShown() {
        runCatching {
            composeRule.waitUntil(timeoutMillis = 3_000) {
                composeRule.onAllNodesWithTag("reading_edit").fetchSemanticsNodes().isNotEmpty() ||
                    composeRule.onAllNodesWithTag("memo_body").fetchSemanticsNodes().isNotEmpty()
            }
        }
        if (composeRule.onAllNodesWithTag("reading_edit").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("reading_edit").performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("memo_body").fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    /**
     * The comments sheet has no close button; back is how a sheet is put away. The keyboard is
     * put away first, because an open IME takes the first back for itself.
     */
    private fun dismissCommentsSheet() {
        closeSoftKeyboard()
        composeRule.waitForIdle()
        repeat(2) {
            if (composeRule.onAllNodesWithTag("user_comments_sheet")
                    .fetchSemanticsNodes().isEmpty()
            ) {
                return
            }
            androidx.test.espresso.Espresso.pressBack()
            runCatching {
                composeRule.waitUntil(timeoutMillis = 3_000) {
                    composeRule.onAllNodesWithTag("user_comments_sheet")
                        .fetchSemanticsNodes().isEmpty()
                }
            }
        }
        composeRule.waitUntil(timeoutMillis = 2_000) {
            composeRule.onAllNodesWithTag("user_comments_sheet").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun wallColumnToggleLaysTheCombinedWallFlatAndLeavesTheSingleViewsAlone() {
        val (first, second) = runBlocking {
            val dao = application().database.memoDao()
            val now = System.currentTimeMillis()
            val a = dao.insert(MemoEntity(title = "壱", body = "ひとつめの本文", createdAt = now, updatedAt = now + 1))
            val b = dao.insert(MemoEntity(title = "弐", body = "ふたつめの本文", createdAt = now, updatedAt = now))
            a to b
        }
        awaitNode("memo_card_$first")

        // On the combined wall the two cards stand side by side, and the toggle offers flat.
        awaitNode("wall_column_toggle")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_card_$second").fetchSemanticsNodes().isNotEmpty()
        }
        val standingFirst = composeRule.onNodeWithTag("memo_card_$first").getUnclippedBoundsInRoot()
        val standingSecond = composeRule.onNodeWithTag("memo_card_$second").getUnclippedBoundsInRoot()
        assertEquals(standingFirst.top, standingSecond.top)

        // Flat: one card per row, each taking the whole line.
        composeRule.onNodeWithTag("wall_column_toggle").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            val a = composeRule.onNodeWithTag("memo_card_$first").getUnclippedBoundsInRoot()
            val b = composeRule.onNodeWithTag("memo_card_$second").getUnclippedBoundsInRoot()
            a.top != b.top
        }
        val flatFirst = composeRule.onNodeWithTag("memo_card_$first").getUnclippedBoundsInRoot()
        val flatSecond = composeRule.onNodeWithTag("memo_card_$second").getUnclippedBoundsInRoot()
        assertTrue(flatFirst.right - flatFirst.left > standingFirst.right - standingFirst.left)
        assertEquals(flatFirst.left, flatSecond.left)

        // A way of looking keeps: the choice survives leaving the wall and coming back.
        composeRule.onNodeWithTag("memo_card_$first").performClick()
        leaveReadingModeIfShown()
        awaitNode("memo_body")
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitNode("wall_column_toggle")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            val a = composeRule.onNodeWithTag("memo_card_$first").getUnclippedBoundsInRoot()
            val b = composeRule.onNodeWithTag("memo_card_$second").getUnclippedBoundsInRoot()
            a.top != b.top
        }

        // メモのみ is the old memo tab: no toggle, and the flat choice does not reach it.
        switchWallDisplay("memo_wall_MEMO")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            val a = composeRule.onNodeWithTag("memo_card_$first").getUnclippedBoundsInRoot()
            val b = composeRule.onNodeWithTag("memo_card_$second").getUnclippedBoundsInRoot()
            a.top == b.top
        }
        composeRule.onAllNodesWithTag("wall_column_toggle").assertCountEquals(0)

        // And the アウトライン shelf keeps its own grid too.
        runBlocking {
            application().database.memoDao()
                .updateContent(first, "壱", "# 見出し\n- 項目", System.currentTimeMillis())
        }
        switchWallDisplay("memo_wall_OUTLINE")
        awaitNode("memo_sheet_$first")
        composeRule.onAllNodesWithTag("wall_column_toggle").assertCountEquals(0)
        val sheet = composeRule.onNodeWithTag("memo_sheet_$first").getUnclippedBoundsInRoot()
        assertTrue(sheet.right - sheet.left < flatFirst.right - flatFirst.left)
    }

    @Test
    fun theShortcutBarStandsInTwoRowsWhenAskedTo() {
        // One row by default: the aids and the structure tools sit at the same height.
        composeRule.onNodeWithTag("create_memo").performClick()
        composeRule.onNodeWithTag("memo_body").performTextInput("ひとこと")
        awaitNode("toolbar_bold")
        // The bar rides the IME as it settles; two reads mid-animation see two heights, so
        // the one-row claim is awaited rather than sampled once.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            val bold = composeRule.onNodeWithTag("toolbar_bold").getUnclippedBoundsInRoot()
            val task = composeRule.onNodeWithTag("toolbar_task").getUnclippedBoundsInRoot()
            bold.top == task.top
        }

        // Switched on in 設定, the bar stands in two rows: aids above, structure below.
        runBlocking { application().settingsRepository.setEditorToolbarTwoRows(true) }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            val bold = composeRule.onNodeWithTag("toolbar_bold").getUnclippedBoundsInRoot()
            val task = composeRule.onNodeWithTag("toolbar_task").getUnclippedBoundsInRoot()
            task.top > bold.top
        }
    }

    @Test
    fun theReaderBarOrdersPlayThenSpeechThenEdit() {
        val (noteId, episodeId) = runBlocking {
            application().database.clearAllTables()
            val note = application().database.noteDao().insert(
                NoteEntity(title = "ああ", coverColor = "plum", createdAt = 1, updatedAt = 1),
            )
            val episode = application().database.memoDao().insert(
                MemoEntity(title = "無題", body = "あああ", createdAt = 2, updatedAt = 2),
            )
            application().database.noteDao().placeEpisode(episode, note, null, 0)
            note to episode
        }
        composeRule.onNodeWithTag("memo_view_note").performClick()
        awaitNode("toggle_note_$noteId")
        composeRule.onNodeWithTag("toggle_note_$noteId").performClick()
        awaitNode("episode_row_$episodeId")
        composeRule.onNodeWithTag("episode_row_$episodeId").performClick()
        awaitNode("note_reader_top_bar")

        // Reading's three verbs, oldest habit last: flow, hear, change.
        awaitNode("reader_speech")
        assertTrue(comesBefore("reader_play", "reader_speech"))
        assertTrue(comesBefore("reader_speech", "reader_edit"))
    }

    @Test
    fun theTagRowLeavesTheEditorWhenAskedToAndOnlyThere() {
        val memoId = runBlocking {
            val now = System.currentTimeMillis()
            application().database.memoDao().insert(
                MemoEntity(title = "壱", body = "ひとこと", createdAt = now, updatedAt = now),
            )
        }
        awaitNode("memo_card_$memoId")
        composeRule.onNodeWithTag("memo_card_$memoId").performClick()
        leaveReadingModeIfShown()
        awaitNode("open_tag_picker")

        runBlocking { application().settingsRepository.setEditorHideTags(true) }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("open_tag_picker").fetchSemanticsNodes().isEmpty()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun inlineFormattingSurvivesDeletionFromItsVisibleEnd() {
        composeRule.onNodeWithTag("create_memo").performClick()
        val body = composeRule.onNodeWithTag("memo_body")

        // Bold あああ, then backspace from the visible end: ああ → あ → empty, with the markers
        // shrinking with the word and never surfacing as text.
        body.performTextInput("あああ")
        body.performTextInputSelection(TextRange(0, 3))
        composeRule.onNodeWithTag("toolbar_bold").performClick()
        awaitBody("**あああ**")
        body.performTextInputSelection(TextRange(7))
        body.performKeyInput { pressKey(Key.Backspace) }
        awaitBody("**ああ**")
        body.performKeyInput { pressKey(Key.Backspace) }
        awaitBody("**あ**")
        body.performKeyInput { pressKey(Key.Backspace) }
        awaitBody("")

        // The same contract for a coloured highlight, whose opening marker is longer.
        body.performTextInput("あああ")
        body.performTextInputSelection(TextRange(0, 3))
        composeRule.onNodeWithTag("toolbar_highlight").performClick()
        awaitNode("highlight_color_sheet")
        composeRule.onNodeWithTag("highlight_color_yellow").performClick()
        awaitBody("==yellow:あああ==")
        body.performTextInputSelection(TextRange(14))
        body.performKeyInput { pressKey(Key.Backspace) }
        awaitBody("==yellow:ああ==")

        // Deleting the middle character keeps the style around what remains.
        body.performTextInputSelection(TextRange(11))
        body.performKeyInput { pressKey(Key.Backspace) }
        awaitBody("==yellow:あ==")

        // And deleting the last one takes the markers with it.
        body.performTextInputSelection(TextRange(10))
        body.performKeyInput { pressKey(Key.Backspace) }
        awaitBody("")
    }

    private fun awaitBody(expected: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_body").fetchSemanticsNodes().firstOrNull()
                ?.config?.getOrNull(SemanticsProperties.EditableText)?.text == expected
        }
    }

    private fun awaitNode(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun comesBefore(firstTag: String, secondTag: String): Boolean {
        val first = composeRule.onNodeWithTag(firstTag).getUnclippedBoundsInRoot()
        val second = composeRule.onNodeWithTag(secondTag).getUnclippedBoundsInRoot()
        return if (first.top != second.top) first.top < second.top else first.left < second.left
    }

    @Test
    fun copyAllPutsTheWholeMemoOnTheClipboard() {
        runBlocking { application().database.clearAllTables() }
        val memoId = runBlocking {
            application().database.memoDao().insert(
                MemoEntity(title = "写す題", body = "写す本文。", createdAt = 1, updatedAt = 1),
            )
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_card_$memoId").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("memo_card_$memoId").performClick()
        leaveReadingModeIfShown()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_editor_more").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("memo_editor_more").performClick()
        // Sharing stands beside it in the same menu.
        composeRule.onNodeWithTag("memo_editor_share").assertIsDisplayed()
        composeRule.onNodeWithTag("memo_editor_copy_all").performClick()
        composeRule.waitForIdle()

        var copied = ""
        composeRule.runOnUiThread {
            val clipboard = application().getSystemService(
                android.content.Context.CLIPBOARD_SERVICE,
            ) as android.content.ClipboardManager
            copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        }
        // The title, one blank line, the body — exactly what is stored.
        assertEquals("写す題\n\n写す本文。", copied)
    }

    @Test
    fun anEpisodesReadingModeOpensTheNoteReader() {
        runBlocking { application().database.clearAllTables() }
        val (noteId, episodeId) = runBlocking {
            val noteId = application().database.noteDao().insert(
                NoteEntity(title = "読まれるノート", coverColor = "teal", createdAt = 1, updatedAt = 1),
            )
            val episodeId = application().database.memoDao().insert(
                MemoEntity(title = "第一話", body = "ノートの一話目。", createdAt = 1, updatedAt = 1),
            )
            application().database.noteDao().placeEpisode(episodeId, noteId, null, 0)
            noteId to episodeId
        }
        // Episodes live in their note, so the editor is reached through the note's detail row.
        composeRule.onNodeWithTag("memo_view_note").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("open_note_$noteId").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("open_note_$noteId").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("note_edit_episode_$episodeId")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("note_edit_episode_$episodeId").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("memo_editor_more").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("memo_editor_more").performClick()
        composeRule.onNodeWithTag("memo_editor_reading_mode").performClick()

        // Not the memo's plain reading surface: the note's own reader, ruby and all.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("note_reader_top_bar").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun settingsListsTheTemplates() {
        runBlocking { application().database.clearAllTables() }
        openSettingsFromTopLevel()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("settings_list").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("setting_templates"))
        composeRule.onNodeWithTag("setting_templates").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("templates_empty").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun application(): MemoRippleApplication =
        composeRule.activity.application as MemoRippleApplication
}
