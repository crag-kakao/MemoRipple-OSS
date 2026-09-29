package io.github.cragcoffee.memoripple.llmbench

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import java.io.File
import org.json.JSONObject

/**
 * One snapshot of the process and device state (docs/LLM_PHASE0.md §7). Every number is
 * read from the platform at the moment of the call; nothing is estimated.
 *
 * RSS / HWM come from /proc/self/status (VmRSS, VmHWM), the native heap from
 * [Debug.getNativeHeapAllocatedSize], the Java heap from the runtime, device free memory
 * from [ActivityManager.MemoryInfo]. A mmap-loaded model is counted in RSS only for the
 * pages actually touched, so "pss" is recorded too when available.
 */
data class Snapshot(
    val label: String,
    val atMs: Long,
    val vmRssKb: Long,
    val vmHwmKb: Long,
    val pssKb: Long,
    val nativeHeapKb: Long,
    val javaHeapKb: Long,
    val deviceAvailKb: Long,
    val deviceLowMemory: Boolean,
    val batteryPercent: Int,
    val batteryTempDeciC: Int,
    val batteryPlugged: Boolean,
    val thermalStatus: Int,
    val cpuTempMilliC: Long?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("label", label)
        .put("atMs", atMs)
        .put("vmRssKb", vmRssKb)
        .put("vmHwmKb", vmHwmKb)
        .put("pssKb", pssKb)
        .put("nativeHeapKb", nativeHeapKb)
        .put("javaHeapKb", javaHeapKb)
        .put("deviceAvailKb", deviceAvailKb)
        .put("deviceLowMemory", deviceLowMemory)
        .put("batteryPercent", batteryPercent)
        .put("batteryTempDeciC", batteryTempDeciC)
        .put("batteryPlugged", batteryPlugged)
        .put("thermalStatus", thermalStatus)
        .put("cpuTempMilliC", cpuTempMilliC ?: JSONObject.NULL)
}

class Metrics(private val context: Context) {
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    fun snapshot(label: String): Snapshot {
        val status = readProcStatus()
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val memInfo = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val runtime = Runtime.getRuntime()
        val pss = try {
            Debug.getPss()
        } catch (_: Throwable) {
            -1L
        }
        return Snapshot(
            label = label,
            atMs = System.currentTimeMillis(),
            vmRssKb = status["VmRSS"] ?: -1,
            vmHwmKb = status["VmHWM"] ?: -1,
            pssKb = pss,
            nativeHeapKb = Debug.getNativeHeapAllocatedSize() / 1024,
            javaHeapKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024,
            deviceAvailKb = memInfo.availMem / 1024,
            deviceLowMemory = memInfo.lowMemory,
            batteryPercent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            batteryTempDeciC = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1,
            batteryPlugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
            thermalStatus = powerManager.currentThermalStatus,
            cpuTempMilliC = readCpuTemp(),
        )
    }

    fun thermalStatus(): Int = powerManager.currentThermalStatus

    /** Charge counter in µAh when the kernel exposes it, else null; the delta over a run is the plan's battery number. */
    fun chargeCounterMicroAh(): Long? {
        val v = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        return if (v == Long.MIN_VALUE || v <= 0) null else v
    }

    private fun readProcStatus(): Map<String, Long> {
        val out = HashMap<String, Long>()
        try {
            File("/proc/self/status").forEachLine { line ->
                val key = line.substringBefore(':', "")
                if (key == "VmRSS" || key == "VmHWM" || key == "VmSize") {
                    val kb = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull()
                    if (kb != null) out[key] = kb
                }
            }
        } catch (_: Throwable) {
        }
        return out
    }

    private fun readCpuTemp(): Long? {
        // Best effort: the first thermal zone whose type mentions the CPU; many vendors hide these
        // from apps, in which case the value stays null and only the battery temperature counts.
        return try {
            File("/sys/class/thermal").listFiles()
                ?.filter { it.name.startsWith("thermal_zone") }
                ?.firstOrNull { zone ->
                    val type = File(zone, "type").readText().trim().lowercase()
                    "cpu" in type || "ap" == type || "big" in type
                }
                ?.let { File(it, "temp").readText().trim().toLongOrNull() }
        } catch (_: Throwable) {
            null
        }
    }
}
