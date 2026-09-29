package com.glassstorm.phonemanager.di

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.glassstorm.phonemanager.PhoneManagerApplication
import com.glassstorm.phonemanager.core.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.core.domain.security.TokenVerifier
import com.glassstorm.phonemanager.core.domain.service.DeviceService
import com.glassstorm.phonemanager.core.domain.service.PairingService
import com.glassstorm.phonemanager.transport.grpc.HubServerAdapter
import com.glassstorm.phonemanager.ui.DashboardViewModel
import com.glassstorm.phonemanager.ui.DevicesViewModel
import com.glassstorm.phonemanager.ui.PairingViewModel
import com.glassstorm.phonemanager.ui.SettingsViewModel
import com.glassstorm.phonemanager.ui.StreamViewModel
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves the Dagger graph compiles and resolves the whole hub, AND that the
 * pairing/token-verifier identity invariant survives the registry -> Dagger move.
 *
 * The two resolutions below are the heart of this suite: the gRPC Pairing service
 * mints tokens through [PairingService] while the `AuthInterceptor` verifies them
 * through [TokenVerifier]. If the graph built two instances they could disagree;
 * `ServiceModule` binds both to one `@Singleton` `PairingServiceImpl`, and this
 * test fails the moment that binding is split.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AppComponentTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun component(): AppComponent = DaggerAppComponent.factory().create(app)

    @Test
    fun `the graph resolves every port the hub needs`() {
        // Given the production Dagger component
        val component = component()

        // When each port is resolved by type
        // Then none throws — a missing binding would fail the BUILD, not this call
        assertThat(component.deviceService()).isNotNull()
        assertThat(component.pairingService()).isNotNull()
        assertThat(component.streamService()).isNotNull()
        assertThat(component.hubServer()).isNotNull()
        assertThat(component.appConfig()).isNotNull()
    }

    @Test
    fun `the pairing service and the token verifier are the same instance`() {
        // Given the production Dagger component
        val component = component()

        // When the service surface and the interceptor's verifier are resolved
        val pairing: PairingService = component.pairingService()
        val verifier: TokenVerifier = component.tokenVerifier()

        // Then they are identical, so the interceptor can never verify against a
        // different pairing state than the one Pair mints tokens into.
        assertThat(verifier).isSameInstanceAs(pairing)
    }

    @Test
    fun `singleton ports resolve to one instance across resolutions`() {
        // Given the production Dagger component
        val component = component()

        // When a singleton port is resolved twice
        val first = component.streamService()
        val second = component.streamService()

        // Then it is one instance, so a relay session's state is shared process-wide
        assertThat(second).isSameInstanceAs(first)
    }

    @Test
    fun `the resolved hub server is the production all-interfaces bind`() {
        // Given the production Dagger component
        val component = component()

        // When the hub port is resolved
        val hub: HubServer = component.hubServer()

        // Then it is stopped (never started by construction) and its adapter binds
        // the wildcard, because the phone IS the hotspot and LAN peers dial in
        assertThat(hub.isRunning()).isFalse()
        assertThat(hub.boundPort()).isEqualTo(0)
        assertThat((hub as HubServerAdapter).bindAddress()).isEqualTo(
            HubServerAdapter.ALL_INTERFACES_ADDRESS,
        )
    }

    @Test
    fun `the view model factory resolves every bound screen view model`() {
        // Given the production Dagger component
        val component = component()
        val factory = component.viewModelFactory()

        // When each screen ViewModel is requested by its runtime class
        // Then the ViewModelKey map resolved it — a missing @IntoMap binding would
        // throw here, so this is the guard for the multibinding
        assertThat(factory.create(DashboardViewModel::class.java)).isInstanceOf(DashboardViewModel::class.java)
        assertThat(factory.create(DevicesViewModel::class.java)).isInstanceOf(DevicesViewModel::class.java)
        assertThat(factory.create(PairingViewModel::class.java)).isInstanceOf(PairingViewModel::class.java)
        assertThat(factory.create(SettingsViewModel::class.java)).isInstanceOf(SettingsViewModel::class.java)
        assertThat(factory.create(StreamViewModel::class.java)).isInstanceOf(StreamViewModel::class.java)
    }

    @Test
    fun `the application binds the graph on create`() {
        // Given the application under test
        val application = ApplicationProvider.getApplicationContext<Application>()

        // Then the manifest-declared application IS PhoneManagerApplication, and it
        // built the component, so the whole process shares one graph
        assertThat(application).isInstanceOf(PhoneManagerApplication::class.java)
        val component = (application as PhoneManagerApplication).component
        assertThat(component.deviceService()).isInstanceOf(DeviceService::class.java)
    }
}
