package com.glassstorm.phonemanager.adapter.android.config

import android.content.Context
import android.content.SharedPreferences
import com.glassstorm.phonemanager.core.domain.adapter.config.AppConfig
import com.glassstorm.phonemanager.core.model.HotspotMode
import java.util.Locale

/**
 * App-private runtime configuration, read at composition time.
 *
 * Posture mirrors the reference client's `RuntimeConfigStore`: a single
 * `MODE_PRIVATE` `SharedPreferences` file and values trimmed on write.
 *
 * The prefs file name is [PREFS_NAME] = `runtime_config`, which `:app`'s
 * `backup_rules.xml` / `data_extraction_rules.xml` exclude from cloud backup and
 * device transfer.
 *
 * This class is the IMPLEMENTATION of the domain [AppConfig] port: the Settings
 * screen reads and writes only the domain [HotspotMode], while the on-disk spelling
 * stays private to this adapter. The hub runs a single on-device speech engine, so
 * there is no STT-engine choice, API key or region to persist any more.
 *
 * The DEFAULT is the safe one: [HotspotMode.MANUAL] (the user owns the access point).
 */
class RuntimeConfigStore(
    private val context: Context,
) : AppConfig {
    /** Persist the hotspot mode under the private key spelling. */
    override fun setHotspotMode(mode: HotspotMode) {
        prefs().edit().putString(KEY_HOTSPOT_MODE, hotspotModeToKey(mode)).apply()
    }

    /** The configured hotspot mode, or [HotspotMode.MANUAL] when unset or unrecognised. */
    override fun hotspotMode(): HotspotMode =
        when (prefs().getString(KEY_HOTSPOT_MODE, null)?.trim()?.lowercase(Locale.ROOT)) {
            HOTSPOT_AUTO -> HotspotMode.AUTO
            else -> HotspotMode.MANUAL
        }

    private fun prefs(): SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun hotspotModeToKey(mode: HotspotMode): String =
        when (mode) {
            HotspotMode.MANUAL -> HOTSPOT_MANUAL
            HotspotMode.AUTO -> HOTSPOT_AUTO
        }

    companion object {
        /** The app-private prefs file; matches the reference repo's backup exclusions. */
        const val PREFS_NAME: String = "runtime_config"

        const val KEY_HOTSPOT_MODE: String = "hotspot_mode"

        const val HOTSPOT_MANUAL: String = "manual"
        const val HOTSPOT_AUTO: String = "auto"
    }
}
