package io.github.cragcoffee.memoripple

import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The same tripwire as ComposeViewRegistryAuditTest, but in the shape every screen test uses:
 * `createAndroidComposeRule<MainActivity>()`. Under the Compose test host the frame clock runs
 * composition effects inside the first traversal, so a fix that only holds for a plain
 * ActivityScenario (a `LaunchedEffect` without a post) still leaks here. Whichever of the
 * class's tests runs first records the registry size; every later test must see the same size —
 * one attached compose root for the live activity and nothing left over from the previous test.
 */
@RunWith(AndroidJUnit4::class)
class ComposeRuleRegistryTripwireTest {
    companion object {
        @Volatile private var sizeSeenFirst: Int? = null
    }

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun registrySize(): Int {
        val list = Class.forName("androidx.compose.ui.platform.AndroidComposeView")
            .getDeclaredField("composeViews").apply { isAccessible = true }.get(null)
        return list.javaClass.getMethod("getSize").invoke(list) as Int
    }

    private fun check() {
        composeRule.waitForIdle()
        val size = registrySize()
        Log.i("ComposeViewRegistryAudit", "compose rule test: registry=$size (first seen ${sizeSeenFirst ?: "now"})")
        val first = sizeSeenFirst
        if (first == null) sizeSeenFirst = size
        else assertEquals("compose roots in the static registry while one MainActivity is live", first, size)
    }

    @Test fun firstLifetime() = check()
    @Test fun secondLifetime() = check()
    @Test fun thirdLifetime() = check()
}
