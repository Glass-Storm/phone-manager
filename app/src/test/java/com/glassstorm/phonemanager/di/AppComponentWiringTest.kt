package com.glassstorm.phonemanager.di

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.glassstorm.phonemanager.adapter.android.config.RuntimeConfigStore
import com.glassstorm.phonemanager.adapter.android.repository.sqlite.SqliteDeviceRepository
import com.glassstorm.phonemanager.core.domain.adapter.config.AppConfig
import com.glassstorm.phonemanager.core.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.core.model.HotspotMode
import com.glassstorm.phonemanager.core.model.SttEngine
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The platform wiring, proven at the component level.
 *
 * Two facts are asserted independently: the persistent SQLite repository is what
 * the graph binds for [DeviceRepository], and the app-private [RuntimeConfigStore]
 * is the [AppConfig] the Settings screen reads and writes — so `:app/ui` can read
 * and write settings without ever importing `:adapter`.
 *
 * The registry-era cases that asserted "no Android Context keeps the in-memory
 * fallback" and "the config port is absent without an Android Context" are GONE
 * on purpose: the component factory always binds a real `Application`, so both
 * described states compile-time DI makes impossible.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AppComponentWiringTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun component(): AppComponent = DaggerAppComponent.factory().create(app)

    @Test
    fun `the device repository is bound to the sqlite adapter`() {
        // Given the production component with a real Android Application
        val component = component()

        // When the repository port is resolved
        val repo: DeviceRepository = component.deviceRepository()

        // Then the persistent adapter is the one bound
        assertThat(repo).isInstanceOf(SqliteDeviceRepository::class.java)
    }

    @Test
    fun `the app config port round-trips an engine choice through the adapter store`() {
        // Given the production component
        val component = component()

        // When the config port is resolved and a non-default engine is stored
        val config: AppConfig = component.appConfig()
        config.setSttEngine(SttEngine.SPEECHMATICS)
        config.setRegion("eu")
        config.setHotspotMode(HotspotMode.AUTO)

        // Then it is the adapter-backed store and the values read back through the port
        assertThat(config).isInstanceOf(RuntimeConfigStore::class.java)
        assertThat(config.sttEngine()).isEqualTo(SttEngine.SPEECHMATICS)
        assertThat(config.region()).isEqualTo("eu")
        assertThat(config.hotspotMode()).isEqualTo(HotspotMode.AUTO)
    }
}
