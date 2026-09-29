package io.github.cragcoffee.memoripple

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tripwire for the instrumentation-process leak found by the memory audit
 * (docs/TEST_MEMORY_AUDIT.md). MainActivity's first frame attaches its AndroidComposeView twice
 * and detaches it once, so Compose UI's private static registry AndroidComposeView.composeViews
 * (and the snapshot apply-observer list behind it) keeps one detached view — and through it the
 * whole destroyed activity — per MainActivity lifetime, about 1 MB per test. The registry is
 * the cheapest place to observe that asymmetry: this test fails while it exists.
 */
@RunWith(AndroidJUnit4::class)
class ComposeViewRegistryAuditTest {
    private val tag = "ComposeViewRegistryAudit"
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private val registry: Any by lazy {
        Class.forName("androidx.compose.ui.platform.AndroidComposeView")
            .getDeclaredField("composeViews").apply { isAccessible = true }.get(null)
    }

    private fun registrySize(): Int = registry.javaClass.getMethod("getSize").invoke(registry) as Int

    private fun settle() {
        instrumentation.waitForIdleSync()
        // The window is removed on the main thread right after onDestroy; let that pass complete.
        instrumentation.runOnMainSync { }
        instrumentation.waitForIdleSync()
    }

    @Test
    fun theStaticComposeViewListDoesNotGrowAcrossActivityLifetimes() {
        val before = registrySize()
        val afterEachClose = ArrayList<Int>()
        repeat(4) { round ->
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            instrumentation.waitForIdleSync()
            val during = registrySize()
            scenario.close()
            settle()
            afterEachClose += registrySize()
            Log.i(tag, "round $round: during=$during after=${afterEachClose.last()}")
        }
        assertEquals(
            "registry entries left behind after each MainActivity close: $afterEachClose (before: $before)",
            List(4) { before },
            afterEachClose,
        )
    }

    /**
     * Characterisation, not a guard: how many times the activity's AndroidComposeView is
     * attached and detached over one MainActivity lifetime. On the Pixel_10 / API 36 emulator
     * with Compose UI 1.10.0 the first frame attaches it twice (both from
     * ViewRootImpl.performTraversals) and detaches it once. The counts are logged so a platform /
     * library change is visible; the assertion is that one lifetime leaves nothing in the registry.
     */
    @Test
    fun mainActivityAttachAndDetachCountsOverOneLifetime() {
        val events = java.util.Collections.synchronizedList(ArrayList<String>())
        val app = ApplicationProvider.getApplicationContext<Application>()
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity !is MainActivity) return
                // setContent runs after super.onCreate, so the ComposeView is not there yet; watch the
                // content view for it, then watch it for the AndroidComposeView it creates on attach.
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                content.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                    override fun onChildViewRemoved(parent: View, child: View) = Unit
                    override fun onChildViewAdded(parent: View, composeView: View) {
                        (composeView as ViewGroup).setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                            override fun onChildViewRemoved(p: View, c: View) = Unit
                            override fun onChildViewAdded(p: View, child: View) {
                                child.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                                    override fun onViewAttachedToWindow(v: View) { events += "ATTACH" }
                                    override fun onViewDetachedFromWindow(v: View) { events += "DETACH" }
                                })
                            }
                        })
                    }
                })
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        app.registerActivityLifecycleCallbacks(callbacks)
        val before = registrySize()
        try {
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            instrumentation.waitForIdleSync()
            val during = registrySize()
            scenario.close()
            settle()
            val after = registrySize()
            Log.i(
                tag,
                "MainActivity lifetime: attach=${events.count { it == "ATTACH" }} " +
                    "detach=${events.count { it == "DETACH" }} registry before=$before during=$during after=$after",
            )
            assertEquals("registry entries left behind by one MainActivity lifetime", before, after)
        } finally {
            app.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }
}
