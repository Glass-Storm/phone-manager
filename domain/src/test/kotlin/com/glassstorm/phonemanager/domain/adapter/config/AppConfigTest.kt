package com.glassstorm.phonemanager.domain.adapter.config

import com.glassstorm.phonemanager.domain.dto.HotspotMode
import com.glassstorm.phonemanager.domain.dto.SttEngine
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
        assertEquals("us", AppConfig.GoDefaultRegion)
    }

    @Test
    fun `an implementation round-trips every setting through the port`() {
        // Given an in-memory implementation of the port
        val GoConfig: AppConfig = GoMemoryConfig()

        // When every setting is written
        GoConfig.GoSetSttEngine(SttEngine.SPEECHMATICS)
        GoConfig.GoSetApiKey("  key-1  ")
        GoConfig.GoSetRegion("eu")
        GoConfig.GoSetHotspotMode(HotspotMode.AUTO)

        // Then every setting reads back through the same port
        assertEquals(SttEngine.SPEECHMATICS, GoConfig.GoSttEngine())
        assertEquals("key-1", GoConfig.GoApiKey())
        assertEquals("eu", GoConfig.GoRegion())
        assertEquals(HotspotMode.AUTO, GoConfig.GoHotspotMode())
    }

    @Test
    fun `a fresh implementation defaults to the offline engine and manual hotspot`() {
        // Given a port with nothing written
        val GoConfig: AppConfig = GoMemoryConfig()

        // Then the safe defaults hold: no network engine, no automatic access point
        assertEquals(SttEngine.MOCK, GoConfig.GoSttEngine())
        assertEquals("", GoConfig.GoApiKey())
        assertEquals(AppConfig.GoDefaultRegion, GoConfig.GoRegion())
        assertEquals(HotspotMode.MANUAL, GoConfig.GoHotspotMode())
    }

    @Test
    fun `a blank api key clears the stored key`() {
        val GoConfig: AppConfig = GoMemoryConfig()
        GoConfig.GoSetApiKey("key-1")

        GoConfig.GoSetApiKey("   ")

        assertEquals("", GoConfig.GoApiKey())
    }

    /** Minimal in-memory port implementation: the contract the adapter must satisfy. */
    private class GoMemoryConfig : AppConfig {
        private var GoEngine: SttEngine = SttEngine.MOCK
        private var GoKey: String = ""
        private var GoRegionValue: String = AppConfig.GoDefaultRegion
        private var GoMode: HotspotMode = HotspotMode.MANUAL

        override fun GoSttEngine(): SttEngine = GoEngine

        override fun GoSetSttEngine(kind: SttEngine) {
            GoEngine = kind
        }

        override fun GoApiKey(): String = GoKey

        override fun GoSetApiKey(apiKey: String?) {
            GoKey = apiKey?.trim().orEmpty()
        }

        override fun GoRegion(): String = GoRegionValue

        override fun GoSetRegion(region: String?) {
            region
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() }
                ?.let { GoRegionValue = it }
        }

        override fun GoHotspotMode(): HotspotMode = GoMode

        override fun GoSetHotspotMode(mode: HotspotMode) {
            GoMode = mode
        }
    }
}
