package com.glassstorm.phonemanager.adapter.config

import android.content.Context
import android.content.SharedPreferences
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
 * The prefs file name is [GO_PREFS_NAME] = `runtime_config`, the same name the
 * reference repo's backup rules exclude from cloud backup and device transfer
 * (`runtime_config.xml`). The corresponding `backup_rules.xml` /
 * `data_extraction_rules.xml` in `:app` are T17's to add (T15 owns no app file)
 * — the key is already out of any backup the moment those rules land, because
 * the file name matches.
 *
 * The DEFAULTS are the safe ones: [SttAdapterKind.MOCK] (no key, no network) and
 * region `us`.
 */
class RuntimeConfigStore(private val GoContext: Context) {

    /** Persist [kind]; a fresh install with no write at all stays on [SttAdapterKind.MOCK]. */
    fun GoSetSttAdapterKind(kind: SttAdapterKind) {
        GoPrefs().edit().putString(GO_KEY_STT_ADAPTER, GoKindToKey(kind)).apply()
    }

    /** The configured engine, or [SttAdapterKind.MOCK] when unset or unrecognised. */
    fun GoSttAdapterKind(): SttAdapterKind =
        GoKindFromKey(GoPrefs().getString(GO_KEY_STT_ADAPTER, null))

    /** Store [apiKey] trimmed; a blank value REMOVES the key rather than storing "". */
    fun GoSetApiKey(apiKey: String?) {
        val GoTrimmed = apiKey?.trim().orEmpty()
        if (GoTrimmed.isEmpty()) {
            GoPrefs().edit().remove(GO_KEY_API_KEY).apply()
        } else {
            GoPrefs().edit().putString(GO_KEY_API_KEY, GoTrimmed).apply()
        }
    }

    /** The stored API key, or `""`. NEVER log this value. */
    fun GoApiKey(): String = GoPrefs().getString(GO_KEY_API_KEY, "").orEmpty()

    /** True when a usable key is present (the reference's placeholder does not count). */
    fun GoHasApiKey(): Boolean {
        val GoKey = GoApiKey()
        return GoKey.isNotEmpty() && GoKey != GO_PLACEHOLDER_API_KEY
    }

    /**
     * Persist [region] lowercased when it is one of `global|eu|us|au`; any other
     * value is ignored, leaving the previous choice intact.
     */
    fun GoSetRegion(region: String?) {
        val GoLower = region?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (GoLower in GO_KNOWN_REGIONS) {
            GoPrefs().edit().putString(GO_KEY_REGION, GoLower).apply()
        }
    }

    /** The configured region, or [GO_DEFAULT_REGION] (`us`) when unset. */
    fun GoRegion(): String = GoPrefs().getString(GO_KEY_REGION, GO_DEFAULT_REGION).orEmpty()

    private fun GoPrefs(): SharedPreferences =
        GoContext.getSharedPreferences(GO_PREFS_NAME, Context.MODE_PRIVATE)

    private fun GoKindToKey(kind: SttAdapterKind): String = when (kind) {
        SttAdapterKind.MOCK -> GO_ADAPTER_MOCK
        SttAdapterKind.SPEECHMATICS -> GO_ADAPTER_SPEECHMATICS
    }

    private fun GoKindFromKey(key: String?): SttAdapterKind = when (key?.trim()?.lowercase(Locale.ROOT)) {
        GO_ADAPTER_SPEECHMATICS -> SttAdapterKind.SPEECHMATICS
        else -> SttAdapterKind.MOCK
    }

    companion object {
        /** The app-private prefs file; matches the reference repo's backup exclusions. */
        const val GO_PREFS_NAME: String = "runtime_config"

        const val GO_KEY_STT_ADAPTER: String = "stt_adapter"
        const val GO_KEY_API_KEY: String = "api_key"
        const val GO_KEY_REGION: String = "region"

        const val GO_ADAPTER_MOCK: String = "mock"
        const val GO_ADAPTER_SPEECHMATICS: String = "speechmatics"

        const val GO_DEFAULT_REGION: String = "us"

        /** A sample value users sometimes paste; it is NOT a usable key. */
        const val GO_PLACEHOLDER_API_KEY: String = "YOUR_SPEECHMATICS_API_KEY"

        private val GO_KNOWN_REGIONS = setOf("global", "eu", "us", "au")
    }
}
