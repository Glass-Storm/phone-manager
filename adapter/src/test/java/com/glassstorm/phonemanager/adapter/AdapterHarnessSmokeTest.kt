package com.glassstorm.phonemanager.adapter

import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Proves the Robolectric harness actually runs on the `:adapter` module.
 * Later adapter todos (hotspot, NSD, SQLite) depend on this harness.
 */
@RunWith(RobolectricTestRunner::class)
class AdapterHarnessSmokeTest {
    @Test
    fun `robolectric provides an application instance`() {
        assertNotNull(RuntimeEnvironment.getApplication())
    }
}
