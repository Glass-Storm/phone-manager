package com.glassstorm.phonemanager.ui.hub

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.MotionDurationScale

/**
 * The hub's motion tokens, and the one place a state tween collapses to a snap.
 *
 * A state change eases out over [DURATION_MILLIS]. Reduced motion ("remove
 * animations" / animator duration scale 0) must SNAP instead, so the duration is
 * read back as 0 when the platform reports the scale off. Compose's own tween
 * already scales itself by `MotionDurationScale`; returning 0 is what makes
 * motion-off a hard snap rather than an imperceptible tween on a slow device.
 *
 * `MotionDurationScale` is not a composition local in Compose 1.7: it rides the
 * recomposition coroutine context, so it is read off the remembered scope.
 */
object HubMotion {
    const val DURATION_MILLIS = 200

    @Composable
    fun tweenMillis(): Int {
        val scale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
        return if (scale <= 0f) 0 else DURATION_MILLIS
    }
}
