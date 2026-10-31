package com.glassstorm.phonemanager.core.domain.adapter.config

import com.glassstorm.phonemanager.core.model.HotspotMode

/**
 * Port for the hub's user-editable runtime configuration.
 *
 * The Settings screen (`:app`) reads and writes through this DOMAIN interface, so
 * `:app/ui` never imports the `:adapter` implementation that actually persists the
 * values. The adapter owns the storage shape (an app-private prefs file whose
 * `runtime_config.xml` is excluded from backup); the domain owns the vocabulary
 * ([HotspotMode]) and the safe defaults.
 *
 * Every getter is total: an unset or unrecognised value resolves to the safe
 * default rather than throwing, so a half-configured install still renders.
 *
 * Speech-to-text is no longer configurable: the hub runs a single on-device engine
 * (`SpeechPort`) and there is no cloud STT path, so this port carries no engine or
 * credential state.
 */
interface AppConfig {
    /** The configured hotspot mode, defaulting to [HotspotMode.MANUAL]. */
    fun hotspotMode(): HotspotMode

    /** Persist the chosen hotspot mode. */
    fun setHotspotMode(mode: HotspotMode)
}
