package com.cqmu.assistant.data

import org.jsoup.Jsoup

/**
 * 教务系统（EAMS / Beangle）页面解析器。
 * 全部针对 https://jiaowu.cqmu.edu.cn:8080/eams 实测页面结构编写。
 */
object EamsParser {

    // ================= 用户信息 =================

    /** 首页右上角：某同学(2024000000) */
    fun parseUserInfo(homeHtml: String): UserInfo? {
        val m = Regex("""/eams/security/my\.action"[^>]*>\s*([^<(]+?)\s*[（(]([^)）]+)[)）]""")
            .find(homeHtml) ?: return null
        val name = m.groupValues[1].trim()
        val no = m.groupValues[2].trim()
        if (name.isEmpty() || no.isEmpty()) return null
        return UserInfo(name, no)
    }

    /** 课表查询页脚本中的个人 ids（学生视图，合成示例 ids=900001） */
    fun parseStdIds(courseTablePageHtml: String): String? {
        val stdPos = courseTablePageHtml.indexOf("courseTableType == \"std\"")
        val region = if (stdPos >= 0)
            courseTablePageHtml.substring(stdPos, minOf(courseTablePageHtml.length, stdPos + 4000))
        else courseTablePageHtml
        return Regex("""addInput\(form,"ids","(\d+)"\)""").find(region)?.groupValues?.get(1)
            ?: Regex("""addInput\(form,"ids","(\d+)"\)""").find(courseTablePageHtml)
                ?.groupValues?.get(1)
    }

    // ================= 学期 =================

