package com.glassstorm.phonemanager.adapter.config

import android.content.Context
import android.content.SharedPreferences
import com.glassstorm.phonemanager.core.domain.adapter.config.AppConfig
import com.glassstorm.phonemanager.core.model.HotspotMode
import com.glassstorm.phonemanager.core.model.SttEngine
import java.util.Locale

/** Which speech engine the composition root should build. */
enum class SttAdapterKind {
    /** Fully offline, deterministic pseudo-transcription. The v1 DEFAULT. */
    MOCK,

    /** Speechmatics realtime cloud recognition. Requires an API key + uplink. */
    SPEECHMATICS,
}

/**
 * App-private runtime configuration, read at composition time.
 *
 * Posture mirrors the reference client's `RuntimeConfigStore`: a single
 * `MODE_PRIVATE` `SharedPreferences` file, values trimmed on write, unknown
 * regions silently ignored, and the API key treated as a secret (never logged,
 * never persisted anywhere else).
 *
 * The prefs file name is [PREFS_NAME] = `runtime_config`, which `:app`'s
 * `backup_rules.xml` / `data_extraction_rules.xml` exclude from cloud backup and
 * device transfer (`runtime_config.xml`) — the API key is therefore out of any
 * backup, because the file name matches.
 *
 * This class is the IMPLEMENTATION of the domain [AppConfig] port: the Settings
 * screen reads and writes only the domain types ([SttEngine], [HotspotMode]),
 * while the on-disk spelling stays private to this adapter ([SttAdapterKind] and
 * the `*_KEY` constants below).
 *
 * The DEFAULTS are the safe ones: [SttAdapterKind.MOCK] (no key, no network),
 * region `us`, and [HotspotMode.MANUAL] (the user owns the access point).
 */
class RuntimeConfigStore(
    private val context: Context,
) : AppConfig {
    /** Persist [kind]; a fresh install with no write at all stays on [SttAdapterKind.MOCK]. */
    fun setSttAdapterKind(kind: SttAdapterKind) {
        prefs().edit().putString(KEY_STT_ADAPTER, kindToKey(kind)).apply()
    }

    /** The configured engine, or [SttAdapterKind.MOCK] when unset or unrecognised. */
    fun sttAdapterKind(): SttAdapterKind = kindFromKey(prefs().getString(KEY_STT_ADAPTER, null))

    override fun sttEngine(): SttEngine =
        when (sttAdapterKind()) {
            SttAdapterKind.MOCK -> SttEngine.MOCK
            SttAdapterKind.SPEECHMATICS -> SttEngine.SPEECHMATICS
        }

    override fun setSttEngine(kind: SttEngine) {
        setSttAdapterKind(
            when (kind) {
                SttEngine.MOCK -> SttAdapterKind.MOCK
                SttEngine.SPEECHMATICS -> SttAdapterKind.SPEECHMATICS
            },
        )
    }

    /** Store [apiKey] trimmed; a blank value REMOVES the key rather than storing "". */
    override fun setApiKey(apiKey: String?) {
        val trimmed = apiKey?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            prefs().edit().remove(KEY_API_KEY).apply()
        } else {
            prefs().edit().putString(KEY_API_KEY, trimmed).apply()
        }
    }

    /** The stored API key, or `""`. NEVER log this value. */
    override fun apiKey(): String = prefs().getString(KEY_API_KEY, "").orEmpty()

    /** True when a usable key is present (the reference's placeholder does not count). */
    fun hasApiKey(): Boolean {
        val key = apiKey()
        return key.isNotEmpty() && key != PLACEHOLDER_API_KEY
    }

    /**
     * Persist [region] lowercased when it is one of `global|eu|us|au`; any other
     * value is ignored, leaving the previous choice intact.
     */
    override fun setRegion(region: String?) {
        val lower = region?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (lower in KNOWN_REGIONS) {
            prefs().edit().putString(KEY_REGION, lower).apply()
        }
    }

    /** The configured region, or [DEFAULT_REGION] (`us`) when unset. */
    override fun region(): String = prefs().getString(KEY_REGION, DEFAULT_REGION).orEmpty()

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

    private fun kindToKey(kind: SttAdapterKind): String =
        when (kind) {
            SttAdapterKind.MOCK -> ADAPTER_MOCK
            SttAdapterKind.SPEECHMATICS -> ADAPTER_SPEECHMATICS
        }

    private fun kindFromKey(key: String?): SttAdapterKind =
        when (key?.trim()?.lowercase(Locale.ROOT)) {
            ADAPTER_SPEECHMATICS -> SttAdapterKind.SPEECHMATICS
            else -> SttAdapterKind.MOCK
        }

    private fun hotspotModeToKey(mode: HotspotMode): String =
        when (mode) {
            HotspotMode.MANUAL -> HOTSPOT_MANUAL
            HotspotMode.AUTO -> HOTSPOT_AUTO
        }

    companion object {
        /** The app-private prefs file; matches the reference repo's backup exclusions. */
        const val PREFS_NAME: String = "runtime_config"

        const val KEY_STT_ADAPTER: String = "stt_adapter"
        const val KEY_API_KEY: String = "api_key"
        const val KEY_REGION: String = "region"
        const val KEY_HOTSPOT_MODE: String = "hotspot_mode"

        const val ADAPTER_MOCK: String = "mock"
        const val ADAPTER_SPEECHMATICS: String = "speechmatics"

        const val HOTSPOT_MANUAL: String = "manual"
        const val HOTSPOT_AUTO: String = "auto"

        const val DEFAULT_REGION: String = "us"

        /** A sample value users sometimes paste; it is NOT a usable key. */
        const val PLACEHOLDER_API_KEY: String = "YOUR_SPEECHMATICS_API_KEY"

        private val KNOWN_REGIONS = setOf("global", "eu", "us", "au")
    }
}
