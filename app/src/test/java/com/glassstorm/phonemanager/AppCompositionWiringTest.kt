package com.glassstorm.phonemanager

import androidx.test.core.app.ApplicationProvider
import com.glassstorm.phonemanager.adapter.config.RuntimeConfigStore
import com.glassstorm.phonemanager.adapter.repository.memory.MemoryDeviceRepository
import com.glassstorm.phonemanager.adapter.repository.sqlite.SqliteDeviceRepository
import com.glassstorm.phonemanager.domain.adapter.config.AppConfig
import com.glassstorm.phonemanager.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.FromContext
import com.glassstorm.phonemanager.domain.dto.HotspotMode
import com.glassstorm.phonemanager.domain.dto.SttEngine
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.content.Context as GoAndroidContext

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
    private val GoAndroid: GoAndroidContext = ApplicationProvider.getApplicationContext()

    @Test
    fun `an android context selects the sqlite device repository`() {
        // Given a registry built with a real Android Context
        val GoCtx: Context = AppComposition.GoBuildContext(GoAndroid)

        // When the repository port is resolved
        val GoRepo = FromContext<DeviceRepository>(GoCtx)

        // Then the persistent adapter is the one bound, not the in-memory fake
        assertThat(GoRepo).isInstanceOf(SqliteDeviceRepository::class.java)
    }

    @Test
    fun `no android context keeps the in-memory repository fallback`() {
        // Given a registry built without an Android Context (the pure-JVM slice)
        val GoCtx: Context = AppComposition.GoBuildContext(null)

        // Then the in-memory fallback is bound, so the JVM-only tests keep working
        assertThat(FromContext<DeviceRepository>(GoCtx))
            .isInstanceOf(MemoryDeviceRepository::class.java)
    }

    @Test
    fun `the app config port is registered and round-trips an engine choice`() {
        // Given a registry built with a real Android Context
        val GoCtx = AppComposition.GoBuildContext(GoAndroid)

        // When the config port is resolved and a non-default engine is stored
        val GoConfig = FromContext<AppConfig>(GoCtx)
        GoConfig.GoSetSttEngine(SttEngine.SPEECHMATICS)
        GoConfig.GoSetRegion("eu")
        GoConfig.GoSetHotspotMode(HotspotMode.AUTO)

        // Then it is the adapter-backed store and the values read back through the port
        assertThat(GoConfig).isInstanceOf(RuntimeConfigStore::class.java)
        assertThat(GoConfig.GoSttEngine()).isEqualTo(SttEngine.SPEECHMATICS)
        assertThat(GoConfig.GoRegion()).isEqualTo("eu")
        assertThat(GoConfig.GoHotspotMode()).isEqualTo(HotspotMode.AUTO)
    }

    @Test
    fun `the config port is absent from a context built without an android context`() {
        // Given the pure-JVM registry, which has no SharedPreferences to persist into
        val GoCtx = AppComposition.GoBuildContext(null)

        // Then the config port degrades to absent rather than crashing construction
        assertThat(
            com.glassstorm.phonemanager.domain.context
                .FromContextOrNull<AppConfig>(GoCtx),
        ).isNull()
    }
}
