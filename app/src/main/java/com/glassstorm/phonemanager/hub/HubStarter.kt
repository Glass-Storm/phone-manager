package com.glassstorm.phonemanager.hub

import android.content.Context
import android.content.Intent
import com.glassstorm.phonemanager.HubForegroundService

/**
 * The Dashboard's seam onto the hub's bring-up and teardown.
 *
 * The ViewModel must not touch Android APIs, so it depends on this interface and
 * `:app`'s composition root supplies [AndroidHubStarter]. Tests bind a fake, which
 * is what makes the "Start hub" wiring assertable without a device — and, more
 * importantly, what keeps the listener's lifetime owned by the foreground service
 * instead of the UI process: starting the listener here hands the job to
 * [HubForegroundService], which outlives every activity.
 *
 * [start] and [stop] are a matched pair. The service is the SINGLE owner of
 * bring-up, so teardown must cross the SAME seam: stopping the listener behind the
 * service's back would leave `HubBringUp` thinking the hub is still up, so a later
 * `start` would be refused (`HubForegroundService` guards bring-up on a null
 * `bringUp`) and discovery would keep advertising a dead port.
 */
interface HubStarter {
    /** Ask the foreground service to bring the hub up. */
    fun start()

    /**
     * Ask the foreground service to tear the hub down.
     *
     * Idempotent and safe when the service is not running.
     */
    fun stop()
}

/**
 * The Android-backed implementation, delegating to [HubForegroundService].
 *
 * Started from the application Context (the composition root holds no Activity),
 * so the service — not the caller — owns the hub's lifetime.
 */
class AndroidHubStarter(
    private val context: Context,
) : HubStarter {
    override fun start() {
        HubForegroundService.startService(context)
    }

    override fun stop() {
        // A plain `stopService` is the correct teardown for a STARTED (not bound)
        // service: it delivers onDestroy, which runs `HubBringUp.tearDown()`.
        context.stopService(Intent(context, HubForegroundService::class.java))
    }
}
