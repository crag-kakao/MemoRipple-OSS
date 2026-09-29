package io.github.cragcoffee.memoripple

import android.graphics.Color
import android.content.Intent
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.domain.WorkCommentScope
import io.github.cragcoffee.memoripple.domain.playback.PlaybackContentMode
import io.github.cragcoffee.memoripple.overlay.OverlayPlaybackRequest
import io.github.cragcoffee.memoripple.overlay.OverlayPlaybackOptions
import io.github.cragcoffee.memoripple.overlay.OverlayDisplayRegion
import io.github.cragcoffee.memoripple.overlay.OverlayDensity
import io.github.cragcoffee.memoripple.overlay.OverlayWindowPolicy
import io.github.cragcoffee.memoripple.overlay.OverlayPlaybackStatus
import io.github.cragcoffee.memoripple.overlay.ACTION_STOP_OVERLAY
import io.github.cragcoffee.memoripple.overlay.OverlayCommentService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverlayFoundationInstrumentationTest {
    @Test
    fun serviceRequestRoundTripsOnlySmallPlaybackIdentifiers() {
        val context = ApplicationProvider.getApplicationContext<MemoRippleApplication>()
        val request = OverlayPlaybackRequest(
            42L,
            OverlayPlaybackOptions(
                PlaybackContentMode.USER_ONLY,
                OverlayDisplayRegion.BOTTOM_HALF,
                OverlayDensity.SPARSE,
            ),
            "request-42",
        )

        val restored = OverlayPlaybackRequest.from(request.toIntent(context))

        assertEquals(request, restored)
        assertEquals(5, request.toIntent(context).extras?.keySet()?.size)
    }

    @Test
    fun wallRequestRoundTripsItsMemoIdsAndScopeThroughTheIntent() {
        val context = ApplicationProvider.getApplicationContext<MemoRippleApplication>()
        val request = OverlayPlaybackRequest.createWall(
            memoIds = listOf(7L, 3L, 5L),
            scope = WorkCommentScope.BODY,
            options = OverlayPlaybackOptions(
                PlaybackContentMode.WORK_ONLY,
                OverlayDisplayRegion.TOP_HALF,
                OverlayDensity.STANDARD,
            ),
        )

        val restored = OverlayPlaybackRequest.from(request.toIntent(context))

        assertEquals(request, restored)
    }

    @Test
    fun unknownNewOptionIdsFallBackWithoutDroppingPermissionRoundtripRequest() {
        val context = ApplicationProvider.getApplicationContext<MemoRippleApplication>()
        val intent = OverlayPlaybackRequest(
            42L,
            OverlayPlaybackOptions(PlaybackContentMode.BOTH),
            "request-fallback",
        ).toIntent(context).apply {
            putExtra("overlay.display_region", "future_region")
            putExtra("overlay.density", "future_density")
        }

        val restored = requireNotNull(OverlayPlaybackRequest.from(intent))

        assertEquals(OverlayDisplayRegion.FULL, restored.options.displayRegion)
        assertEquals(OverlayDensity.STANDARD, restored.options.density)
        assertEquals("request-fallback", restored.requestId)
    }

    @Test
    fun overlayLayoutUsesSafeNonInteractiveApplicationWindow() {
        val context = ApplicationProvider.getApplicationContext<MemoRippleApplication>()
        val windowManager = context.getSystemService(WindowManager::class.java)
        val params = OverlayWindowPolicy.layoutParams(context, windowManager)

        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, params.type)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertTrue(params.alpha in 0f..1f)
    }

    @Test
    fun permissionGrantedEnvironmentCanAddAndRemoveOneOverlayRoot() {
        val context = ApplicationProvider.getApplicationContext<MemoRippleApplication>()
        assumeTrue(Settings.canDrawOverlays(context))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val windowManager = context.getSystemService(WindowManager::class.java)
        val root = View(context).apply { setBackgroundColor(Color.TRANSPARENT) }
        var failure: Throwable? = null

        instrumentation.runOnMainSync {
            try {
                windowManager.addView(root, OverlayWindowPolicy.layoutParams(context, windowManager))
            } catch (error: Throwable) {
                failure = error
            }
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            try {
                assertTrue(root.isAttachedToWindow)
                assertNotNull(root.windowToken)
            } catch (error: Throwable) {
                failure = failure ?: error
            } finally {
                if (root.isAttachedToWindow) windowManager.removeViewImmediate(root)
            }
        }

        failure?.let { throw it }
    }

    @Test
    fun grantedDeviceStartsAndStopsEveryRegionDensitySessionCombination() {
        val context = ApplicationProvider.getApplicationContext<MemoRippleApplication>()
        assumeTrue(Settings.canDrawOverlays(context))
        val memo = runBlocking {
            requireNotNull(
                context.memoRepository.save(
                    existing = null,
                    title = "Overlay option smoke",
                    body = "# 見出し\n- 実機コメント",
                    now = System.currentTimeMillis(),
                ),
            )
        }

        try {
            OverlayDisplayRegion.entries.forEach { region ->
                OverlayDensity.entries.forEach { density ->
                    val request = OverlayPlaybackRequest(
                        memoId = memo.id,
                        options = OverlayPlaybackOptions(
                            contentMode = PlaybackContentMode.WORK_ONLY,
                            displayRegion = region,
                            density = density,
                        ),
                        requestId = "smoke-${region.storageId}-${density.storageId}",
                    )
                    ContextCompat.startForegroundService(context, request.toIntent(context))
                    waitForStatus(context, OverlayPlaybackStatus.PLAYING)
                    assertEquals(request.options, context.overlayPlaybackStateStore.state.value.options)
                    assertTrue(context.overlayPlaybackStateStore.state.value.itemCount > 0)

                    context.startService(Intent(context, OverlayCommentService::class.java).apply {
                        action = ACTION_STOP_OVERLAY
                    })
                    waitForStatus(context, OverlayPlaybackStatus.IDLE)
                }
            }
        } finally {
            runBlocking {
                context.memoRepository.moveToTrash(memo.id)
                context.memoRepository.deletePermanently(memo.id)
            }
            context.overlayPlaybackStateStore.idle()
        }
    }

    private fun waitForStatus(context: MemoRippleApplication, expected: OverlayPlaybackStatus) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            if (context.overlayPlaybackStateStore.state.value.status == expected) return
            Thread.sleep(25L)
        }
        throw AssertionError(
            "Expected $expected but was ${context.overlayPlaybackStateStore.state.value}",
        )
    }
}
