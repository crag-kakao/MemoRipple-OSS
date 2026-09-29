package io.github.cragcoffee.memoripple.data.ai.models

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import io.github.cragcoffee.memoripple.data.SettingsRepository
import io.github.cragcoffee.memoripple.data.ai.llamacpp.CpuFeatures
import io.github.cragcoffee.memoripple.domain.ai.models.DeviceGuards
import io.github.cragcoffee.memoripple.domain.ai.models.SelectedModelStore
import kotlinx.coroutines.flow.Flow

/** The platform behind [DeviceGuards]: free space on the no-backup volume, metered network, battery, the CPU feature gate. ACCESS_NETWORK_STATE (normal) is the one permission this needs, for the metered check. */
class AndroidDeviceGuards(private val context: Context) : DeviceGuards {
    override fun usableBytes(): Long = context.noBackupFilesDir.usableSpace

    /** Needs ACCESS_NETWORK_STATE (declared); if the platform still refuses to answer, the network counts as metered and the user is asked. */
    override fun isMetered(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        return try { cm.isActiveNetworkMetered } catch (_: SecurityException) { true }
    }

    override fun batteryPercent(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return 100
        val p = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (p in 0..100) p else 100
    }

    override fun isCharging(): Boolean {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return false
        return bm.isCharging
    }

    override fun supportsLocalInference(): Boolean = CpuFeatures.hasDotProduct()

    /**
     * Phase 7: the same permission and the same decision point as [isMetered] — is there a network
     * the download could use? A one-shot read of the active network's capabilities; no callback,
     * no receiver, nothing monitored. Unknown counts as offline (nothing starts).
     */
    override fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return try {
            val network = cm.activeNetwork ?: return false
            cm.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } catch (_: SecurityException) {
            false
        }
    }
}

/** The selected model id in the app's preferences — the only model preference there is. */
class DataStoreSelectedModelStore(private val settings: SettingsRepository) : SelectedModelStore {
    override val selected: Flow<String?> get() = settings.selectedAiModelId
    override suspend fun set(modelId: String?) = settings.setSelectedAiModelId(modelId)
}
