package com.glassstorm.phonemanager.core.model

/**
 * One static gesture recognized from a single still image. Pure data.
 *
 * [label] is the engine's raw canned-gesture label (for example `Thumb_Up`);
 * [text] is the hub-owned, spoken MEANING the table maps that label to (for
 * example `好`), and [confidence] is the engine's score in `[0, 1]`.
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
 * The engines emit their own labels: MediaPipe's canned gestures plus the custom
 * classifier labels its bundled `.task` bundle carries, and, for the sequence
 * recognizer, the `Hksl_*` glosses of the HKSL table. What a label MEANS to a
 * listener is a hub decision, not an engine fact, so it lives in this overridable
 * table rather than being hardcoded in the adapter. [standard] is the shipped
 * default for the single-image recognizer, [shuwa] the one for the HKSL sequence
 * recognizer; a caller may build any other table with [of].
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

        /** Bundled-model custom classifier label: a thumbs up. */
        const val THANKS: String = "thanks"

        /** Bundled-model custom classifier label: a single raised index finger. */
        const val ONE: String = "one"

        /**
         * The seven canned labels the hub recognizes, in the order the meaning
         * table declares them.
         */
        val cannedLabels: List<String> =
            listOf(THUMB_UP, FIST, OPEN_PALM, POINTING_UP, VICTORY, THUMB_DOWN, I_LOVE_YOU)

        /**
         * The shipped default table. `Closed_Fist` is an ALIAS for [FIST] so both
         * MediaPipe spellings resolve to the same meaning.
         *
         * Beyond the canned vocabulary, the bundled `.task` bundle emits custom
         * classifier labels: [THANKS], [ONE], and the single-letter ASL
         * fingerspelling labels `a`..`z` (spoken as their uppercase letter).
         *
         * Every meaning is written in the Cantonese (Traditional Chinese) words the
         * on-device Cantonese VITS voice can synthesize. The LABELS stay English
         * because they are the engine's vocabulary; only the spoken meanings are
         * hub policy. An English meaning here reaches a Cantonese-only voice and
         * synthesizes no samples, so do not "translate" these back.
         */
        val standard: GestureMeaningTable =
            GestureMeaningTable(
                linkedMapOf<String, String>().apply {
                    put(THUMB_UP, "好")
                    put(FIST, "停")
                    put(CLOSED_FIST, "停")
                    put(OPEN_PALM, "你好")
                    put(POINTING_UP, "等陣")
                    put(VICTORY, "和平")
                    put(THUMB_DOWN, "唔好")
                    put(I_LOVE_YOU, "我愛你")
                    put(THANKS, "多謝")
                    put(ONE, "一")
                    for (letter in 'a'..'z') {
                        put(letter.toString(), letter.uppercaseChar().toString())
                    }
                },
            )

        /**
         * The shipped table for the HKSL sequence recognizer.
         *
         * Its labels are the `Hksl_*` glosses of the provisioned KNN table, in that table's order, so
         * the mapping can be audited against the artifact one row at a time. A gloss is a lexical
         * sign with a direct Cantonese equivalent, which is why every entry is a translation and not
         * an interpretation. The combined `Hksl_*-Jsl_*` glosses are HKSL signs whose JSL sample set
         * contributed to the reference data; they are spoken as the HKSL meaning.
         *
         * Meanings are Cantonese (Traditional Chinese) for the same reason as [standard]: the
         * on-device voice is Cantonese-only.
         */
        val shuwa: GestureMeaningTable =
            GestureMeaningTable(
                linkedMapOf(
                    "Hksl_able_to" to "能夠",
                    "Hksl_bathroom" to "洗手間",
                    "Hksl_bicycle-Jsl_bicycle" to "單車",
                    "Hksl_body_soap" to "香皂",
                    "Hksl_busy" to "忙",
                    "Hksl_carrot" to "紅蘿蔔",
                    "Hksl_cash" to "現金",
                    "Hksl_chef" to "廚師",
                    "Hksl_coffee" to "咖啡",
                    "Hksl_correct" to "正確",
                    "Hksl_credit_cards" to "信用卡",
                    "Hksl_dog" to "狗",
                    "Hksl_double_bed" to "雙人床",
                    "Hksl_dragonfly" to "蜻蜓",
                    "Hksl_finish" to "完成",
                    "Hksl_flip_flops" to "拖鞋",
                    "Hksl_fortune_teller" to "算命",
                    "Hksl_garden" to "花園",
                    "Hksl_ginger" to "薑",
                    "Hksl_good" to "好",
                    "Hksl_grass" to "草",
                    "Hksl_grey" to "灰色",
                    "Hksl_hat-Jsl_cap" to "帽",
                    "Hksl_hearing_aid-Jsl_hearing_aid" to "助聽器",
                    "Hksl_high_heels" to "高跟鞋",
                    "Hksl_how_are_you" to "你好嗎",
                    "Hksl_jacket" to "外套",
                    "Hksl_leisurely" to "悠閒",
                    "Hksl_lemon" to "檸檬",
                    "Hksl_let_it_go" to "算啦",
                    "Hksl_lipstick" to "唇膏",
                    "Hksl_machine" to "機器",
                    "Hksl_mango" to "芒果",
                    "Hksl_matter" to "事情",
                    "Hksl_milk_tea" to "奶茶",
                    "Hksl_motorcycle-Jsl_motorcycle" to "電單車",
                    "Hksl_non_smoking_room" to "非吸煙室",
                    "Hksl_obstruct" to "阻礙",
                    "Hksl_onsite_payment" to "現場付款",
                    "Hksl_panda" to "熊貓",
                    "Hksl_pasta" to "意粉",
                    "Hksl_post_office" to "郵局",
                    "Hksl_purple" to "紫色",
                    "Hksl_read" to "閱讀",
                    "Hksl_reservation_booking" to "預約",
                    "Hksl_shampoo" to "洗頭水",
                    "Hksl_shark" to "鯊魚",
                    "Hksl_shirt-Jsl_shirt" to "恤衫",
                    "Hksl_signature" to "簽名",
                    "Hksl_single_bed" to "單人床",
                    "Hksl_smoking_room" to "吸煙室",
                    "Hksl_soft_drink" to "汽水",
                    "Hksl_special" to "特別",
                    "Hksl_steak" to "牛扒",
                    "Hksl_strange" to "奇怪",
                    "Hksl_summer" to "夏天",
                    "Hksl_sunglasses" to "太陽眼鏡",
                    "Hksl_supermarket" to "超級市場",
                    "Hksl_swimming_pool" to "游泳池",
                    "Hksl_tiger" to "老虎",
                    "Hksl_tomato" to "番茄",
                    "Hksl_trouble" to "麻煩",
                    "Hksl_understand" to "明白",
                    "Hksl_waiter" to "侍應",
                    "Hksl_watermelon" to "西瓜",
                    "Hksl_welcome" to "歡迎",
                    "Hksl_wine" to "酒",
                    "Hksl_winter-Jsl_winter" to "冬天",
                    "Hksl_worry" to "擔心",
                    "Hksl_write" to "寫",
                    "Hksl_yes" to "係",
                ),
            )

        /** Build a custom table from an explicit label -> meaning map. */
        fun of(meanings: Map<String, String>): GestureMeaningTable = GestureMeaningTable(meanings)
    }
}