    /**
     * dataQuery.action?dataType=semesterCalendar 返回的是宽松 JS 对象：
     * semesters:{y0:[{id:283,schoolYear:"2026-2027",name:"2"},{...}],y1:[...]}
     */
    fun parseSemesters(text: String): List<Semester> {
        val regex = Regex("""id\s*:\s*(\d+)\s*,\s*schoolYear\s*:\s*"([^"]*)"\s*,\s*name\s*:\s*"([^"]*)"""")
        return regex.findAll(text)
            .map { Semester(it.groupValues[1].toLong(), it.groupValues[2], it.groupValues[3]) }
            .distinctBy { it.id }
            .toList()
    }

    /** 教学日历视图里第 1 周对应的第一个日期（周一） */
    fun parseWeek1Monday(calendarHtml: String): String? {
        val dateRegex = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""")
        fun dateIn(text: String): String? = dateRegex.find(text)?.let { match ->
            runCatching {
                java.time.LocalDate.of(
                    match.groupValues[1].toInt(),
                    match.groupValues[2].toInt(),
                    match.groupValues[3].toInt()
                ).toString()
            }.getOrNull()
        }

        val marker = Jsoup.parse(calendarHtml)
            .getElementsContainingOwnText("第1周")
            .minByOrNull { it.text().length }
        listOfNotNull(marker?.closest("tr"), marker?.parent(), marker)
            .distinct()
            .forEach { scope -> dateIn(scope.text())?.let { return it } }

        val markerIndex = calendarHtml.indexOf("第1周")
        if (markerIndex < 0) return null
        val nearby = calendarHtml.substring(
            maxOf(0, markerIndex - 200),
            minOf(calendarHtml.length, markerIndex + 200)
        )
        return dateIn(nearby)
    }

    // ================= 个人课表 =================

    /**
     * 解析 courseTableForStd!courseTable.action 返回的页面。
     * 页面脚本里每门课是：
     *   actTeacherName.push("合成教师");
     *   activity = new TaskActivity(actTeacherId.join(','),actTeacherName.join(','),"L001",
     *       "合成课程","C001","合成教室A1-1101","01110110...",null,null,assistantName,"",
     *       "理论","","","8-10节","");
     *   index =1*unitCount+7; index =1*unitCount+8; index =1*unitCount+9;
     * index = (星期-1)*unitCount + (小节-1)，星期/小节均为 0 基。
     */
    fun parseTimetable(html: String, semesterId: Long): Timetable {
        val unitCount = Regex("""var unitCount\s*=\s*(\d+)""")
            .find(html)?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it in 1..54 }
            ?: throw EamsException("课表响应格式异常，请重试")

        val teacherNameRegex = Regex("""actTeacherName\.push\("([^"]+)"""")
        val indexRegex = Regex("""index\s*=\s*(\d+)\s*\*\s*unitCount\s*\+\s*(\d+)\s*;""")
        val callRegex = Regex("new TaskActivity[(]")

        val calls = callRegex.findAll(html).toList()
        val raws = mutableListOf<RawActivity>()

        for ((i, call) in calls.withIndex()) {
            val preludeStart = if (i == 0) 0 else calls[i - 1].range.last + 1
            val prelude = html.substring(preludeStart, call.range.first)
            val teachers = teacherNameRegex.findAll(prelude)
                .map { it.groupValues[1] }
                .filter { it.isNotBlank() }
                .toList()

            val argsStart = call.range.last + 1
            val argsEnd = html.indexOf(");", argsStart)
            if (argsEnd < 0) throw EamsException("课表活动格式异常，请重试")
            val args = splitArgs(html.substring(argsStart, argsEnd))

            val segmentEnd = if (i + 1 < calls.size) calls[i + 1].range.first
            else minOf(html.length, argsEnd + 6000)
            val segment = html.substring(argsEnd, segmentEnd)

            fun literal(index: Int): String = literalArg(args.getOrNull(index))

            val lessonId = literal(2)
            val name = literal(3)
            val room = literal(5)
            val weeksStr = literal(6)
            if (weeksStr.isBlank() || !weeksStr.matches(Regex("0[01]*"))) {
                throw EamsException("课表活动周次异常，请重试")
            }
            val assistant = literal(9)
            val type = literal(11)
            val groupName = literal(13)
            val hint = literal(14)
            val displayName = if (name.isNotBlank()) name else hint
            if (displayName.isBlank()) throw EamsException("课表活动缺少课程名称，请重试")

            var teacher = teachers.joinToString("、")
            if (assistant.isNotBlank()) {
                teacher = if (teacher.isBlank()) assistant else "$teacher(助教:$assistant)"
            }

            // 周次串为 1 基：第 0 位恒为占位 '0'，串[k]=='1' 表示第 k 周上课
            // 首位是协议占位，第 1 周从索引 1 开始。
            val weeks = buildSet {
                for (k in 1 until weeksStr.length) if (weeksStr[k] == '1') add(k)
            }

            var activitySlotCount = 0
            for (m in indexRegex.findAll(segment)) {
                val day = m.groupValues[1].toInt()      // 0 基，周一=0
                val period = m.groupValues[2].toInt()   // 0 基
                if (day !in 0..6 || period !in 0 until unitCount) {
                    throw EamsException("课表活动节次异常，请重试")
                }
                if (weeks.isNotEmpty()) {
                    raws += RawActivity(
                        lessonId = lessonId,
                        name = displayName,
                        teacher = teacher,
                        room = room,
                        day = day + 1,
                        period = period + 1,
                        weeks = weeks,
                        type = type,
                        groupName = groupName
                    )
                }
                activitySlotCount++
            }
            if (activitySlotCount == 0) throw EamsException("课表活动缺少节次，请重试")
        }

        val merged = mergeContiguous(raws)
        return Timetable(semesterId, unitCount, merged)
    }

    private data class RawActivity(
        val lessonId: String,
        val name: String,
        val teacher: String,
        val room: String,
        val day: Int,
        val period: Int,
        val weeks: Set<Int>,
        val type: String,
        val groupName: String
    )

    private data class CourseScheduleKey(
        val lessonId: String,
        val name: String,
        val teacher: String,
        val room: String,
        val day: Int,
        val startPeriod: Int,
        val endPeriod: Int,
        val type: String,
        val groupName: String
    )

    /** 逐周合并，避免单双周、换教室或分组活动互相污染；相同展示再压缩周次。 */
    private fun mergeContiguous(raws: List<RawActivity>): List<Course> {
        val maxWeek = maxOf(18, raws.maxOfOrNull { it.weeks.maxOrNull() ?: 0 } ?: 0)
        val weekly = (1..maxWeek).flatMap { week ->
            raws.filter { week in it.weeks }
                .groupBy { Triple(it.lessonId, it.day, it.name) }
                .values
                .flatMap(::mergeCourseDay)
                .map { it.copy(weeks = setOf(week)) }
        }
        return weekly.groupBy(::scheduleKey).values
            .map { variants ->
                variants.first().copy(weeks = variants.flatMap { it.weeks }.toSet())
            }
            .sortedWith(compareBy({ it.day }, { it.startPeriod }, { it.name }))
    }

    private fun scheduleKey(course: Course) = CourseScheduleKey(
        course.lessonId, course.name, course.teacher, course.room, course.day,
        course.startPeriod, course.endPeriod, course.type, course.groupName
    )

    private fun mergeCourseDay(raws: List<RawActivity>): List<Course> {
        val slots = raws.groupBy { it.period }
            .map { (period, entries) -> mergeSlot(entries, period) }
            .sortedBy { it.period }
        val result = mutableListOf<Course>()
        var run = mutableListOf<RawActivity>()

        fun flushRun() {
            if (run.isEmpty()) return
            val first = run.first()
            result += Course(
                first.lessonId, first.name, first.teacher, first.room, first.day,
                run.first().period, run.last().period, first.weeks, first.type, first.groupName
            )
            run = mutableListOf()
        }

        for (slot in slots) {
            val contiguous = run.lastOrNull()?.let { previous ->
                previous.period + 1 == slot.period && sameSchedule(previous, slot)
            } ?: true
            if (!contiguous) flushRun()
            run += slot
        }
        flushRun()
        return result
    }

    private fun mergeSlot(entries: List<RawActivity>, period: Int): RawActivity {
        val first = entries.first()
        return first.copy(
            teacher = joinDistinct(entries.map { it.teacher }, "、"),
            room = joinDistinct(entries.map { it.room }, " / "),
            period = period,
            weeks = entries.flatMap { it.weeks }.toSet(),
            type = joinDistinct(entries.map { it.type }, "/"),
            groupName = joinDistinct(entries.map { it.groupName }, "/")
        )
    }

    private fun joinDistinct(values: List<String>, separator: String): String =
        values.filter { it.isNotBlank() }.distinct().joinToString(separator)

    private fun sameSchedule(left: RawActivity, right: RawActivity): Boolean =
        left.teacher == right.teacher && left.room == right.room &&
            left.type == right.type && left.groupName == right.groupName

    /**
     * 参数切分：脚本参数里含 actTeacherId.join(',') 这类带引号/括号逗号的表达式，
     * 需要按引号与括号深度切分。
     */
    private fun splitArgs(s: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inDq = false; var inSq = false; var depth = 0; var esc = false
        for (c in s) {
            when {
                esc -> { sb.append(c); esc = false }
                (c == '\\') && (inDq || inSq) -> { sb.append(c); esc = true }
                c == '"' && !inSq -> { inDq = !inDq; sb.append(c) }
                c == '\'' && !inDq -> { inSq = !inSq; sb.append(c) }
                c == '(' && !inDq && !inSq -> { depth++; sb.append(c) }
                c == ')' && !inDq && !inSq -> { depth--; sb.append(c) }
                c == ',' && !inDq && !inSq && depth == 0 -> {
                    out.add(sb.toString().trim()); sb.clear()
                }
                else -> sb.append(c)
            }
        }
        out.add(sb.toString().trim())
        return out
    }

    /** 引号包裹的字面量则去引号返回，表达式返回空串 */
    private fun literalArg(arg: String?): String {
        if (arg == null) return ""
        val t = arg.trim()
        if (t.length >= 2) {
            val f = t.first(); val l = t.last()
            if ((f == '"' && l == '"') || (f == '\'' && l == '\'')) return t.substring(1, t.length - 1)
        }
        return ""
    }

    // ================= 空闲教室 =================

    /**
     * free!search.action 返回的 gridtable：
     * 序号 | 名称 | 教学楼 | 校区 | 教室设备配置 | 容量(数字 + 若干 N<sup>标签</sup>)
     * 总数在脚本 pageInfo(1,20,494) 里。
     */
    fun parseFreeRooms(html: String): FreeRoomResult {
        val doc = Jsoup.parse(html)
        val emptyResultMarker = Regex(
            """没有\s*(?:查询到|找到)?\s*(?:符合条件的)?\s*(?:教室|记录)|""" +
                """没有\s*(?:查询到|找到|符合条件的)\s*(?:符合条件的)?\s*数据|""" +
                """暂无\s*(?:符合条件的)?\s*(?:教室|记录|数据)|""" +
                """无符合条件的(?:教室|记录)|无记录|查询结果为空"""
        )
        val table = doc.selectFirst("table.gridtable")
        if (table == null && !doc.text().contains(emptyResultMarker)) {
            throw EamsException("空闲教室响应格式异常，请重试")
        }
        val rooms = mutableListOf<FreeRoom>()
        // 延续 1.6.5 的接口兼容行为：明确的无结果页可能不输出 gridtable。
        for (tr in table?.select("tbody tr").orEmpty()) {
            val tds = tr.select("td")
            if (tds.size < 6) {
                if (tr.text().contains(emptyResultMarker)) continue
                throw EamsException("空闲教室响应行格式异常，请重试")
            }
            val name = tds[1].text().trim()
            if (name.isEmpty()) throw EamsException("空闲教室响应缺少教室名称，请重试")
            val capacityCell = tds[5]
            val capacity = Regex("""(\d+)""").find(capacityCell.text())?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val notes = Regex("""(\d+)\s*<sup>([^<]+)</sup>""")
                .findAll(capacityCell.html())
                .joinToString("，") { "${it.groupValues[1]}${it.groupValues[2]}" }
            rooms += FreeRoom(
                name = name,
                building = tds[2].text().trim(),
                campus = tds[3].text().trim(),
                facility = tds[4].text().trim(),
                capacity = capacity,
                notes = notes
            )
        }
        val pageInfoRegex = Regex(
            """pageInfo\s*\(\s*["']?\d+["']?\s*,\s*["']?\d+["']?\s*,""" +
                """\s*["']?(\d+)["']?(?:\s*,[^)]*)?\s*\)"""
        )
        // 无法识别的分页脚本按 1.6.5 的方式降级为当前页数量；分页状态机会继续读取到空页。
        val explicitTotals = pageInfoRegex.findAll(html)
            .mapNotNull { match -> match.groupValues[1].toIntOrNull() }
            .distinct()
            .toList()
        if (explicitTotals.size > 1) {
            throw EamsException("空闲教室分页总数冲突，请重试")
        }
        val explicitTotal = explicitTotals.singleOrNull()
        return FreeRoomResult(
            rooms = rooms,
            total = explicitTotal ?: rooms.size,
            totalIsExplicit = explicitTotal != null
        )
    }

    // ================= 登录页 =================

    /** CAS 登录页里的流程令牌 execution */
    fun parseExecution(loginPageHtml: String): String? =
        Regex("""name="execution"\s+value="([^"]+)"""").find(loginPageHtml)?.groupValues?.get(1)

    /** 登录页是否要求验证码（页面脚本第一处 var 声明才是服务端初始值，后面的会被运行时改写） */
    fun parseCaptchaRequired(loginPageHtml: String): Boolean {
        val m = Regex("""var\s+casServerCaptchaShow\s*=\s*(true|false)""")
            .find(loginPageHtml) ?: return false
        return m.groupValues[1] == "true"
    }

    // ===================== 成绩 / 绩点 =====================

    /**
     * 解析「所有学期成绩」页（person!historyCourseGrade.action?projectType=MAJOR）。
     * 页面含三类表格：分学期汇总（学年度…平均学分绩点）、总体统计（统计时间）、
     * 课程明细（课程代码…最终成绩、绩点）。均为空数据时对应集合为空。
     */
    fun parseGradeHistory(html: String): GradeBook {
        val doc = Jsoup.parse(html)
        var courses = listOf<GradeLine>()
        var semesterSummaries = listOf<SemesterGpa>()
        var overall: OverallGpa? = null

        for (table in doc.select("table")) {
            val heads = table.select("th").map { it.text().trim() }
            when {
                heads.contains("课程代码") && heads.contains("绩点") ->
                    courses = table.select("tr").drop(1).mapNotNull { tr ->
                        val c = tr.select("td").map { it.text().trim() }
                        // 空分数单元格（如无人值的实验成绩）经 Jsoup 规范化后可能被合并，
                        // 因此前 7 列从头取、后 3 列（总评/最终/绩点）从尾取，中间尽力而为
                        if (c.size < 12) return@mapNotNull null
                        GradeLine(
                            semester = c[0],
                            courseCode = c[1],
                            name = c[3],
                            category = c[4],
                            nature = c[5],
                            credit = c[6].toDoubleOrNull() ?: 0.0,
                            regularScore = c.getOrElse(7) { "" },
                            labScore = if (c.size >= 13) c.getOrElse(8) { "" } else "",
                            finalExamScore = if (c.size >= 13) c.getOrElse(9) { "" }
                            else c.getOrElse(8) { "" },
                            totalScore = c[c.size - 3],
                            finalScore = c[c.size - 2],
                            gradePoint = c.last().toDoubleOrNull()
                        )
                    }

                heads.contains("学年度") && heads.contains("平均学分绩点") ->
                    semesterSummaries = table.select("tr").drop(1).mapNotNull { tr ->
                        val c = tr.select("td").map { it.text().trim() }
                        if (c.size < 6) return@mapNotNull null
                        SemesterGpa(
                            schoolYear = c[0],
                            term = c[1],
                            courseCount = c[2].toIntOrNull() ?: 0,
                            credits = c[3].toDoubleOrNull() ?: 0.0,
                            avgScore = c[4],
                            gpa = c[5],
                            rank = c.getOrNull(6) ?: ""
                        )
                    }

                heads.contains("统计时间") -> {
                    if (overall == null) {
                        overall = table.select("tr").drop(1).firstOrNull()?.let { tr ->
                            val c = tr.select("td").map { it.text().trim() }
                            if (c.size < 4) null
                            // 行尾 4 格固定为 平均成绩/平均学分绩点/专业排名/统计时间
                            //（部分部署在行首多一个课程性质标签列，故从尾部对齐）
                            else OverallGpa(
                                avgScore = c[c.size - 4],
                                gpa = c[c.size - 3],
                                rank = c[c.size - 2],
                                statTime = c.last()
                            )
                        }
                    }
                }
            }
        }
        return GradeBook(courses, overall, semesterSummaries)
    }

    // ===================== 考试 =====================

    /** 考试批次下拉（期末考试/期中考试/各类补考…），selected 为页面默认选中项 */
    fun parseExamBatches(html: String): List<ExamBatch> =
        Jsoup.parse(html).select("select option").mapNotNull { o ->
            val id = o.attr("value").toLongOrNull() ?: return@mapNotNull null
            val label = o.text().trim()
            if (!label.contains("考") && !label.contains("重修")) return@mapNotNull null
            ExamBatch(id, label, selected = o.hasAttr("selected"))
        }

    /** 考试安排表（stdExamTable!examTable.action） */
    fun parseExamTable(html: String): List<ExamItem> =
        Jsoup.parse(html).select("table.gridtable tr").drop(1).mapNotNull { tr ->
            val c = tr.select("td").map { it.text().trim() }
            if (c.size < 11) return@mapNotNull null
            ExamItem(
                lessonCode = c[0],
                name = c[1],
                dept = c[2],
                teachClass = c[3],
                credit = c[4].toDoubleOrNull() ?: 0.0,
                examType = c[5],
                date = c[6],
                time = c[7],
                place = c[8],
                seat = c[9],
                status = c[10],
                note = c.getOrElse(11) { "" }
            )
        }
}
