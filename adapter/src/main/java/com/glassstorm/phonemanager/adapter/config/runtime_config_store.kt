package com.glassstorm.phonemanager.adapter.config

import android.content.Context
import android.content.SharedPreferences
import com.glassstorm.phonemanager.domain.adapter.config.AppConfig
import com.glassstorm.phonemanager.domain.dto.HotspotMode
import com.glassstorm.phonemanager.domain.dto.SttEngine
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
 * The prefs file name is [GO_PREFS_NAME] = `runtime_config`, which `:app`'s
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
    private val GoContext: Context,
) : AppConfig {
    /** Persist [kind]; a fresh install with no write at all stays on [SttAdapterKind.MOCK]. */
    fun GoSetSttAdapterKind(kind: SttAdapterKind) {
        GoPrefs().edit().putString(GO_KEY_STT_ADAPTER, GoKindToKey(kind)).apply()
    }

    /** The configured engine, or [SttAdapterKind.MOCK] when unset or unrecognised. */
    fun GoSttAdapterKind(): SttAdapterKind = GoKindFromKey(GoPrefs().getString(GO_KEY_STT_ADAPTER, null))

    override fun GoSttEngine(): SttEngine =
        when (GoSttAdapterKind()) {
            SttAdapterKind.MOCK -> SttEngine.MOCK
            SttAdapterKind.SPEECHMATICS -> SttEngine.SPEECHMATICS
        }

    override fun GoSetSttEngine(kind: SttEngine) {
        GoSetSttAdapterKind(
            when (kind) {
                SttEngine.MOCK -> SttAdapterKind.MOCK
                SttEngine.SPEECHMATICS -> SttAdapterKind.SPEECHMATICS
            },
        )
    }

    /** Store [apiKey] trimmed; a blank value REMOVES the key rather than storing "". */
    override fun GoSetApiKey(apiKey: String?) {
        val GoTrimmed = apiKey?.trim().orEmpty()
        if (GoTrimmed.isEmpty()) {
            GoPrefs().edit().remove(GO_KEY_API_KEY).apply()
        } else {
            GoPrefs().edit().putString(GO_KEY_API_KEY, GoTrimmed).apply()
        }
    }

    /** The stored API key, or `""`. NEVER log this value. */
    override fun GoApiKey(): String = GoPrefs().getString(GO_KEY_API_KEY, "").orEmpty()

    /** True when a usable key is present (the reference's placeholder does not count). */
    fun GoHasApiKey(): Boolean {
        val GoKey = GoApiKey()
        return GoKey.isNotEmpty() && GoKey != GO_PLACEHOLDER_API_KEY
    }

    /**
     * Persist [region] lowercased when it is one of `global|eu|us|au`; any other
     * value is ignored, leaving the previous choice intact.
     */
    override fun GoSetRegion(region: String?) {
        val GoLower = region?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (GoLower in GO_KNOWN_REGIONS) {
            GoPrefs().edit().putString(GO_KEY_REGION, GoLower).apply()
        }
    }

    /** The configured region, or [GO_DEFAULT_REGION] (`us`) when unset. */
    override fun GoRegion(): String = GoPrefs().getString(GO_KEY_REGION, GO_DEFAULT_REGION).orEmpty()

    /** Persist the hotspot mode under the private key spelling. */
    override fun GoSetHotspotMode(mode: HotspotMode) {
        GoPrefs().edit().putString(GO_KEY_HOTSPOT_MODE, GoHotspotModeToKey(mode)).apply()
    }

    /** The configured hotspot mode, or [HotspotMode.MANUAL] when unset or unrecognised. */
    override fun GoHotspotMode(): HotspotMode =
        when (GoPrefs().getString(GO_KEY_HOTSPOT_MODE, null)?.trim()?.lowercase(Locale.ROOT)) {
            GO_HOTSPOT_AUTO -> HotspotMode.AUTO
            else -> HotspotMode.MANUAL
        }

    private fun GoPrefs(): SharedPreferences = GoContext.getSharedPreferences(GO_PREFS_NAME, Context.MODE_PRIVATE)

    private fun GoKindToKey(kind: SttAdapterKind): String =
        when (kind) {
            SttAdapterKind.MOCK -> GO_ADAPTER_MOCK
            SttAdapterKind.SPEECHMATICS -> GO_ADAPTER_SPEECHMATICS
        }

    private fun GoKindFromKey(key: String?): SttAdapterKind =
        when (key?.trim()?.lowercase(Locale.ROOT)) {
            GO_ADAPTER_SPEECHMATICS -> SttAdapterKind.SPEECHMATICS
            else -> SttAdapterKind.MOCK
        }

    private fun GoHotspotModeToKey(mode: HotspotMode): String =
        when (mode) {
            HotspotMode.MANUAL -> GO_HOTSPOT_MANUAL
            HotspotMode.AUTO -> GO_HOTSPOT_AUTO
        }

    companion object {
        /** The app-private prefs file; matches the reference repo's backup exclusions. */
        const val GO_PREFS_NAME: String = "runtime_config"

        const val GO_KEY_STT_ADAPTER: String = "stt_adapter"
        const val GO_KEY_API_KEY: String = "api_key"
        const val GO_KEY_REGION: String = "region"
        const val GO_KEY_HOTSPOT_MODE: String = "hotspot_mode"

        const val GO_ADAPTER_MOCK: String = "mock"
        const val GO_ADAPTER_SPEECHMATICS: String = "speechmatics"

        const val GO_HOTSPOT_MANUAL: String = "manual"
        const val GO_HOTSPOT_AUTO: String = "auto"

        const val GO_DEFAULT_REGION: String = "us"

        /** A sample value users sometimes paste; it is NOT a usable key. */
        const val GO_PLACEHOLDER_API_KEY: String = "YOUR_SPEECHMATICS_API_KEY"

        private val GO_KNOWN_REGIONS = setOf("global", "eu", "us", "au")
    }
}
