package com.glassstorm.phonemanager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.glassstorm.phonemanager.permission.HubPermissions

/**
 * Hosts the whole hub (access point, gRPC listener, discovery) behind a
 * foreground notification.
 *
 * A started (not bound) service so the hub outlives every activity: the phone is
 * the ecosystem's hub, and the listener must survive the user leaving the UI. It
 * is the ONE owner of bring-up — no Activity starts the hotspot or the discovery.
 *
 * Thin on purpose: the Android lifecycle lives here, the ordered bring-up lives
 * in [HubBringUp], and the listener itself is resolved from the composition root
 * through the [com.glassstorm.phonemanager.domain.adapter.transport.HubServer]
 * port. This class names no concrete adapter.
 */
class HubForegroundService : Service() {
    private var bringUp: HubBringUp? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // The network adapters need a real WifiManager/NsdManager, so the Android
        // Context is recorded before anything resolves the registry.
        AppComposition.initAndroid(this)
        // The channel MUST exist before startForeground on API 26+, or the OS
        // rejects the notification and the service crashes.
        ensureChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startForeground(GO_NOTIFICATION_ID, notification())
        if (bringUp == null) {
            bringUp =
                HubBringUp(
                    ctx = AppComposition.appContext(),
                    permissionBlocker = {
                        HubPermissions.blockingHotspotPermission(applicationContext)
                    },
                ).also { it.bringUp(requestedPort(intent)) }
        }
        // Restart the hub after the OS reclaims the process: the hub is the whole
        // point of this service, so a stolen process must come back.
        return START_STICKY
    }

    override fun onDestroy() {
        bringUp?.tearDown()
        bringUp = null
        super.onDestroy()
    }

    private fun requestedPort(intent: Intent?): Int =
        intent?.getIntExtra(GO_EXTRA_PORT, AppComposition.GO_DEFAULT_HUB_PORT)
            ?: AppComposition.GO_DEFAULT_HUB_PORT

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(GO_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                GO_CHANNEL_ID,
                GO_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun notification(): Notification =
        Notification
            .Builder(this, GO_CHANNEL_ID)
            .setContentTitle(GO_NOTIFICATION_TITLE)
            .setContentText(GO_NOTIFICATION_TEXT)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()

    companion object {
        /** Notification channel the hub's ongoing notification lives on. */
        const val GO_CHANNEL_ID: String = "hub-foreground"

        /** Human-readable channel name shown in system settings. */
        const val GO_CHANNEL_NAME: String = "Ecosystem Hub"

        const val GO_NOTIFICATION_ID: Int = 1

        const val GO_NOTIFICATION_TITLE: String = "Ecosystem Hub running"

        const val GO_NOTIFICATION_TEXT: String = "Waiting for paired devices"

        /**
         * Optional hub port override. Absent in normal use (the default port is
         * used); tests pass `0` for an ephemeral bind, and a future settings
         * screen can make the port user-visible.
         */
        const val GO_EXTRA_PORT: String = "com.glassstorm.phonemanager.extra.HUB_PORT"

        /** Start the hub from anywhere in the app. */
        fun startService(context: Context) {
            context.startForegroundService(Intent(context, HubForegroundService::class.java))
        }
    }
}
