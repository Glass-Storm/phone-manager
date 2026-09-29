package com.glassstorm.phonemanager.adapter.android.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.glassstorm.phonemanager.core.domain.adapter.config.AppConfig
import com.glassstorm.phonemanager.core.model.HotspotMode
import com.glassstorm.phonemanager.core.model.SttEngine
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Modifier

/**
 * Pins the SRP property of [RuntimeConfigStore]: its published public surface is
 * EXACTLY the [AppConfig] port. The adapter-only vocabulary (`SttAdapterKind`,
 * `sttAdapterKind`, `setSttAdapterKind`, `hasApiKey`) is demoted to `internal`, so
 * it must not appear in the clean public method set.
 *
 * Kotlin `internal` members stay JVM-`public` but are name-mangled (`name$module`),
 * so the assertion excludes `$`-bearing names to isolate the real published surface.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AppConfigPortSurfaceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the clean public surface of the store is exactly the AppConfig port`() {
        // Given the port's method names
        val portMethodNames =
            setOf(
                "sttEngine",
                "setSttEngine",
                "apiKey",
                "setApiKey",
                "region",
                "setRegion",
                "hotspotMode",
                "setHotspotMode",
            )

        // When the public JVM methods declared on the store are collected, dropping
        // the name-mangled internal members (their names carry a '$' suffix)
        val publicSurface =
            RuntimeConfigStore::class.java.declaredMethods
                .filter { Modifier.isPublic(it.modifiers) }
                .map { it.name }
                .filterNot { it.contains('$') }
                .toSet()

        // Then the published surface is exactly the port, and the adapter-only
        // vocabulary does not leak as clean public API
        assertThat(publicSurface).containsExactlyElementsIn(portMethodNames)
    }

    @Test
    fun `the store is the AppConfig port and its methods round-trip`() {
        // Given the store used ONLY through the domain port type
        val port: AppConfig = RuntimeConfigStore(context)

        // When the port's state is written and read back
        port.setSttEngine(SttEngine.SPEECHMATICS)
        port.setRegion("eu")

        // Then the port returns the domain vocabulary it was given
        assertThat(port.sttEngine()).isEqualTo(SttEngine.SPEECHMATICS)
        assertThat(port.region()).isEqualTo("eu")
        assertThat(port.hotspotMode()).isEqualTo(HotspotMode.MANUAL)
    }
}
