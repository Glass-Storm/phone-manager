package com.glassstorm.phonemanager

import androidx.test.core.app.ApplicationProvider
import com.glassstorm.phonemanager.adapter.config.RuntimeConfigStore
import com.glassstorm.phonemanager.adapter.repository.memory.MemoryDeviceRepository
import com.glassstorm.phonemanager.adapter.repository.sqlite.SqliteDeviceRepository
import com.glassstorm.phonemanager.core.domain.adapter.config.AppConfig
import com.glassstorm.phonemanager.core.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.core.domain.context.Context
import com.glassstorm.phonemanager.core.domain.context.fromContext
import com.glassstorm.phonemanager.core.model.HotspotMode
import com.glassstorm.phonemanager.core.model.SttEngine
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.content.Context as AndroidContext

/**
 * The T17 wiring change, proven at the composition root.
 *
 * Two gaps are closed here and each is asserted independently:
 *  1. the SQLite repository is used whenever an Android Context exists, with the
 *     in-memory one kept as the no-Context fallback (so the pure-JVM
 *     [AppCompositionTest] still passes);
 *  2. the domain [AppConfig] port is registered, so `:app/ui` can read and write
 *     settings without ever importing `:adapter`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AppCompositionWiringTest {
    private val android: AndroidContext = ApplicationProvider.getApplicationContext()

    @Test
    fun `an android context selects the sqlite device repository`() {
        // Given a registry built with a real Android Context
        val ctx: Context = AppComposition.buildContext(android)

        // When the repository port is resolved
        val repo = fromContext<DeviceRepository>(ctx)

        // Then the persistent adapter is the one bound, not the in-memory fake
        assertThat(repo).isInstanceOf(SqliteDeviceRepository::class.java)
    }

    @Test
    fun `no android context keeps the in-memory repository fallback`() {
        // Given a registry built without an Android Context (the pure-JVM slice)
        val ctx: Context = AppComposition.buildContext(null)

        // Then the in-memory fallback is bound, so the JVM-only tests keep working
        assertThat(fromContext<DeviceRepository>(ctx))
            .isInstanceOf(MemoryDeviceRepository::class.java)
    }

    @Test
    fun `the app config port is registered and round-trips an engine choice`() {
        // Given a registry built with a real Android Context
        val ctx = AppComposition.buildContext(android)

        // When the config port is resolved and a non-default engine is stored
        val config = fromContext<AppConfig>(ctx)
        config.setSttEngine(SttEngine.SPEECHMATICS)
        config.setRegion("eu")
        config.setHotspotMode(HotspotMode.AUTO)

        // Then it is the adapter-backed store and the values read back through the port
        assertThat(config).isInstanceOf(RuntimeConfigStore::class.java)
        assertThat(config.sttEngine()).isEqualTo(SttEngine.SPEECHMATICS)
        assertThat(config.region()).isEqualTo("eu")
        assertThat(config.hotspotMode()).isEqualTo(HotspotMode.AUTO)
    }

    @Test
    fun `the config port is absent from a context built without an android context`() {
        // Given the pure-JVM registry, which has no SharedPreferences to persist into
        val ctx = AppComposition.buildContext(null)

        // Then the config port degrades to absent rather than crashing construction
        assertThat(
            com.glassstorm.phonemanager.core.domain.context
                .fromContextOrNull<AppConfig>(ctx),
        ).isNull()
    }
}
