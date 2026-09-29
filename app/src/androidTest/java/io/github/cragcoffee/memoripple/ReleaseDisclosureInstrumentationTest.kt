package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import io.github.cragcoffee.memoripple.ui.settings.OssLicenseListScreen
import io.github.cragcoffee.memoripple.ui.settings.PrivacyScreen
import io.github.cragcoffee.memoripple.ui.theme.MemoRippleTheme
import org.junit.Rule
import org.junit.Test

/**
 * The release's own disclosures (2026-09-27, before the Play release): the in-app privacy screen says
 * what the chat and the Local AI do with data, and where the Drive backup goes; the licence list carries
 * the native Local AI runtime (llama.cpp, LLVM libc++, the Unicode Character Database tables).
 */
class ReleaseDisclosureInstrumentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun seeOnPrivacy(words: String) {
        composeRule.onNodeWithTag("privacy_screen").performScrollToNode(hasText(words, substring = true))
        composeRule.onNodeWithText(words, substring = true).assertIsDisplayed()
    }

    @Test
    fun thePrivacyScreenSaysWhatTheChatAndTheLocalAiDoWithData() {
        composeRule.setContent { MemoRippleTheme { PrivacyScreen(onBack = {}) } }
        seeOnPrivacy("チャットの会話は端末内に保存されます。")
        seeOnPrivacy("手動バックアップとGoogle Driveのバックアップには含まれません。")
        seeOnPrivacy("AIの処理は端末内で行います。")
        seeOnPrivacy("AIモデル（GGUF）はアプリに含まれていません。")
        seeOnPrivacy("モデルのダウンロードを開始したときだけ、Hugging FaceへHTTPSで接続します。")
        seeOnPrivacy("メモやチャットの内容をダウンロード先へ送ることはありません。")
        seeOnPrivacy("有効にした場合や実行した場合だけ使います。")
        seeOnPrivacy("ご自身のGoogle Driveのアプリ専用領域へ保存します。")
        // what the screen said before stays
        seeOnPrivacy("通常のDriveファイル一覧には表示されません。")
        seeOnPrivacy("トークンはMemoRippleの永続データには保存しません。")
    }

    @Test
    fun theLicenceListCarriesTheNativeLocalAiRuntime() {
        composeRule.setContent { MemoRippleTheme { OssLicenseListScreen(onBack = {}, onOpenLicense = {}) } }
        composeRule.onNodeWithTag("oss_license_list").performScrollToNode(hasTestTag("oss_license_llama-cpp"))
        composeRule.onNodeWithTag("oss_license_llama-cpp").assertIsDisplayed()
        composeRule.onNodeWithTag("oss_license_list").performScrollToNode(hasTestTag("oss_license_llvm-libcxx"))
        composeRule.onNodeWithTag("oss_license_llvm-libcxx").assertIsDisplayed()
        composeRule.onNodeWithTag("oss_license_list").performScrollToNode(hasTestTag("oss_license_unicode-data"))
        composeRule.onNodeWithTag("oss_license_unicode-data").assertIsDisplayed()
    }
}
