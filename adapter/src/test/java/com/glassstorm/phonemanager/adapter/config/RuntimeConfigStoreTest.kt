package com.glassstorm.phonemanager.adapter.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for the app-private runtime configuration store.
 *
 * The DEFAULT is the whole point: a fresh install with no settings must select the
 * offline mock engine, because the cloud engine cannot work without a key.
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
    fun `a fresh install defaults to the offline mock engine`() {
        assertThat(store.sttAdapterKind()).isEqualTo(SttAdapterKind.MOCK)
    }

    @Test
    fun `the adapter choice round-trips`() {
        store.setSttAdapterKind(SttAdapterKind.SPEECHMATICS)
        assertThat(store.sttAdapterKind()).isEqualTo(SttAdapterKind.SPEECHMATICS)

        store.setSttAdapterKind(SttAdapterKind.MOCK)
        assertThat(store.sttAdapterKind()).isEqualTo(SttAdapterKind.MOCK)
    }

    @Test
    fun `an unknown persisted adapter value falls back to mock`() {
        context
            .getSharedPreferences(RuntimeConfigStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(RuntimeConfigStore.KEY_STT_ADAPTER, "not-an-engine")
            .commit()

        assertThat(RuntimeConfigStore(context).sttAdapterKind()).isEqualTo(SttAdapterKind.MOCK)
    }

    @Test
    fun `a fresh install has no api key`() {
        assertThat(store.apiKey()).isEmpty()
        assertThat(store.hasApiKey()).isFalse()
    }

    @Test
    fun `the api key round-trips trimmed`() {
        store.setApiKey("  abc-123  ")

        assertThat(store.apiKey()).isEqualTo("abc-123")
        assertThat(store.hasApiKey()).isTrue()
    }

    @Test
    fun `setting a blank api key clears it`() {
        store.setApiKey("abc-123")

        store.setApiKey("   ")

        assertThat(store.apiKey()).isEmpty()
        assertThat(store.hasApiKey()).isFalse()
    }

    @Test
    fun `the placeholder value does not count as an api key`() {
        store.setApiKey(RuntimeConfigStore.PLACEHOLDER_API_KEY)

        assertThat(store.hasApiKey()).isFalse()
    }

    @Test
    fun `a fresh install defaults to the us region`() {
        assertThat(store.region()).isEqualTo("us")
    }

    @Test
    fun `the region round-trips lowercased`() {
        store.setRegion("EU")

        assertThat(store.region()).isEqualTo("eu")
    }

    @Test
    fun `an unsupported region is ignored`() {
        store.setRegion("eu")
        store.setRegion("mars")

        assertThat(store.region()).isEqualTo("eu")
    }

    @Test
    fun `values persist inside app-private storage`() {
        store.setSttAdapterKind(SttAdapterKind.SPEECHMATICS)
        store.setApiKey("abc-123")
        store.setRegion("au")

        // A brand-new store object reads the same app-private file (the composition
        // root creates one per process, not one per call).
        val reopened = RuntimeConfigStore(context)

        assertThat(reopened.sttAdapterKind()).isEqualTo(SttAdapterKind.SPEECHMATICS)
        assertThat(reopened.apiKey()).isEqualTo("abc-123")
        assertThat(reopened.region()).isEqualTo("au")
    }

    @Test
    fun `the domain port maps the stored adapter kind to the domain engine`() {
        // Given the adapter-local spelling is what is persisted
        store.setSttAdapterKind(SttAdapterKind.SPEECHMATICS)

        // When the domain port is read
        val port: com.glassstorm.phonemanager.domain.adapter.config.AppConfig = store

        // Then the domain vocabulary is returned, not the adapter enum
        assertThat(port.sttEngine())
            .isEqualTo(com.glassstorm.phonemanager.core.model.SttEngine.SPEECHMATICS)
    }

    @Test
    fun `a fresh install defaults the domain port to mock and manual hotspot`() {
        val port: com.glassstorm.phonemanager.domain.adapter.config.AppConfig = store

        assertThat(port.sttEngine())
            .isEqualTo(com.glassstorm.phonemanager.core.model.SttEngine.MOCK)
        assertThat(port.hotspotMode())
            .isEqualTo(com.glassstorm.phonemanager.core.model.HotspotMode.MANUAL)
    }

    @Test
    fun `the hotspot mode round-trips through the domain port`() {
        val port: com.glassstorm.phonemanager.domain.adapter.config.AppConfig = store

        port.setHotspotMode(com.glassstorm.phonemanager.core.model.HotspotMode.AUTO)
        assertThat(port.hotspotMode())
            .isEqualTo(com.glassstorm.phonemanager.core.model.HotspotMode.AUTO)

        port.setHotspotMode(com.glassstorm.phonemanager.core.model.HotspotMode.MANUAL)
        assertThat(port.hotspotMode())
            .isEqualTo(com.glassstorm.phonemanager.core.model.HotspotMode.MANUAL)
    }

    @Test
    fun `an unknown persisted hotspot mode falls back to manual`() {
        context
            .getSharedPreferences(RuntimeConfigStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(RuntimeConfigStore.KEY_HOTSPOT_MODE, "not-a-mode")
            .commit()

        assertThat(RuntimeConfigStore(context).hotspotMode())
            .isEqualTo(com.glassstorm.phonemanager.core.model.HotspotMode.MANUAL)
    }

    @Test
    fun `the api key is never returned as part of the domain port's string form`() {
        // Given a stored key
        store.setApiKey("secret-key-value")

        // When the store is stringified (the shape a log line would take)
        val text = store.toString()

        // Then the key does not appear: persistence is secret-safe by construction
        assertThat(text).doesNotContain("secret-key-value")
    }
}
