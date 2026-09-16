package com.glassstorm.phonemanager.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * The runtime permissions the hub cannot start without, branched by API level.
 *
 * ## Why the branch exists
 *
 * The app targets API 29, and the platform's LocalOnlyHotspot requirement changed
 * at API 33:
 *
 *  * **API < 33** — starting a LocalOnlyHotspot requires a granted
 *    `ACCESS_FINE_LOCATION`, and location SERVICES must also be enabled. This is
 *    the ACTIVE path on this project and the one the hub really exercises.
 *  * **API >= 33** — `NEARBY_WIFI_DEVICES` replaces the location requirement for
 *    Wi-Fi APIs. Declared with `usesPermissionFlags="neverForLocation"` so it is
 *    never conflated with location access. This branch matters only when the app
 *    is eventually built with a 33+ target on a 33+ device.
 *
 * `POST_NOTIFICATIONS` is required from API 33 to post the foreground-service
 * notification. Below 33 it is granted at install time, so it is deliberately NOT
 * part of the required set there — demanding it on API 29 would make
 * [GoMissing] report a permission the platform never gates.
 *
 * Pure decision logic: no UI, no side effects. T17's Settings screen surfaces the
 * request flow; this file provides only the matrix and the checker.
 */
object HubPermissions {

    /**
     * The Wi-Fi permission that gates LocalOnlyHotspot/NSD at [goSdkInt].
     *
     * Split out from [GoRequiredFor] because it is the ONLY permission that gates
     * the access point: a missing notification grant must not stop the hotspot,
     * and a missing Wi-Fi grant must not stop the plain-socket listener.
     */
    fun GoHotspotPermission(goSdkInt: Int): String =
        if (goSdkInt >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+: the Wi-Fi-specific permission, declared neverForLocation.
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            // API < 33 (and this project's targetSdk 29): the location permission
            // is what gates LocalOnlyHotspot.
            Manifest.permission.ACCESS_FINE_LOCATION
        }

    /**
     * The permissions [goContext] must already hold for the hub to start.
     *
     * @param goSdkInt the device's API level; injected so the branch is testable.
     */
    fun GoRequiredFor(goSdkInt: Int): List<String> =
        buildList {
            add(GoHotspotPermission(goSdkInt))
            if (goSdkInt >= Build.VERSION_CODES.TIRAMISU) {
                // Only from API 33 is the notification a runtime grant; below it the
                // platform grants POST_NOTIFICATIONS at install time.
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

    /**
     * The hotspot gate at this device's API level, or `null` when already granted.
     *
     * Returned as a name rather than a boolean so the caller can report exactly
     * which grant is blocking the access point.
     */
    fun GoBlockingHotspotPermission(goContext: Context): String? {
        val GoPermission = GoHotspotPermission(Build.VERSION.SDK_INT)
        return if (goContext.checkSelfPermission(GoPermission) == PackageManager.PERMISSION_GRANTED) {
            null
        } else {
            GoPermission
        }
    }

    /**
     * The subset of [GoRequiredFor] that [goContext] has NOT been granted.
     *
     * An empty list means the hub may start. A non-empty list names exactly which
     * grants are missing, so the caller never has to guess.
     */
    fun GoMissing(goContext: Context): List<String> =
        GoRequiredFor(Build.VERSION.SDK_INT).filter { GoPermission ->
            goContext.checkSelfPermission(GoPermission) != PackageManager.PERMISSION_GRANTED
        }

    /** True when every required permission is already granted. */
    fun GoHasAll(goContext: Context): Boolean = GoMissing(goContext).isEmpty()
}
