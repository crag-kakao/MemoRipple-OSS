package io.github.cragcoffee.memoripple

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.domain.comments.CommentAppearance
import io.github.cragcoffee.memoripple.domain.comments.CommentColorRole
import io.github.cragcoffee.memoripple.domain.comments.CommentEmphasisRole
import io.github.cragcoffee.memoripple.domain.comments.CommentFlowDirection
import io.github.cragcoffee.memoripple.domain.comments.CommentFlowEffect
import io.github.cragcoffee.memoripple.domain.comments.CommentMotion
import io.github.cragcoffee.memoripple.domain.comments.CommentMotionMode
import io.github.cragcoffee.memoripple.domain.comments.CommentPlacementRole
import io.github.cragcoffee.memoripple.domain.comments.CommentSizeRole
import io.github.cragcoffee.memoripple.domain.comments.CommentSpeedRole
import io.github.cragcoffee.memoripple.domain.settings.ThemeMode
import io.github.cragcoffee.memoripple.ui.memos.CommentExpressionSheet
import io.github.cragcoffee.memoripple.ui.theme.MemoRippleTheme
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VisualRegressionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun flowExpressionMatchesLightGolden() {
        captureAndCompare(
            goldenName = "comment-expression-flow-light.png",
            themeMode = ThemeMode.LIGHT,
            appearance = CommentAppearance(
                colorRole = CommentColorRole.PINK,
                sizeRole = CommentSizeRole.LARGE,
                emphasisRole = CommentEmphasisRole.STRONG,
            ),
            motion = CommentMotion(
                speedRole = CommentSpeedRole.FAST,
                placementRole = CommentPlacementRole.MIDDLE,
                direction = CommentFlowDirection.LEFT_TO_RIGHT,
                flowEffect = CommentFlowEffect.WAVE,
            ),
        )
    }

    @Test
    fun fixedExpressionMatchesDarkGolden() {
        captureAndCompare(
            goldenName = "comment-expression-fixed-dark.png",
            themeMode = ThemeMode.DARK,
            appearance = CommentAppearance(
                colorRole = CommentColorRole.CYAN,
                sizeRole = CommentSizeRole.LARGE,
                emphasisRole = CommentEmphasisRole.STRONG,
            ),
            motion = CommentMotion(mode = CommentMotionMode.FIXED_BOTTOM),
        )
    }

    private fun captureAndCompare(
        goldenName: String,
        themeMode: ThemeMode,
        appearance: CommentAppearance,
        motion: CommentMotion,
    ) {
        assumeReferenceEnvironment()
        composeRule.setContent {
            MemoRippleTheme(themeMode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CommentExpressionSheet(
                        initialAppearance = appearance,
                        initialMotion = motion,
                        previewText = "静かなコメント表現",
                        onDismiss = {},
                        onConfirm = { _, _ -> },
                    )
                }
            }
        }
        composeRule.onNodeWithText("コメントの表現", useUnmergedTree = true).assertIsDisplayed()
        composeRule.waitForIdle()

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(WINDOW_IDLE_TIMEOUT_MS, WINDOW_IDLE_GLOBAL_TIMEOUT_MS)
        val fullScreenshot = instrumentation.uiAutomation.takeScreenshot()
        val actual = Bitmap.createBitmap(
            fullScreenshot,
            0,
            SYSTEM_BAR_CROP_PX,
            fullScreenshot.width,
            fullScreenshot.height - SYSTEM_BAR_CROP_PX * 2,
        )
        val context = instrumentation.targetContext
        val testContext = instrumentation.context
        val arguments = InstrumentationRegistry.getArguments()
        if (arguments.getString(RECORD_ARGUMENT) == "true") {
            val output = File(
                requireNotNull(context.getExternalFilesDir(RECORD_DIRECTORY)),
                goldenName,
            )
            output.outputStream().use {
                check(actual.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            return
        }

        val assetPath = "$GOLDEN_DIRECTORY/$goldenName"
        val expected = testContext.assets.open(assetPath).use(BitmapFactory::decodeStream)
        val result = compare(expected = expected, actual = actual)
        if (!result.matches) {
            val output = File(
                requireNotNull(context.getExternalFilesDir(DIFF_DIRECTORY)),
                "diff-$goldenName",
            )
            output.outputStream().use {
                check(result.diff.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        assertTrue(
            "$assetPath changed: ${result.changedPixels}/${result.totalPixels} pixels; " +
                "diff=${context.getExternalFilesDir(DIFF_DIRECTORY)}",
            result.matches,
        )
    }

    /**
     * Whether this device is the one the goldens were recorded on.
     *
     * No device on hand is, out of the box: Pixel_10 boots 1080x2424 in en-US. That makes the
     * goldens skip silently, so a green suite is not evidence that they were ever compared. To
     * actually run them, put the emulator into the reference environment first:
     *
     * ```
     * adb shell wm size 1080x2400
     * adb shell cmd locale set-app-locales io.github.cragcoffee.memoripple --locales ja-JP
     * ```
     *
     * A Google Play image refuses both `adb root` and `setprop persist.sys.locale`, which is why
     * the locale is set per app rather than for the device. `adb shell wm size reset` puts it back.
     */
    private fun assumeReferenceEnvironment() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = context.resources.configuration
        val metrics = context.resources.displayMetrics
        val locale = configuration.locales[0]
        assumeTrue(
            "Visual goldens require API 36, 1080x2400, 420 dpi, font scale 1.0, and ja locale. " +
                "See the comment above for the two adb commands that put an emulator there.",
            Build.VERSION.SDK_INT == 36 &&
                metrics.widthPixels == REFERENCE_WIDTH_PX &&
                metrics.heightPixels == REFERENCE_HEIGHT_PX &&
                configuration.densityDpi == REFERENCE_DENSITY_DPI &&
                configuration.fontScale == REFERENCE_FONT_SCALE &&
                locale.language == "ja",
        )
    }

    private fun compare(expected: Bitmap, actual: Bitmap): ComparisonResult {
        if (expected.width != actual.width || expected.height != actual.height) {
            return ComparisonResult(
                matches = false,
                changedPixels = maxOf(expected.width * expected.height, actual.width * actual.height),
                totalPixels = actual.width * actual.height,
                diff = actual.copy(Bitmap.Config.ARGB_8888, false),
            )
        }
        val total = actual.width * actual.height
        val expectedPixels = IntArray(total)
        val actualPixels = IntArray(total)
        expected.getPixels(expectedPixels, 0, expected.width, 0, 0, expected.width, expected.height)
        actual.getPixels(actualPixels, 0, actual.width, 0, 0, actual.width, actual.height)
        val diffPixels = IntArray(total)
        var changed = 0
        for (index in 0 until total) {
            val expectedPixel = expectedPixels[index]
            val actualPixel = actualPixels[index]
            val pixelChanged = maxOf(
                abs((expectedPixel ushr 24 and 0xFF) - (actualPixel ushr 24 and 0xFF)),
                abs((expectedPixel ushr 16 and 0xFF) - (actualPixel ushr 16 and 0xFF)),
                abs((expectedPixel ushr 8 and 0xFF) - (actualPixel ushr 8 and 0xFF)),
                abs((expectedPixel and 0xFF) - (actualPixel and 0xFF)),
            ) > CHANNEL_TOLERANCE
            if (pixelChanged) {
                changed++
                diffPixels[index] = 0xFFFF1744.toInt()
            } else {
                val gray = ((actualPixel ushr 16 and 0xFF) +
                    (actualPixel ushr 8 and 0xFF) +
                    (actualPixel and 0xFF)) / 3
                diffPixels[index] = 0x55000000 or (gray shl 16) or (gray shl 8) or gray
            }
        }
        val diff = Bitmap.createBitmap(diffPixels, actual.width, actual.height, Bitmap.Config.ARGB_8888)
        return ComparisonResult(
            matches = changed.toDouble() / total <= MAX_CHANGED_PIXEL_RATIO,
            changedPixels = changed,
            totalPixels = total,
            diff = diff,
        )
    }

    private data class ComparisonResult(
        val matches: Boolean,
        val changedPixels: Int,
        val totalPixels: Int,
        val diff: Bitmap,
    )

    private companion object {
        const val RECORD_ARGUMENT = "recordVisualGoldens"
        const val RECORD_DIRECTORY = "visual-golden-recording"
        const val DIFF_DIRECTORY = "visual-regression-diffs"
        const val GOLDEN_DIRECTORY = "visual-baselines/api36-1080x2400-420dpi-ja"
        const val CHANNEL_TOLERANCE = 12
        const val MAX_CHANGED_PIXEL_RATIO = 0.002
        const val SYSTEM_BAR_CROP_PX = 120
        const val REFERENCE_WIDTH_PX = 1080
        const val REFERENCE_HEIGHT_PX = 2400
        const val REFERENCE_DENSITY_DPI = 420
        const val REFERENCE_FONT_SCALE = 1.0f
        const val WINDOW_IDLE_TIMEOUT_MS = 500L
        const val WINDOW_IDLE_GLOBAL_TIMEOUT_MS = 5_000L
    }
}
