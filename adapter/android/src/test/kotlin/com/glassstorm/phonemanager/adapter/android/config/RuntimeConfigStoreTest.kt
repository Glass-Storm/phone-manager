package com.glassstorm.phonemanager.adapter.android.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.glassstorm.phonemanager.core.domain.adapter.config.AppConfig
import com.glassstorm.phonemanager.core.model.HotspotMode
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for the app-private runtime configuration store.
 *
 * The store now persists only the hotspot mode: the hub runs a single on-device
 * speech engine, so there is no STT-engine, API-key or region state. The DEFAULT
 * is the whole point: a fresh install lets the user own the access point.
 */
@RunWith(RobolectricTestRunner::class)
class RuntimeConfigStoreTest {
    private lateinit var context: Context
    private lateinit var store: RuntimeConfigStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Each test starts from empty prefs, independent of the others.
        context
            .getSharedPreferences(RuntimeConfigStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        store = RuntimeConfigStore(context)
    }

    @Test
    fun `a fresh install defaults to the manual hotspot mode`() {
        assertThat(store.hotspotMode()).isEqualTo(HotspotMode.MANUAL)
    }

    @Test
    fun `the hotspot mode round-trips through the domain port`() {
        val port: AppConfig = store

        port.setHotspotMode(HotspotMode.AUTO)
        assertThat(port.hotspotMode()).isEqualTo(HotspotMode.AUTO)

        port.setHotspotMode(HotspotMode.MANUAL)
        assertThat(port.hotspotMode()).isEqualTo(HotspotMode.MANUAL)
    }

    @Test
    fun `an unknown persisted hotspot mode falls back to manual`() {
        context
            .getSharedPreferences(RuntimeConfigStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(RuntimeConfigStore.KEY_HOTSPOT_MODE, "not-a-mode")
            .commit()

        assertThat(RuntimeConfigStore(context).hotspotMode()).isEqualTo(HotspotMode.MANUAL)
    }

    @Test
    fun `the hotspot mode persists inside app-private storage`() {
        store.setHotspotMode(HotspotMode.AUTO)

        // A brand-new store object reads the same app-private file (the composition
        // root creates one per process, not one per call).
        val reopened = RuntimeConfigStore(context)

        assertThat(reopened.hotspotMode()).isEqualTo(HotspotMode.AUTO)
    }
}
