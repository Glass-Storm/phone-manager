package com.glassstorm.phonemanager.domain.adapter.config

import com.glassstorm.phonemanager.core.model.HotspotMode
import com.glassstorm.phonemanager.core.model.SttEngine

/**
 * Port for the hub's user-editable runtime configuration.
 *
 * The Settings screen (`:app`) reads and writes through this DOMAIN interface, so
 * `:app/ui` never imports the `:adapter` implementation that actually persists the
 * values. The adapter owns the storage shape (an app-private prefs file whose
 * `runtime_config.xml` is excluded from backup); the domain owns the vocabulary
 * ([SttEngine], [HotspotMode]) and the safe defaults.
 *
 * Every getter is total: an unset or unrecognised value resolves to the safe
 * default rather than throwing, so a half-configured install still renders.
 *
 * The API key is a secret: implementations MUST NOT log it, and callers MUST NOT
 * render it in cleartext by default.
 */
interface AppConfig {
    /** The configured speech engine, defaulting to [SttEngine.MOCK]. */
    fun sttEngine(): SttEngine

    /** Persist the chosen speech engine. */
    fun setSttEngine(kind: SttEngine)

    /** The stored Speechmatics API key, or `""` when unset. NEVER log this. */
    fun apiKey(): String

    /** Store [apiKey] trimmed; a blank value removes it rather than storing `""`. */
    fun setApiKey(apiKey: String?)

    /** The configured Speechmatics region, or [DEFAULT_REGION] when unset. */
    fun region(): String

    /** Persist [region] when it is supported; an unsupported value is ignored. */
    fun setRegion(region: String?)

    /** The configured hotspot mode, defaulting to [HotspotMode.MANUAL]. */
    fun hotspotMode(): HotspotMode

    /** Persist the chosen hotspot mode. */
    fun setHotspotMode(mode: HotspotMode)

    companion object {
        /** The region used when nothing else is configured. */
        const val DEFAULT_REGION: String = "us"
    }
}
