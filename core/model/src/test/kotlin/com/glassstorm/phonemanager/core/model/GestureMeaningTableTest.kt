package com.glassstorm.phonemanager.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GestureMeaningTableTest {
    @Test
    fun `bundled custom labels map to their spoken meaning`() {
        val table = GestureMeaningTable.standard

        assertThat(table.meaningFor("thanks")).isEqualTo("多謝")
        assertThat(table.meaningFor("one")).isEqualTo("一")
        assertThat(table.meaningFor("a")).isEqualTo("A")
        assertThat(table.meaningFor("b")).isEqualTo("B")
        assertThat(table.meaningFor("c")).isEqualTo("C")
        assertThat(table.meaningFor("z")).isEqualTo("Z")
    }

    @Test
    fun `an unmapped label yields a null meaning`() {
        assertThat(GestureMeaningTable.standard.meaningFor("No_Such_Label")).isNull()
    }

    @Test
    fun `canned labels map to their Cantonese meaning`() {
        val table = GestureMeaningTable.standard

        // The label vocabulary is the ENGINE's: it stays English.
        assertThat(GestureMeaningTable.cannedLabels)
            .containsExactly(
                "Thumb_Up",
                "Fist",
                "Open_Palm",
                "Pointing_Up",
                "Victory",
                "Thumb_Down",
                "ILoveYou",
            )
        // The meanings are the HUB's: they must be synthesizable by the Cantonese VITS voice.
        assertThat(table.meaningFor("Thumb_Up")).isEqualTo("好")
        assertThat(table.meaningFor("Fist")).isEqualTo("停")
        assertThat(table.meaningFor("Closed_Fist")).isEqualTo("停")
        assertThat(table.meaningFor("Open_Palm")).isEqualTo("你好")
        assertThat(table.meaningFor("Pointing_Up")).isEqualTo("等陣")
        assertThat(table.meaningFor("Victory")).isEqualTo("和平")
        assertThat(table.meaningFor("Thumb_Down")).isEqualTo("唔好")
        assertThat(table.meaningFor("ILoveYou")).isEqualTo("我愛你")
    }
}
