package com.glassstorm.phonemanager.battery

import android.content.Context
import android.content.Intent

/**
 * The Settings screen's seam onto the Doze-exemption helper.
 *
 * The ViewModel must not touch Android APIs, so it depends on this interface and
 * `:app`'s composition root supplies [AndroidBatteryExemption]. Tests bind a fake,
 * which is what makes the button's wiring assertable without a device.
 */
interface BatteryExemption {
    /** Whether the platform currently exempts this app from battery optimization. */
    fun isExempt(): Boolean

    /** Ask the user to grant the exemption. A no-op when there is nothing to launch. */
    fun requestExemption()
}

/** The Android-backed implementation, delegating to [BatteryOptimization]. */
class AndroidBatteryExemption(
    private val context: Context,
) : BatteryExemption {
    override fun isExempt(): Boolean = BatteryOptimization.isExempt(context)

    override fun requestExemption() {
        // Started from the application Context (the composition root holds no
        // Activity), so the request needs its own task.
        context.startActivity(
            BatteryOptimization.requestIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
