package io.github.cragcoffee.memoripple.domain.export

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableAutoExportPolicyTest {

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun aFreshSetupIsDueAtOnce() {
        assertTrue(PortableAutoExportPolicy.isDue(nowMillis = 100, lastRunMillis = 0, intervalDays = 1))
    }

    @Test
    fun theIntervalGates() {
        assertFalse(PortableAutoExportPolicy.isDue(nowMillis = day, lastRunMillis = 1, intervalDays = 1))
        assertTrue(PortableAutoExportPolicy.isDue(nowMillis = day + 1, lastRunMillis = 1, intervalDays = 1))
        assertFalse(PortableAutoExportPolicy.isDue(nowMillis = 6 * day, lastRunMillis = 1, intervalDays = 7))
        assertTrue(PortableAutoExportPolicy.isDue(nowMillis = 7 * day + 1, lastRunMillis = 1, intervalDays = 7))
    }

    @Test
    fun aClockThatWentBackwardsStillRuns() {
        assertTrue(PortableAutoExportPolicy.isDue(nowMillis = 50, lastRunMillis = 100, intervalDays = 7))
    }
}
