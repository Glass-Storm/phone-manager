package com.glassstorm.phonemanager.core.model

/**
 * One static gesture recognized from a single still image. Pure data.
 *
 * [label] is the engine's raw canned-gesture label (for example `Thumb_Up`);
 * [text] is the hub-owned, spoken MEANING the table maps that label to (for
 * example `Hello`), and [confidence] is the engine's score in `[0, 1]`.
 *
 * Keeping [label] and [text] separate is deliberate: the meaning is a policy the
 * hub owns and can re-wire, while the label is the engine's vocabulary the hub
 * does not control.
 */
data class GestureOutcome(
    val label: String,
    val text: String,
    val confidence: Float,
)

/**
 * The CONFIGURABLE canned-gesture -> spoken-meaning table.
 *
 * The MediaPipe engine emits a fixed vocabulary of canned labels. What a label
 * MEANS to a listener is a hub decision, not an engine fact, so it lives in this
 * overridable table rather than being hardcoded in the adapter. [standard] is the
 * shipped default; a caller may build any other table with [of].
 *
 * An unmapped label yields `null` from [meaningFor] (the adapter then reports no
 * outcome) — silently inventing a meaning is forbidden.
 */
class GestureMeaningTable private constructor(
    private val meanings: Map<String, String>,
) {
    /** The spoken meaning for [label], or `null` when the table does not map it. */
    fun meaningFor(label: String): String? = meanings[label]

    /** Every label the table knows, in declaration order. */
    fun labels(): Set<String> = meanings.keys

    companion object {
        /** Canned label: thumbs up. */
        const val THUMB_UP: String = "Thumb_Up"

        /** Canned label: closed fist (also reported as `Closed_Fist` by some builds). */
        const val FIST: String = "Fist"

        /** MediaPipe's own spelling of the closed fist. */
        const val CLOSED_FIST: String = "Closed_Fist"

        /** Canned label: open palm. */
        const val OPEN_PALM: String = "Open_Palm"

        /** Canned label: index finger pointing up. */
        const val POINTING_UP: String = "Pointing_Up"

        /** Canned label: victory / peace sign. */
        const val VICTORY: String = "Victory"

        /** Canned label: thumbs down. */
        const val THUMB_DOWN: String = "Thumb_Down"

        /** Canned label: the `I love you` hand sign. */
        const val I_LOVE_YOU: String = "ILoveYou"

        /**
         * The seven canned labels the hub recognizes, in the order the meaning
         * table declares them.
         */
        val cannedLabels: List<String> =
            listOf(THUMB_UP, FIST, OPEN_PALM, POINTING_UP, VICTORY, THUMB_DOWN, I_LOVE_YOU)

        /**
         * The shipped default table. `Closed_Fist` is an ALIAS for [FIST] so both
         * MediaPipe spellings resolve to the same meaning.
         */
        val standard: GestureMeaningTable =
            GestureMeaningTable(
                linkedMapOf(
                    THUMB_UP to "Yes",
                    FIST to "Stop",
                    CLOSED_FIST to "Stop",
                    OPEN_PALM to "Hello",
                    POINTING_UP to "Wait",
                    VICTORY to "Peace",
                    THUMB_DOWN to "No",
                    I_LOVE_YOU to "I love you",
                ),
            )

        /** Build a custom table from an explicit label -> meaning map. */
        fun of(meanings: Map<String, String>): GestureMeaningTable = GestureMeaningTable(meanings)
    }
}
