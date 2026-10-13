package com.cqmu.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

class CurrentTimeNavigationTest {

    @Test
    fun `period resolver returns current class or the next class during a break`() {
        assertEquals(1, CurrentTimeNavigation.periodAtOrNext(LocalTime.of(8, 0)))
        assertEquals(1, CurrentTimeNavigation.periodAtOrNext(LocalTime.of(8, 45)))
        assertEquals(2, CurrentTimeNavigation.periodAtOrNext(LocalTime.of(9, 10)))
        assertEquals(2, CurrentTimeNavigation.periodAtOrNext(LocalTime.of(9, 12)))
        assertEquals(8, CurrentTimeNavigation.periodAtOrNext(LocalTime.of(13, 55)))
        assertEquals(15, CurrentTimeNavigation.periodAtOrNext(LocalTime.of(19, 5)))
        assertEquals(18, CurrentTimeNavigation.periodAtOrNext(LocalTime.of(21, 54)))
        assertNull(CurrentTimeNavigation.periodAtOrNext(LocalTime.of(21, 55)))
        assertNull(CurrentTimeNavigation.periodAtOrNext(LocalTime.of(22, 0)))
    }

    @Test
    fun `free room target moves to tomorrow after the final class`() {
        assertEquals(
            CurrentPeriodTarget(LocalDate.of(2026, 8, 30), 10),
            CurrentTimeNavigation.freeRoomTarget(LocalDateTime.of(2026, 8, 30, 16, 0))
        )
        assertEquals(
            CurrentPeriodTarget(LocalDate.of(2026, 8, 31), 1),
            CurrentTimeNavigation.freeRoomTarget(LocalDateTime.of(2026, 8, 30, 22, 0))
        )
    }

    @Test
    fun `semester week resolver follows an injected date`() {
        val week1 = LocalDate.of(2026, 9, 7)

        assertEquals(1, CurrentTimeNavigation.semesterWeek(week1, LocalDate.of(2026, 9, 7)))
        assertEquals(2, CurrentTimeNavigation.semesterWeek(week1, LocalDate.of(2026, 9, 14)))
        assertNull(CurrentTimeNavigation.semesterWeek(week1, LocalDate.of(2026, 9, 6)))
        assertNull(CurrentTimeNavigation.semesterWeek(week1, LocalDate.of(2027, 1, 11)))
    }

    @Test
    fun `semester week resolver follows the actual timetable beyond week 18`() {
        val timetable = Timetable(
            semesterId = 263,
            unitCount = 18,
            courses = listOf(
                Course("L020", "合成课程", "", "", 1, 1, 2, setOf(20), "", "")
            )
        )
        val week1 = LocalDate.of(2026, 9, 7)

        assertEquals(20, timetable.weekCount)
        assertEquals(
            19,
            CurrentTimeNavigation.semesterWeek(
                week1,
                week1.plusWeeks(18),
                maxWeeks = timetable.weekCount
            )
        )
    }

    @Test
    fun `campus now always uses Chongqing civil time`() {
        val clock = Clock.fixed(Instant.parse("2026-08-30T16:30:00Z"), ZoneOffset.UTC)

        assertEquals(
            LocalDateTime.of(2026, 8, 31, 0, 30),
            CurrentTimeNavigation.campusNow(clock)
        )
    }

    @Test
    fun `exam completion follows the v142 date-only rule`() {
        val today = LocalDate.of(2026, 8, 30)

        assertTrue(ExamDisplayPolicy.isFinished("2026-08-29", today))
        assertFalse(ExamDisplayPolicy.isFinished("2026-08-30", today))
        assertFalse(ExamDisplayPolicy.isFinished("2026-08-31", today))
        assertFalse(ExamDisplayPolicy.isFinished("invalid", today))
    }
}
