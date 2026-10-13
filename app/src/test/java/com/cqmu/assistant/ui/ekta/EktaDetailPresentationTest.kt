package com.cqmu.assistant.ui.ekta

import org.junit.Assert.assertEquals
import org.junit.Test

class EktaDetailPresentationTest {
    @Test
    fun `formats official timestamp in China time with seconds`() {
        assertEquals(
            "2024-01-01 08:00:00",
            EktaDetailPresentation.formatTimestamp(1_704_067_200_000L)
        )
    }

    @Test
    fun `formats signed hours from record type`() {
        assertEquals("+1.5 学时", EktaDetailPresentation.formatHours(1.5, 1))
        assertEquals("-2 学时", EktaDetailPresentation.formatHours(2.0, 2))
    }

    @Test
    fun `maps known and unknown official source types`() {
        assertEquals("活动", EktaDetailPresentation.sourceLabel(1))
        assertEquals("青年大学习", EktaDetailPresentation.sourceLabel(9))
        assertEquals("置换", EktaDetailPresentation.sourceLabel(31))
        assertEquals("其他来源", EktaDetailPresentation.sourceLabel(999))
    }
}
