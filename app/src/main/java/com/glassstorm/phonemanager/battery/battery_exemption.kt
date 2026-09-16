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
    fun GoIsExempt(): Boolean

    /** Ask the user to grant the exemption. A no-op when there is nothing to launch. */
    fun GoRequestExemption()
}

/** The Android-backed implementation, delegating to [BatteryOptimization]. */
class AndroidBatteryExemption(private val GoContext: Context) : BatteryExemption {

    override fun GoIsExempt(): Boolean = BatteryOptimization.GoIsExempt(GoContext)

    override fun GoRequestExemption() {
        // Started from the application Context (the composition root holds no
        // Activity), so the request needs its own task.
        GoContext.startActivity(
            BatteryOptimization.GoRequestIntent(GoContext).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
