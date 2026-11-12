package com.glassstorm.phonemanager.adapter.ai

import hk.edu.hkmu.sign.shuwa.ShuwaRecognizerConfig
import hk.edu.hkmu.sign.shuwa.ShuwaSpec

/**
 * Windowing and de-duplication policy for the Shuwa HKSL gesture port.
 *
 * The recogniser is a **sequence** model, so the port needs two decisions the single-image path never
 * needed:
 *
 * 1. **When to run it.** A window is complete after [windowFrames] stills have arrived; after that the
 *    window advances by [stride] stills. Running on every frame would re-classify the same clip (and
 *    cost 16x the inference).
 * 2. **When to emit a result.** A held sign produces several overlapping windows, so the same gloss
 *    must not be reported (or spoken) repeatedly: an identical label is suppressed for
 *    [duplicateWindowMs].
 *
 * The confidence decision is deliberately NOT here: ai-android's recogniser already flags a match
 * below its own threshold, and the port drops a flagged prediction. Keeping one threshold avoids two
 * numbers drifting apart.
 *
 * Pure Kotlin with no Android dependency, so the cadence and suppression rules are host-JVM testable.
 *
 * @property windowFrames stills per recognition window.
 * @property stride stills advanced between windows; `1..windowFrames`.
 * @property duplicateWindowMs how long an identical label stays suppressed.
 */
class ShuwaWindowPolicy(
    val windowFrames: Int = DEFAULT_WINDOW_FRAMES,
    val stride: Int = DEFAULT_STRIDE,
    val duplicateWindowMs: Long = DEFAULT_DUPLICATE_WINDOW_MS,
) {
    init {
        require(windowFrames >= MIN_WINDOW) { "windowFrames must be at least $MIN_WINDOW" }
        require(stride in 1..windowFrames) { "stride must be in 1..windowFrames" }
        require(duplicateWindowMs >= 0) { "duplicateWindowMs must not be negative" }
    }

    private var frames = 0L
    private var lastLabel: String? = null
    private var lastAcceptedAtMs: Long? = null

    /**
     * Records one captured still.
     *
     * @return `true` when this still completes a window boundary and the recogniser should run.
     */
    fun onFrame(): Boolean {
        frames++
        if (frames < windowFrames) {
            return false
        }
        return (frames - windowFrames) % stride == 0L
    }

    /**
     * Decides whether a completed window's match should be emitted.
     *
     * @param label winning gloss.
     * @param atMs capture timestamp of the window's newest still.
     * @return `true` when the result is new and should be presented.
     */
    fun accept(
        label: String,
        atMs: Long,
    ): Boolean {
        val previousAtMs = lastAcceptedAtMs
        if (label == lastLabel && previousAtMs != null && atMs - previousAtMs < duplicateWindowMs) {
            return false
        }
        lastLabel = label
        lastAcceptedAtMs = atMs
        return true
    }

    companion object {
        /** Stills per window; the translator's fixed input length. */
        const val DEFAULT_WINDOW_FRAMES: Int = ShuwaSpec.WINDOW_FRAMES

        /** Stills advanced between windows (75% overlap at the default width). */
        const val DEFAULT_STRIDE: Int = ShuwaRecognizerConfig.DEFAULT_STRIDE

        /** Duplicate suppression window for a repeated gloss. */
        const val DEFAULT_DUPLICATE_WINDOW_MS: Long = 1_200L

        private const val MIN_WINDOW = 2
    }
}
