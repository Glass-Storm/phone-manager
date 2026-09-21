package com.glassstorm.phonemanager

import com.glassstorm.phonemanager.adapter.config.RuntimeConfigStore
import com.glassstorm.phonemanager.adapter.network.nsd.NsdDiscoveryAdapter
import com.glassstorm.phonemanager.adapter.network.wifi.LocalOnlyHotspotAdapter
import com.glassstorm.phonemanager.adapter.relay.DiscardingFrameSink
import com.glassstorm.phonemanager.adapter.repository.memory.MemoryDeviceRepository
import com.glassstorm.phonemanager.adapter.repository.sqlite.SqliteDeviceRepository
import com.glassstorm.phonemanager.adapter.speech.SttFactory
import com.glassstorm.phonemanager.adapter.speech.mock.MockSttAdapter
import com.glassstorm.phonemanager.adapter.transport.grpc.HubServerAdapter
import com.glassstorm.phonemanager.battery.AndroidBatteryExemption
import com.glassstorm.phonemanager.battery.BatteryExemption
import com.glassstorm.phonemanager.core.domain.adapter.config.AppConfig
import com.glassstorm.phonemanager.core.domain.adapter.network.Discovery
import com.glassstorm.phonemanager.core.domain.adapter.network.HotspotController
import com.glassstorm.phonemanager.core.domain.adapter.relay.FrameSink
import com.glassstorm.phonemanager.core.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.core.domain.adapter.speech.SttPort
import com.glassstorm.phonemanager.core.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.core.domain.context.Context
import com.glassstorm.phonemanager.core.domain.context.fromContextOrNull
import com.glassstorm.phonemanager.core.domain.context.register
import com.glassstorm.phonemanager.core.domain.security.TokenVerifier
import com.glassstorm.phonemanager.core.domain.service.DeviceService
import com.glassstorm.phonemanager.core.domain.service.PairingService
import com.glassstorm.phonemanager.core.domain.service.StreamService
import com.glassstorm.phonemanager.core.service.DeviceServiceImpl
import com.glassstorm.phonemanager.core.service.PairingServiceImpl
import com.glassstorm.phonemanager.core.service.StreamServiceImpl
import android.content.Context as AndroidContext

/**
 * Composition root. The ONLY place that knows every layer at once: it binds the
 * domain ports to concrete adapter/service implementations inside a [Context].
 *
 * This vertical slice is the template later todos follow:
 *   domain port  ->  `:adapter` implementation  ->  `:service` implementation,
 * all resolved elsewhere via `fromContext<Port>(ctx)`.
 *
 * The hub's two gRPC services and its [TokenVerifier] resolve their collaborators
 * EAGERLY when a server is built, so every port they need must be registered here
 * before [HubServer.start] is ever called.
 */
object AppComposition {
    /**
     * The hub's default listening port. Reachable by hotspot peers via the
     * gateway address, and the port the discovery adapter advertises.
     */
    const val DEFAULT_HUB_PORT: Int = 9000

    @Volatile
    private var androidAppContext: AndroidContext? = null

    @Volatile
    private var singleton: Context? = null

    /**
     * Record the Android application context BEFORE the registry is first built.
     *
     * The persistent repository and the config store need an Android Context, and
     * the network adapters need a real `WifiManager`/`NsdManager`; only an Android
     * Context can supply them. The hub's own [HubForegroundService] calls this in
     * `onCreate` — before anything resolves the registry.
     */
    fun initAndroid(goAndroidContext: AndroidContext) {
        androidAppContext = goAndroidContext.applicationContext
        // A registry built before the Android Context arrived (e.g. the UI resolved
        // first) would hold the in-memory repository and lack the platform-backed
        // ports. Re-register them into the live singleton so the hub never comes up
        // half-wired (and so settings persist instead of silently resetting).
        singleton?.let { registerAndroidBacked(it, androidAppContext) }
    }

    /**
     * The process-wide registry, created once.
     *
     * A foreground service and the UI must resolve the SAME hub instance, or they
     * would observe different listeners and pairing state, so the app resolves
     * through here rather than rebuilding a Context per caller.
     */
    fun appContext(): Context =
        singleton ?: synchronized(this) {
            singleton ?: buildContext(androidAppContext).also { singleton = it }
        }

    /**
     * Build a registry.
     *
     * [goAndroidContext] is optional: the pure-JVM/UI slice and the unit tests
     * build a registry without it. With no Android Context the repository falls
     * back to the in-memory implementation and the config port is simply absent;
     * with one, the SQLite repository and the app-private config store are bound.
     */
    fun buildContext(goAndroidContext: AndroidContext? = null): Context {
        val ctx = Context()

        // Adapter layer: bind a concrete repository under the DOMAIN port type.
        // No Android Context means no persistent store, so the in-memory fake is the
        // fallback (and what the pure-JVM AppCompositionTest resolves).
        if (goAndroidContext == null) {
            register<DeviceRepository>(ctx, MemoryDeviceRepository())
            // The engine is selected by the config store, which needs an Android
            // Context. Without one the safe v1 default applies: the offline,
            // deterministic MockSttAdapter — the SAME instance SttFactory returns
            // for the unconfigured default, never a test-only stand-in.
            register<SttPort>(ctx, MockSttAdapter())
        }

        registerAndroidBacked(ctx, goAndroidContext)

        // Service layer: the service resolves its collaborator from the Context.
        register<DeviceService>(ctx, DeviceServiceImpl(ctx))

        // Pairing: ONE instance satisfies both the service surface the gRPC
        // PairingService uses and the token check the AuthInterceptor uses, so the
        // interceptor and the service can never disagree about pairing state.
        val pairing = PairingServiceImpl(ctx)
        register<PairingService>(ctx, pairing)
        register<TokenVerifier>(ctx, pairing)

        register<StreamService>(ctx, StreamServiceImpl(ctx))

        // The relay's video half. The FrameSink port and the SttPort are resolved
        // EAGERLY the first time a frame is dispatched, so both must be present
        // before the hub can accept media — without them the real app hub would
        // throw MissingFromContextException on the first inbound NAL. v1 does not
        // store or display media, so the sink accepts and drops it.
        register<FrameSink>(ctx, DiscardingFrameSink())

        // The hub listener itself. Production binds all interfaces because the
        // phone IS the hotspot and its LAN peers must dial in; tests bind loopback.
        register<HubServer>(ctx, HubServerAdapter.forLanPeers(ctx))

        return ctx
    }

    /**
     * Register the Android-backed adapters when an Android Context exists.
     *
     * Registered together and guarded by the [AppConfig] binding so a repeated
     * [initAndroid] (the service can be re-created) is a no-op rather than a
     * second repository over the same database file. The discovery gateway
     * fallback is left unconfigured: it is a settings-owned value, and the hub's
     * own use of the port is advertising, which needs no fallback.
     */
    private fun registerAndroidBacked(
        ctx: Context,
        goAndroidContext: AndroidContext?,
    ) {
        if (goAndroidContext == null) return
        if (fromContextOrNull<AppConfig>(ctx) != null) return

        register<DeviceRepository>(ctx, SqliteDeviceRepository(goAndroidContext))
        val config = RuntimeConfigStore(goAndroidContext)
        register<AppConfig>(ctx, config)
        register<SttPort>(ctx, SttFactory(config).createSttPort())
        register<BatteryExemption>(ctx, AndroidBatteryExemption(goAndroidContext))
        register<HotspotController>(ctx, LocalOnlyHotspotAdapter(goAndroidContext))
        register<Discovery>(ctx, NsdDiscoveryAdapter(goAndroidContext, gatewayFallback = null))
    }
}
