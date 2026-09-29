package io.github.cragcoffee.memoripple

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bisect behind docs/TEST_MEMORY_AUDIT.md, kept as three small probes on a bare
 * androidx.activity.ComponentActivity (declared by ui-test-manifest; no product code). Content is
 * installed from onActivityCreated — after onCreate, before the window exists — the same shape as
 * MainActivity.setContent. The AndroidComposeView's attach / detach counts are logged under
 * ComposeViewRegistryAudit; the two healthy shapes assert one attach and one detach.
 */
@RunWith(AndroidJUnit4::class)
class EdgeToEdgeAttachProbeTest {
    private val tag = "ComposeViewRegistryAudit"

    private fun probe(label: String, onCreated: (ComponentActivity) -> Unit): Pair<Int, Int> {
        val events = java.util.Collections.synchronizedList(ArrayList<String>())
        val app = ApplicationProvider.getApplicationContext<Application>()
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity !is ComponentActivity) return
                onCreated(activity)
                val composeView = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
                composeView.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                    override fun onChildViewAdded(parent: View, child: View) {
                        child.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(v: View) { events += "ATTACH" }
                            override fun onViewDetachedFromWindow(v: View) { events += "DETACH" }
                        })
                    }
                    override fun onChildViewRemoved(parent: View, child: View) = Unit
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
        try {
            val scenario = ActivityScenario.launch(ComponentActivity::class.java)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.close()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        } finally {
            app.unregisterActivityLifecycleCallbacks(callbacks)
        }
        val attach = events.count { it == "ATTACH" }
        val detach = events.count { it == "DETACH" }
        Log.i(tag, "$label: attach=$attach detach=$detach")
        return attach to detach
    }

    private val transparent = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)

    @Test
    fun bareComposeContentAttachesOnce() {
        val counts = probe("bare compose") { activity ->
            activity.enableEdgeToEdge()
            activity.setContent { Text("probe") }
        }
        assertEquals("attach/detach", 1 to 1, counts)
    }

    @Test
    fun enableEdgeToEdgePostedFromLaunchedEffectAttachesOnce() {
        val counts = probe("LaunchedEffect { decorView.post { enableEdgeToEdge } }") { activity ->
            activity.enableEdgeToEdge()
            activity.setContent {
                LaunchedEffect(Unit) {
                    activity.window.decorView.post { activity.enableEdgeToEdge(transparent, transparent) }
                }
                Text("probe")
            }
        }
        assertEquals("attach/detach", 1 to 1, counts)
    }

    /**
     * Logged only: attaches once under a plain ActivityScenario, but under the Compose test host
     * the frame clock runs the effect inside the first traversal and it attaches twice
     * (ComposeRuleRegistryTripwireTest is the guard for that shape).
     */
    @Test
    fun enableEdgeToEdgeFromLaunchedEffectIsLogged() {
        probe("LaunchedEffect { enableEdgeToEdge }") { activity ->
            activity.enableEdgeToEdge()
            activity.setContent {
                LaunchedEffect(Unit) { activity.enableEdgeToEdge(transparent, transparent) }
                Text("probe")
            }
        }
    }

    /** The shape MainActivity uses today. Logged only: the count is the platform's to change. */
    @Test
    fun enableEdgeToEdgeFromSideEffectIsLogged() {
        probe("SideEffect { enableEdgeToEdge }") { activity ->
            activity.enableEdgeToEdge()
            activity.setContent {
                SideEffect { activity.enableEdgeToEdge(transparent, transparent) }
                Text("probe")
            }
        }
    }
}
