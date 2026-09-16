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

    private lateinit var GoContext: Context
    private lateinit var GoStore: RuntimeConfigStore

    @Before
    fun setUp() {
        GoContext = ApplicationProvider.getApplicationContext()
        // Each test starts from empty prefs, independent of the others.
        GoContext.getSharedPreferences(RuntimeConfigStore.GO_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        GoStore = RuntimeConfigStore(GoContext)
    }

    @Test
    fun `a fresh install defaults to the offline mock engine`() {
        assertThat(GoStore.GoSttAdapterKind()).isEqualTo(SttAdapterKind.MOCK)
    }

    @Test
    fun `the adapter choice round-trips`() {
        GoStore.GoSetSttAdapterKind(SttAdapterKind.SPEECHMATICS)
        assertThat(GoStore.GoSttAdapterKind()).isEqualTo(SttAdapterKind.SPEECHMATICS)

        GoStore.GoSetSttAdapterKind(SttAdapterKind.MOCK)
        assertThat(GoStore.GoSttAdapterKind()).isEqualTo(SttAdapterKind.MOCK)
    }

    @Test
    fun `an unknown persisted adapter value falls back to mock`() {
        GoContext.getSharedPreferences(RuntimeConfigStore.GO_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(RuntimeConfigStore.GO_KEY_STT_ADAPTER, "not-an-engine")
            .commit()

        assertThat(RuntimeConfigStore(GoContext).GoSttAdapterKind()).isEqualTo(SttAdapterKind.MOCK)
    }

    @Test
    fun `a fresh install has no api key`() {
        assertThat(GoStore.GoApiKey()).isEmpty()
        assertThat(GoStore.GoHasApiKey()).isFalse()
    }

    @Test
    fun `the api key round-trips trimmed`() {
        GoStore.GoSetApiKey("  abc-123  ")

        assertThat(GoStore.GoApiKey()).isEqualTo("abc-123")
        assertThat(GoStore.GoHasApiKey()).isTrue()
    }

    @Test
    fun `setting a blank api key clears it`() {
        GoStore.GoSetApiKey("abc-123")

        GoStore.GoSetApiKey("   ")

        assertThat(GoStore.GoApiKey()).isEmpty()
        assertThat(GoStore.GoHasApiKey()).isFalse()
    }

    @Test
    fun `the placeholder value does not count as an api key`() {
        GoStore.GoSetApiKey(RuntimeConfigStore.GO_PLACEHOLDER_API_KEY)

        assertThat(GoStore.GoHasApiKey()).isFalse()
    }

    @Test
    fun `a fresh install defaults to the us region`() {
        assertThat(GoStore.GoRegion()).isEqualTo("us")
    }

    @Test
    fun `the region round-trips lowercased`() {
        GoStore.GoSetRegion("EU")

        assertThat(GoStore.GoRegion()).isEqualTo("eu")
    }

    @Test
    fun `an unsupported region is ignored`() {
        GoStore.GoSetRegion("eu")
        GoStore.GoSetRegion("mars")

        assertThat(GoStore.GoRegion()).isEqualTo("eu")
    }

    @Test
    fun `values persist inside app-private storage`() {
        GoStore.GoSetSttAdapterKind(SttAdapterKind.SPEECHMATICS)
        GoStore.GoSetApiKey("abc-123")
        GoStore.GoSetRegion("au")

        // A brand-new store object reads the same app-private file (the composition
        // root creates one per process, not one per call).
        val GoReopened = RuntimeConfigStore(GoContext)

        assertThat(GoReopened.GoSttAdapterKind()).isEqualTo(SttAdapterKind.SPEECHMATICS)
        assertThat(GoReopened.GoApiKey()).isEqualTo("abc-123")
        assertThat(GoReopened.GoRegion()).isEqualTo("au")
    }
}
