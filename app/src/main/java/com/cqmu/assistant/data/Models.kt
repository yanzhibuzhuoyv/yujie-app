package com.cqmu.assistant.data

/** 学年学期 */
data class Semester(
    val id: Long,
    val schoolYear: String,
    val term: String
) {
    /** 形如 "2026-2027 学年第1学期" */
    val displayName: String get() = "$schoolYear 学年第${term.trim()}学期"
}

/** 一门课的一次排课活动（某天某节次，含周次） */
data class Course(
    val lessonId: String,
    val name: String,
    val teacher: String,
    val room: String,
    val day: Int,          // 1..7，周一..周日
    val startPeriod: Int,  // 1..unitCount（大节/小节均按教务的小节计）
    val endPeriod: Int,
    val weeks: Set<Int>,   // 上课周次
    val type: String,      // 理论 / 实验 / ...
    val groupName: String  // 实验分组，可为空
)

/** 一个学期的完整课表 */
data class Timetable(
    val semesterId: Long,
    val unitCount: Int,
    val courses: List<Course>
) {
    val weekCount: Int
        get() = maxOf(18, courses.maxOfOrNull { it.weeks.maxOrNull() ?: 0 } ?: 0)

    /** 指定周次有课的活动（周次为空视为每周都有） */
    fun coursesOfWeek(week: Int?): List<Course> =
        if (week == null) courses
        else courses.filter { it.weeks.isEmpty() || week in it.weeks }
}

/** 空闲教室查询结果里的一间教室 */
data class FreeRoom(
    val name: String,
    val building: String,
    val campus: String,
    val facility: String,
    val capacity: Int,
    val notes: String
)

/** 空闲教室查询分页结果 */
data class FreeRoomResult(
    val rooms: List<FreeRoom>,
    val total: Int,
    val totalIsExplicit: Boolean
)

/** 当前登录用户信息 */
data class UserInfo(
    val name: String,
    val studentNo: String
)

/** 教室类型下拉选项（来自教务系统 free.action 页面） */
data class RoomTypeOption(val id: String, val name: String)

/** 校区下拉选项 */
data class CampusOption(val id: String, val name: String)

// ===================== 成绩 / 绩点 =====================

/** 一门课的成绩行（来自「所有学期成绩」明细表） */
data class GradeLine(
    val semester: String,        // 如 "2024-2025 1"
    val courseCode: String,
    val name: String,
    val category: String,        // 课程类别
    val nature: String,          // 课程性质（必修/限选/公选…）
    val credit: Double,
    val regularScore: String,    // 平时成绩
    val labScore: String,        // 实验成绩
    val finalExamScore: String,  // 期末成绩
    val totalScore: String,      // 正考总评
    val finalScore: String,      // 最终成绩
    val gradePoint: Double?      // 绩点
)

/** 学校统计的某学期绩点汇总 */
data class SemesterGpa(
    val schoolYear: String,
    val term: String,
    val courseCount: Int,
    val credits: Double,
    val avgScore: String,
    val gpa: String,
    val rank: String
)

/** 学校统计的总体绩点（有统计结果时才非空） */
data class OverallGpa(
    val avgScore: String,
    val gpa: String,
    val rank: String,
    val statTime: String
)

/** 完整成绩册 */
data class GradeBook(
    val courses: List<GradeLine>,
    val overall: OverallGpa?,
    val semesterSummaries: List<SemesterGpa>
) {
    /** 按学分加权计算的平均绩点（仅统计有绩点的课程） */
    fun computedGpa(courses: List<GradeLine> = this.courses): Double? {
        val valid = courses.filter { it.gradePoint != null && it.credit > 0 }
        if (valid.isEmpty()) return null
        val credits = valid.sumOf { it.credit }
        return valid.sumOf { it.credit * it.gradePoint!! } / credits
    }

    /** 按学期分组（保持原有顺序） */
    fun bySemester(): List<Pair<String, List<GradeLine>>> =
        courses.groupBy { it.semester }.map { (k, v) -> k to v }
}

// ===================== 考试 =====================

/** 考试批次（期末考试/期中考试/补考…） */
data class ExamBatch(val id: Long, val name: String, val selected: Boolean = false)

/** 一场考试的安排 */
data class ExamItem(
    val lessonCode: String,
    val name: String,
    val dept: String,        // 开课院系
    val teachClass: String,  // 教学班
    val credit: Double,
    val examType: String,    // 考试类别
    val date: String,        // 考试日期 yyyy-MM-dd
    val time: String,        // 考试安排（如 09:00~10:30）
    val place: String,       // 考试地点
    val seat: String,        // 考场座位号
    val status: String,      // 考试情况
    val note: String         // 其它说明
)
