package com.glassstorm.phonemanager.adapter.jvm.ai

import com.glassstorm.phonemanager.core.domain.adapter.gesture.GesturePort
import com.glassstorm.phonemanager.core.model.GestureMeaningTable
import com.glassstorm.phonemanager.core.model.GestureOutcome

/**
 * The DEFAULT gesture engine for every JVM/unit build: fully offline and
 * DETERMINISTIC.
 *
 * The outcome is a pure function of the JPEG bytes — the label is selected from
 * the canned vocabulary and the confidence derived from the same digest, with no
 * clock, no `Random`, and no native library. That keeps the hub's relay tests
 * reproducible without an ARM device, where ai-android's MediaPipe native cannot
 * run at all. An empty image yields `null` (no hand), mirroring the engine's own
 * "no detection" contract.
 *
 * @param meanings the configurable label -> spoken-meaning table.
 */
class FakeGesturePort(
    private val meanings: GestureMeaningTable = GestureMeaningTable.standard,
) : GesturePort {
    override suspend fun recognize(jpeg: ByteArray): GestureOutcome? {
        if (jpeg.isEmpty()) return null
        val digest = Fnv1a.hash(jpeg)
        val labels = GestureMeaningTable.cannedLabels
        val label = labels[(digest.toULong() % labels.size.toULong()).toInt()]
        val text = meanings.meaningFor(label) ?: return null
        val confidence = (digest.toULong() % 101UL).toFloat() / 100f
        return GestureOutcome(label = label, text = text, confidence = confidence)
    }
}
