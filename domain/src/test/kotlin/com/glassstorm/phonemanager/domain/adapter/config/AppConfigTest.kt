package com.glassstorm.phonemanager.domain.adapter.config

import com.glassstorm.phonemanager.core.model.HotspotMode
import com.glassstorm.phonemanager.core.model.SttEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the [AppConfig] port shape and the domain vocabulary it exposes.
 *
 * The two enums are a FROZEN contract: `:app` selects an engine/mode through them
 * and `:adapter` maps them onto its own persistence keys, so adding or renaming a
 * value is a deliberate, reviewable change rather than an accident.
 */
class AppConfigTest {
    @Test
    fun `the speech engines are exactly the offline mock and the cloud engine`() {
        assertEquals(listOf(SttEngine.MOCK, SttEngine.SPEECHMATICS), SttEngine.entries.toList())
    }

    @Test
    fun `the hotspot modes are exactly manual and auto`() {
        assertEquals(listOf(HotspotMode.MANUAL, HotspotMode.AUTO), HotspotMode.entries.toList())
    }

    @Test
    fun `the default region is the us endpoint`() {
        assertEquals("us", AppConfig.DEFAULT_REGION)
    }

    @Test
    fun `an implementation round-trips every setting through the port`() {
        // Given an in-memory implementation of the port
        val config: AppConfig = MemoryConfig()

        // When every setting is written
        config.setSttEngine(SttEngine.SPEECHMATICS)
        config.setApiKey("  key-1  ")
        config.setRegion("eu")
        config.setHotspotMode(HotspotMode.AUTO)

        // Then every setting reads back through the same port
        assertEquals(SttEngine.SPEECHMATICS, config.sttEngine())
        assertEquals("key-1", config.apiKey())
        assertEquals("eu", config.region())
        assertEquals(HotspotMode.AUTO, config.hotspotMode())
    }

    @Test
    fun `a fresh implementation defaults to the offline engine and manual hotspot`() {
        // Given a port with nothing written
        val config: AppConfig = MemoryConfig()

        // Then the safe defaults hold: no network engine, no automatic access point
        assertEquals(SttEngine.MOCK, config.sttEngine())
        assertEquals("", config.apiKey())
        assertEquals(AppConfig.DEFAULT_REGION, config.region())
        assertEquals(HotspotMode.MANUAL, config.hotspotMode())
    }

    @Test
    fun `a blank api key clears the stored key`() {
        val config: AppConfig = MemoryConfig()
        config.setApiKey("key-1")

        config.setApiKey("   ")

        assertEquals("", config.apiKey())
    }

    /** Minimal in-memory port implementation: the contract the adapter must satisfy. */
    private class MemoryConfig : AppConfig {
        private var engine: SttEngine = SttEngine.MOCK
        private var key: String = ""
        private var regionValue: String = AppConfig.DEFAULT_REGION
        private var mode: HotspotMode = HotspotMode.MANUAL

        override fun sttEngine(): SttEngine = engine

        override fun setSttEngine(kind: SttEngine) {
            engine = kind
        }

        override fun apiKey(): String = key

        override fun setApiKey(apiKey: String?) {
            key = apiKey?.trim().orEmpty()
        }

        override fun region(): String = regionValue

        override fun setRegion(region: String?) {
            region
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() }
                ?.let { regionValue = it }
        }

        override fun hotspotMode(): HotspotMode = mode

        override fun setHotspotMode(mode: HotspotMode) {
            this.mode = mode
        }
    }
}
