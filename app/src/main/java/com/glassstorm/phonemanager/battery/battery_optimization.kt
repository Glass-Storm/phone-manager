package com.glassstorm.phonemanager.battery

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Battery-optimization (Doze) exemption helper.
 *
 * ## Why the hub needs this
 *
 * The phone is the ecosystem's hub and holds a long-lived LAN listener. Android's
 * Doze and App Standby modes suspend network access for apps that are not exempt,
 * which quietly stops the hub answering peers even while the foreground service
 * runs. Exempting the app from battery optimization is the supported way to keep a
 * deliberately long-lived listener alive.
 *
 * ## Permission note (deliberate, documented)
 *
 * The platform documents `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` as requiring
 * the caller to hold `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. The T12 permission
 * matrix deliberately does NOT declare it (the plan forbids permissions no code
 * reads, and a direct-request launch is only reachable from the Settings screen
 * T17 owns). The intent is still built exactly as the platform expects, so the
 * exemption can be granted through it today; if a build ever needs the direct
 * request to be platform-guaranteed, declaring that one permission is the entire
 * change. The manifest comment records this so the gap is never a silent surprise.
 *
 * No new permission is introduced here.
 */
object BatteryOptimization {
    /**
     * Whether this app is already exempt from battery optimization.
     *
     * `false` also covers a device that exposes no [PowerManager] at all — an
     * unoptimizable platform is not reported as exempt.
     */
    fun GoIsExempt(goContext: Context): Boolean {
        val GoPower =
            goContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: return false
        return GoPower.isIgnoringBatteryOptimizations(goContext.packageName)
    }

    /**
     * The intent that asks the user to exempt this app.
     *
     * Carries the mandatory `package:<applicationId>` data URI that scopes the
     * request to this app; without it the system activity has no target and the
     * request is meaningless on API 23+.
     */
    fun GoRequestIntent(goContext: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(GoPackageUri(goContext))

    /**
     * The `package:` URI the exemption activity expects for this app.
     *
     * `Uri.fromParts` is used rather than string concatenation so the scheme and
     * the opaque part are built by the platform parser.
     */
    fun GoPackageUri(goContext: Context): Uri = Uri.fromParts("package", goContext.packageName, null)
}
