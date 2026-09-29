package io.github.cragcoffee.memoripple.domain.export

/**
 * When the automatic 読める形式 export runs: on app launch, if enough days have passed since
 * the last one. Never in the background, never on a schedule of its own — opening the app is
 * the clock. A never-run setup is due immediately, and a clock that seems to have gone
 * backwards (device time change) counts as due rather than silently never running again.
 */
object PortableAutoExportPolicy {

    const val DAILY = 1
    const val WEEKLY = 7

    fun isDue(nowMillis: Long, lastRunMillis: Long, intervalDays: Int): Boolean {
        if (lastRunMillis <= 0L) return true
        if (nowMillis < lastRunMillis) return true
        return nowMillis - lastRunMillis >= intervalDays * MILLIS_PER_DAY
    }

    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
}
