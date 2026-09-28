package com.glassstorm.phonemanager.hub

import android.content.Context
import com.glassstorm.phonemanager.HubForegroundService

/**
 * The Dashboard's seam onto the hub's bring-up.
 *
 * The ViewModel must not touch Android APIs, so it depends on this interface and
 * `:app`'s composition root supplies [AndroidHubStarter]. Tests bind a fake, which
 * is what makes the "Start hub" wiring assertable without a device — and, more
 * importantly, what keeps the listener's lifetime owned by the foreground service
 * instead of the UI process: starting the listener here hands the job to
 * [HubForegroundService], which outlives every activity.
 */
interface HubStarter {
    /** Ask the foreground service to bring the hub up. */
    fun start()
}

/**
 * The Android-backed implementation, delegating to
 * [HubForegroundService.startService].
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
}
