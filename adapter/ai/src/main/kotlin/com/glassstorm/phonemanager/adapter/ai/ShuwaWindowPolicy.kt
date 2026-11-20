package com.glassstorm.phonemanager.adapter.ai

/**
 * De-duplication policy for the Shuwa HKSL gesture port.
 *
 * A sign is segmented into a burst and classified by `AiShuwaGesturePort`; this class only decides
 * whether the resulting gloss is a repeat of the one just emitted. The confidence, distance and hand
 * gates stay in the port because they are model-specific.
 *
 * Pure Kotlin with no Android dependency, so the suppression rule is host-JVM testable.
 *
 * @property duplicateWindowMs how long an identical label stays suppressed after it was emitted.
 */
class ShuwaWindowPolicy(
    val duplicateWindowMs: Long = DEFAULT_DUPLICATE_WINDOW_MS,
) {
    init {
        require(duplicateWindowMs >= 0) { "duplicateWindowMs must not be negative" }
    }

    private var lastLabel: String? = null
    private var lastAcceptedAtMs: Long? = null

    /**
     * Decides whether a completed burst's match should be emitted.
     *
     * @param label winning gloss.
     * @param atMs capture timestamp of the burst's newest still.
     * @return `true` when the label is not a suppressed repeat.
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
        /** Duplicate suppression window for a repeated gloss. */
        const val DEFAULT_DUPLICATE_WINDOW_MS: Long = 1_200L
    }
}
