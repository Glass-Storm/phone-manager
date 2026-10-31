package com.glassstorm.phonemanager.core.domain.adapter.config

import com.glassstorm.phonemanager.core.model.HotspotMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the [AppConfig] port shape and the domain vocabulary it exposes.
 *
 * [HotspotMode] is a FROZEN contract: `:app` selects a mode through it and
 * `:adapter` maps it onto its own persistence keys, so adding or renaming a value
 * is a deliberate, reviewable change rather than an accident. The port carries no
 * speech-engine state: STT is a single on-device engine with no cloud path.
 */
class AppConfigTest {
    @Test
    fun `the hotspot modes are exactly manual and auto`() {
        assertEquals(listOf(HotspotMode.MANUAL, HotspotMode.AUTO), HotspotMode.entries.toList())
    }

    @Test
    fun `an implementation round-trips the hotspot mode through the port`() {
        // Given an in-memory implementation of the port
        val config: AppConfig = MemoryConfig()

        // When the mode is written, then rewritten
        config.setHotspotMode(HotspotMode.AUTO)
        assertEquals(HotspotMode.AUTO, config.hotspotMode())

        config.setHotspotMode(HotspotMode.MANUAL)

        // Then it reads back through the same port
        assertEquals(HotspotMode.MANUAL, config.hotspotMode())
    }

    @Test
    fun `a fresh implementation defaults to manual hotspot`() {
        // Given a port with nothing written
        val config: AppConfig = MemoryConfig()

        // Then the safe default holds: the user owns the access point
        assertEquals(HotspotMode.MANUAL, config.hotspotMode())
    }

    /** Minimal in-memory port implementation: the contract the adapter must satisfy. */
    private class MemoryConfig : AppConfig {
        private var mode: HotspotMode = HotspotMode.MANUAL

        override fun hotspotMode(): HotspotMode = mode

        override fun setHotspotMode(mode: HotspotMode) {
            this.mode = mode
        }
    }
}
