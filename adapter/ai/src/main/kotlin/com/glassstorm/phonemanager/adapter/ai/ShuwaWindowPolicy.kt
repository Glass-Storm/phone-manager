package com.glassstorm.phonemanager.adapter.ai

import hk.edu.hkmu.sign.shuwa.ShuwaRecognizerConfig
import hk.edu.hkmu.sign.shuwa.ShuwaSpec

/**
 * Windowing and de-duplication policy for the Shuwa HKSL gesture port.
 *
 * The recogniser is a **sequence** model, so the port needs three decisions the single-image path
 * never needed:
 *
 * 1. **When to run it.** A window is complete after [windowFrames] stills have arrived; after that the
 *    window advances by [stride] stills. Running on every frame would re-classify the same clip (and
 *    cost 16x the inference).
 * 2. **When a result is trustworthy.** A KNN table has no notion of "not signing": it always returns
 *    the nearest gloss, so ordinary movement produces confident-looking but meaningless labels.
 *    [requiredAgreements] consecutive windows must agree before anything is emitted, which rejects
 *    the label-of-the-moment that a single window produces.
 * 3. **When to repeat it.** A held sign produces several overlapping windows, so an identical label
 *    is suppressed for [duplicateWindowMs].
 *
 * The confidence decision is deliberately NOT here: ai-android's recogniser already flags a match
 * below its own threshold, and the port drops a flagged prediction. Keeping one threshold avoids two
 * numbers drifting apart.
 *
 * Pure Kotlin with no Android dependency, so the cadence, agreement and suppression rules are
 * host-JVM testable.
 *
 * @property windowFrames stills per recognition window.
 * @property stride stills advanced between windows; `1..windowFrames`.
 * @property duplicateWindowMs how long an identical label stays suppressed after it was emitted.
 * @property requiredAgreements consecutive windows that must produce the same label before it is
 *   emitted; `1` disables the agreement gate.
 */
class ShuwaWindowPolicy(
    val windowFrames: Int = DEFAULT_WINDOW_FRAMES,
    val stride: Int = DEFAULT_STRIDE,
    val duplicateWindowMs: Long = DEFAULT_DUPLICATE_WINDOW_MS,
    val requiredAgreements: Int = DEFAULT_REQUIRED_AGREEMENTS,
) {
    init {
        require(windowFrames >= MIN_WINDOW) { "windowFrames must be at least $MIN_WINDOW" }
        require(stride in 1..windowFrames) { "stride must be in 1..windowFrames" }
        require(duplicateWindowMs >= 0) { "duplicateWindowMs must not be negative" }
        require(requiredAgreements >= 1) { "requiredAgreements must be at least 1" }
    }

    private var frames = 0L
    private var candidateLabel: String? = null
    private var candidateCount = 0
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
     * @return `true` when the label has enough consecutive agreement and is not a suppressed repeat.
     */
    fun accept(
        label: String,
        atMs: Long,
    ): Boolean {
        if (label == candidateLabel) {
            candidateCount++
        } else {
            candidateLabel = label
            candidateCount = 1
        }
        if (candidateCount < requiredAgreements) {
            return false
        }
        val previousAtMs = lastAcceptedAtMs
        if (label == lastLabel && previousAtMs != null && atMs - previousAtMs < duplicateWindowMs) {
            return false
        }
        lastLabel = label
        lastAcceptedAtMs = atMs
        candidateCount = 0
        return true
    }

    companion object {
        /** Stills per window; the translator's fixed input length. */
        const val DEFAULT_WINDOW_FRAMES: Int = ShuwaSpec.WINDOW_FRAMES

        /** Stills advanced between windows (75% overlap at the default width). */
        const val DEFAULT_STRIDE: Int = ShuwaRecognizerConfig.DEFAULT_STRIDE

        /** Duplicate suppression window for a repeated gloss. */
        const val DEFAULT_DUPLICATE_WINDOW_MS: Long = 1_200L

        /**
         * Windows that must agree before a label is emitted. Two windows are 0.4 s apart at the
         * default stride, so a real sign costs 0.4 s more latency and a one-window fluke is dropped.
         */
        const val DEFAULT_REQUIRED_AGREEMENTS: Int = 2

        private const val MIN_WINDOW = 2
    }
}
