package com.glassstorm.phonemanager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * Owns the hub's notification channel and its ongoing foreground notification.
 *
 * Split out of [HubForegroundService] so the service keeps only the Android
 * lifecycle: channel creation and notification construction live here.
 */
object HubNotifier {
    const val CHANNEL_ID: String = "hub-foreground"

    const val CHANNEL_NAME: String = "Ecosystem Hub"

    const val NOTIFICATION_ID: Int = 1

    const val NOTIFICATION_TITLE: String = "Ecosystem Hub running"

    const val NOTIFICATION_TEXT: String = "Waiting for paired devices"

    private const val IMPORTANCE: Int = NotificationManager.IMPORTANCE_LOW

    private const val SMALL_ICON: Int = android.R.drawable.stat_sys_upload

    /** Create [CHANNEL_ID] when absent. Idempotent for a repeating call. */
    fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, IMPORTANCE),
        )
    }

    /** The ongoing foreground notification on [CHANNEL_ID]. */
    fun foregroundNotification(context: Context): Notification =
        Notification
            .Builder(context, CHANNEL_ID)
            .setContentTitle(NOTIFICATION_TITLE)
            .setContentText(NOTIFICATION_TEXT)
            .setSmallIcon(SMALL_ICON)
            .setOngoing(true)
            .build()
}
