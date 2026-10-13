package com.cqmu.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HeuristicsTest {

    // ---------- RoomFilter：缙云 A1/A2/A3，排除教师休息室 ----------

    private fun room(name: String, campus: String = "缙云校区") = FreeRoom(name, "", campus, "", 40, "")

    @Test
    fun `room filter keeps only jinyun a1-a2-a3 non-teacher rooms`() {
        val rooms = listOf(
            room("求真楼A1-1209"),
            room("A2-1102教师休息室"),
            room("A3-1105教师休息室"),
            room("A1-1110教师休息室"),
            room("求真楼A1计算机房(3楼)"),
            room("构想楼B1-1206"),
            room("兰园L1-1202"),
            room("杏苑D4-6314"),
            room("求真楼A1-1504", campus = "袁家岗校区")
        )
        val kept = RoomFilter.filter(rooms)
        assertEquals(setOf("求真楼A1-1209", "求真楼A1计算机房(3楼)"), kept.map { it.name }.toSet())
        assertTrue(kept.all { it.name.contains("教师休息室").not() })
    }

    @Test
    fun `room filter removes duplicate room rows`() {
        val duplicate = FreeRoom(
            name = "求真楼A1-1115",
            building = "求真楼",
            campus = "缙云校区",
            facility = "智慧教室",
            capacity = 30,
            notes = ""
        )

        assertEquals(1, RoomFilter.filter(listOf(duplicate, duplicate)).size)
    }

    @Test
    fun `room location parses independent building code and second room digit as floor`() {
        assertEquals(
            RoomLocation(AcademicBuilding.A3, 1),
            RoomFilter.locationOf(room("求真楼A3-1106"))
        )
        assertEquals(
            RoomLocation(AcademicBuilding.A3, 1),
            RoomFilter.locationOf(room("求真楼A3 1106"))
        )
        assertEquals(
            RoomLocation(AcademicBuilding.A3, 3),
            RoomFilter.locationOf(room("求真楼A3-1301"))
        )
        assertEquals(
            RoomLocation(AcademicBuilding.A1, 4),
            RoomFilter.locationOf(room("求真楼A1-2406"))
        )
        assertEquals(
            listOf(AcademicBuilding.A1, AcademicBuilding.A2, AcademicBuilding.A3),
            listOf("A1-1101", "A2-2101", "A3-3101")
                .map { RoomFilter.locationOf(room(it))?.building }
        )
    }

    @Test
    fun `room location rejects building code embedded in another token`() {
        assertNull(RoomFilter.locationOf(room("求真楼A10-1201")))
        assertNull(RoomFilter.locationOf(room("求真楼BA1-1201")))
        assertNull(RoomFilter.locationOf(room("求真楼A1B-1201")))

        assertTrue(
            RoomFilter.filter(
                listOf(room("求真楼A10-1201"), room("求真楼BA1-1201"))
            ).isEmpty()
        )
    }

    @Test
    fun `room location keeps building but leaves nonstandard computer room floor unknown`() {
        assertEquals(
            RoomLocation(AcademicBuilding.A1, floor = null),
            RoomFilter.locationOf(room("求真楼A1计算机房(3楼)"))
        )
    }

    @Test
    fun `room location filter combines building and floor with and semantics`() {
        val rooms = listOf(
            room("求真楼A1-1101"),
            room("求真楼A1-1301"),
            room("求真楼A2-1302"),
            room("求真楼A3-1401"),
            room("求真楼A1计算机房(3楼)")
        )

        val filtered = RoomFilter.filterByLocation(
            rooms,
            building = AcademicBuilding.A1,
            floor = 3
        )

        assertEquals(listOf("求真楼A1-1301"), filtered.map { it.name })
    }

    @Test
    fun `unknown floor appears only when all floors are selected`() {
        val standard = room("求真楼A1-1301")
        val unknownFloor = room("求真楼A1计算机房(3楼)")
        val rooms = listOf(standard, unknownFloor)

        assertEquals(
            setOf(standard.name, unknownFloor.name),
            RoomFilter.filterByLocation(
                rooms,
                building = AcademicBuilding.A1,
                floor = null
            ).map { it.name }.toSet()
        )
        assertEquals(
            listOf(standard.name),
            RoomFilter.filterByLocation(
                rooms,
                building = AcademicBuilding.A1,
                floor = 3
            ).map { it.name }
        )
    }

    @Test
    fun `selecting another building resets the selected floor`() {
        val selected = RoomFilterSelection(
            building = AcademicBuilding.A1,
            floor = 4
        )

        assertEquals(
            RoomFilterSelection(building = AcademicBuilding.A2, floor = null),
            selected.selectBuilding(AcademicBuilding.A2)
        )
    }

    @Test
    fun `semester context follows the query date across vacation boundaries`() {
        assertEquals(262L, SemesterContext.idForDate(LocalDate.of(2026, 1, 31)))
        assertEquals(263L, SemesterContext.idForDate(LocalDate.of(2026, 2, 1)))
        assertEquals(263L, SemesterContext.idForDate(LocalDate.of(2026, 8, 30)))
        assertEquals(282L, SemesterContext.idForDate(LocalDate.of(2026, 9, 1)))
        assertEquals(
            282L,
            SemesterContext.idForDate(
                LocalDate.of(2026, 8, 30),
                mapOf(263L to LocalDate.of(2026, 3, 2), 282L to LocalDate.of(2026, 8, 25))
            )
        )
    }

    @Test
    fun `semester selection keeps the current term after week 18`() {
        val current = Semester(263L, "2025-2026", "2")
        val next = Semester(282L, "2026-2027", "1")

        assertEquals(
            current,
            SemesterSelectionPolicy.choose(
                listOf(
                    current to LocalDate.of(2026, 3, 2),
                    next to LocalDate.of(2026, 9, 7)
                ),
                LocalDate.of(2026, 7, 15)
            )
        )
    }

    @Test
    fun `semester selection switches to an upcoming term shortly before it starts`() {
        val current = Semester(263L, "2025-2026", "2")
        val next = Semester(282L, "2026-2027", "1")

        assertEquals(
            next,
            SemesterSelectionPolicy.choose(
                listOf(
                    current to LocalDate.of(2026, 3, 2),
                    next to LocalDate.of(2026, 9, 7)
                ),
                LocalDate.of(2026, 9, 1)
            )
        )
    }

    // ---------- TermLabel：由考试日期推算学年学期 ----------

    @Test
    fun `term label from exam date`() {
        assertEquals("2025-2026学年 第2学期", TermLabel.fromDate(LocalDate.of(2026, 7, 12)))
        assertEquals("2025-2026学年 第2学期", TermLabel.fromDate(LocalDate.of(2026, 8, 30)))
        assertEquals("2026-2027学年 第1学期", TermLabel.fromDate(LocalDate.of(2026, 9, 20)))
        assertEquals("2025-2026学年 第1学期", TermLabel.fromDate(LocalDate.of(2026, 1, 10)))
        assertEquals("2025-2026学年 第2学期", TermLabel.fromDate(LocalDate.of(2026, 2, 25)))
        assertEquals("2025-2026学年 第2学期", TermLabel.fromDate("2026-06-26"))
    }

    // ---------- SemesterOrder：学期从新到旧 ----------

    @Test
    fun `semester order newest first`() {
        val list = listOf("2024-2025 1", "2025-2026 2", "2024-2025 2", "2025-2026 1", "2026-2027 1")
        val sorted = list.sortedWith(SemesterOrder.comparator())
        assertEquals(
            listOf("2026-2027 1", "2025-2026 2", "2025-2026 1", "2024-2025 2", "2024-2025 1"),
            sorted
        )
    }

    // ---------- PeriodTimes：按作息规则推算 ----------

    @Test
    fun `period times follow school schedule`() {
        // 8:30 上第 1 节；2、3 节课后休 10 分钟，其余 5 分钟
        assertEquals("08:30", PeriodTimes.start(1))
        assertEquals("09:15", PeriodTimes.start(2))
        assertEquals("10:05", PeriodTimes.start(3))
        assertEquals("10:55", PeriodTimes.start(4))
        // 14:00 上第 8 节；9、10 节课后休 10 分钟
        assertEquals("14:00", PeriodTimes.start(8))
        assertEquals("14:45", PeriodTimes.start(9))
        assertEquals("15:35", PeriodTimes.start(10))
        assertEquals("16:25", PeriodTimes.start(11))
        // 19:00 上第 15 节；15-18 节课间均 5 分钟
        assertEquals("19:00", PeriodTimes.start(15))
        assertEquals("19:45", PeriodTimes.start(16))
        assertEquals("21:15", PeriodTimes.start(18))
        // 区间与结束
        assertEquals("14:00-14:40", PeriodTimes.range(8))
        assertEquals("21:55", PeriodTimes.endMinutes(18).let { "%02d:%02d".format(it / 60, it % 60) })
        assertFalse(PeriodTimes.range(3).isBlank())
    }
}
