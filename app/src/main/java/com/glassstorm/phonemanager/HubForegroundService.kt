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
 * in [HubBringUp], and every collaborator is resolved from the process-wide Dagger
 * graph the [PhoneManagerApplication] built — the same graph the UI uses, so the
 * foreground service and the screens observe the SAME listener and pairing state.
 * This class names no concrete adapter.
 */
class HubForegroundService : Service() {
    private var bringUp: HubBringUp? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // The channel MUST exist before startForeground on API 26+, or the OS
        // rejects the notification and the service crashes.
        ensureChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startForeground(NOTIFICATION_ID, notification())
        if (bringUp == null) {
            bringUp = newBringUp().also { it.bringUp(requestedPort(intent)) }
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

    /** Build the ordered bring-up from the process-wide graph. */
    private fun newBringUp(): HubBringUp {
        val component = (application as PhoneManagerApplication).component
        return HubBringUp(
            hub = component.hubServer(),
            hotspot = component.hotspotController(),
            discovery = component.discovery(),
            permissionBlocker = {
                HubPermissions.blockingHotspotPermission(applicationContext)
            },
        )
    }

    private fun requestedPort(intent: Intent?): Int = intent?.getIntExtra(EXTRA_PORT, DEFAULT_HUB_PORT) ?: DEFAULT_HUB_PORT

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun notification(): Notification =
        Notification
            .Builder(this, CHANNEL_ID)
            .setContentTitle(NOTIFICATION_TITLE)
            .setContentText(NOTIFICATION_TEXT)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()

    companion object {
        /**
         * The hub's default listening port. Reachable by hotspot peers via the
         * gateway address, and the port the discovery adapter advertises.
         */
        const val DEFAULT_HUB_PORT: Int = 9000

        /** Notification channel the hub's ongoing notification lives on. */
        const val CHANNEL_ID: String = "hub-foreground"

        /** Human-readable channel name shown in system settings. */
        const val CHANNEL_NAME: String = "Ecosystem Hub"

        const val NOTIFICATION_ID: Int = 1

        const val NOTIFICATION_TITLE: String = "Ecosystem Hub running"

        const val NOTIFICATION_TEXT: String = "Waiting for paired devices"

        /**
         * Optional hub port override. Absent in normal use (the default port is
         * used); tests pass `0` for an ephemeral bind, and a future settings
         * screen can make the port user-visible.
         */
        const val EXTRA_PORT: String = "com.glassstorm.phonemanager.extra.HUB_PORT"

        /** Start the hub from anywhere in the app. */
        fun startService(context: Context) {
            context.startForegroundService(Intent(context, HubForegroundService::class.java))
        }
    }
}
