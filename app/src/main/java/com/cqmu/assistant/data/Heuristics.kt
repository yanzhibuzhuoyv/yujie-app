package com.cqmu.assistant.data

import java.time.LocalDate

enum class AcademicBuilding { A1, A2, A3 }

data class RoomLocation(
    val building: AcademicBuilding,
    val floor: Int?
)

data class RoomFilterSelection(
    val building: AcademicBuilding? = null,
    val floor: Int? = null
) {
    fun selectBuilding(selected: AcademicBuilding?): RoomFilterSelection =
        if (selected == building) this else copy(building = selected, floor = null)

    fun selectFloor(selected: Int?): RoomFilterSelection = copy(floor = selected)
}

/** 自习室筛选策略：仅保留缙云校区 A1/A2/A3 的教室，排除教师休息室 */
object RoomFilter {
    const val CAMPUS = "缙云校区"

    private val buildingPattern = Regex(
        pattern = """(?<![A-Za-z0-9])(A[1-3])(?![A-Za-z0-9])""",
        option = RegexOption.IGNORE_CASE
    )
    private val roomNumberPattern = Regex(
        """^(?:\s*[-－—]\s*|\s+)([0-9]{2,})"""
    )

    /** 楼层取楼栋标识后房号数字串的第二位；非标准房号保留楼栋但楼层未知。 */
    fun locationOf(room: FreeRoom): RoomLocation? {
        val buildingMatch = buildingPattern.find(room.name) ?: return null
        val building = AcademicBuilding.valueOf(buildingMatch.groupValues[1].uppercase())
        val suffix = room.name.substring(buildingMatch.range.last + 1)
        val roomDigits = roomNumberPattern.find(suffix)?.groupValues?.get(1)
        val floor = roomDigits?.getOrNull(1)?.digitToIntOrNull()
        return RoomLocation(building, floor)
    }

    fun isSelfStudyRoom(room: FreeRoom): Boolean {
        if (room.campus != CAMPUS) return false
        if (room.name.contains("教师休息室")) return false
        return locationOf(room) != null
    }

    fun filter(rooms: List<FreeRoom>): List<FreeRoom> = rooms
        .filter { isSelfStudyRoom(it) }
        .distinctBy { it.building to it.name }

    fun filterByLocation(
        rooms: List<FreeRoom>,
        building: AcademicBuilding?,
        floor: Int?
    ): List<FreeRoom> = filter(rooms).filter { room ->
        val location = locationOf(room) ?: return@filter false
        (building == null || location.building == building) &&
            (floor == null || location.floor == floor)
    }

    fun availableFloors(
        rooms: List<FreeRoom>,
        building: AcademicBuilding?
    ): List<Int> = filterByLocation(rooms, building, floor = null)
        .mapNotNull { locationOf(it)?.floor }
        .distinct()
        .sorted()
}

/**
 * EAMS 的 semester.id 规则：2024-2025-1=242，每学年递增 20，第二学期 +1。
 * 2-8 月仍属于上一学年的第二学期；9 月起切到新学年第一学期。
 */
object SemesterContext {
    fun idForDate(date: LocalDate, knownStarts: Map<Long, LocalDate> = emptyMap()): Long {
        knownStarts.entries
            .filter { (_, start) -> !start.isAfter(date) }
            .maxByOrNull { (_, start) -> start }
            ?.let { return it.key }

        val startYear = if (date.monthValue >= 9) date.year else date.year - 1
        val term = if (date.monthValue in 2..8) 2 else 1
        return 242L + (startYear - 2024L) * 20L + (term - 1L)
    }
}

/**
 * 根据已知开学日选择默认学期。当前学期不再用固定 18 周截断；
 * 下一学期开学前两周才预切换，兼容第 19/20 周仍有课或考试的情况。
 */
object SemesterSelectionPolicy {
    private const val UPCOMING_PREVIEW_DAYS = 14L

    fun choose(
        semesterStarts: List<Pair<Semester, LocalDate>>,
        today: LocalDate
    ): Semester? {
        val sorted = semesterStarts.sortedBy { it.second }
        val upcoming = sorted.firstOrNull { (_, start) -> start.isAfter(today) }
        if (upcoming != null && !upcoming.second.isAfter(today.plusDays(UPCOMING_PREVIEW_DAYS))) {
            return upcoming.first
        }
        return sorted.lastOrNull { (_, start) -> !start.isAfter(today) }?.first
            ?: upcoming?.first
    }
}

/** 由考试日期推算所属学年学期（如 2026-07-12 → 2025-2026学年 第2学期） */
object TermLabel {
    fun fromDate(date: LocalDate): String {
        val y = date.year
        return when (date.monthValue) {
            in 2..8 -> "${y - 1}-${y}学年 第2学期"
            in 9..12 -> "${y}-${y + 1}学年 第1学期"
            else -> "${y - 1}-${y}学年 第1学期" // 1月
        }
    }

    fun fromDate(date: String): String? = runCatching { fromDate(LocalDate.parse(date)) }.getOrNull()
}

/** 学期字符串（"2024-2025 1"）排序：从新到旧 */
object SemesterOrder {
    fun parse(semester: String): Pair<String, Int> {
        val parts = semester.trim().split(Regex("\\s+"))
        val year = parts.getOrNull(0) ?: semester
        val term = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return year to term
    }

    fun comparator(): Comparator<String> =
        compareByDescending<String> { parse(it).first }.thenByDescending { parse(it).second }
}
