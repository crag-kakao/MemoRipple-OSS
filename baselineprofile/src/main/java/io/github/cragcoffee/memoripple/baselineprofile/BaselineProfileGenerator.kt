package io.github.cragcoffee.memoripple.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Walks the paths a session actually takes — cold start onto the wall, into the editor, a line
 * typed — so their classes are compiled ahead of time instead of warming up under the user's
 * fingers. The output is committed into app/src/release/generated/baselineProfiles and ships
 * inside the release artifact.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startUpAndWrite() {
        rule.collect(packageName = "io.github.cragcoffee.memoripple") {
            pressHome()
            startActivityAndWait()
            // The wall settles (empty state or cards).
            device.wait(Until.hasObject(By.pkg(packageName).depth(0)), 5_000)

            // Into the editor: the FAB sits bottom-right on every wall.
            val width = device.displayWidth
            val height = device.displayHeight
            device.click((width * 0.89).toInt(), (height * 0.83).toInt())
            device.waitForIdle()
            Thread.sleep(600)

            // A line of writing exercises the editor's per-keystroke paths.
            device.click((width * 0.5).toInt(), (height * 0.33).toInt())
            device.waitForIdle()
            Thread.sleep(300)
        }
    }
}
