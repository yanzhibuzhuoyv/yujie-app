package com.cqmu.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 解析回归测试只使用手工构造的合成页面，不保存真实用户页面或可逆映射。 */
class EamsParserTest {

    private fun res(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("probe/$name")!!
            .readBytes().toString(Charsets.UTF_8)

    private fun syntheticTimetable() = """
        <script>
        var unitCount = 18;
        actTeacherName.push("教师甲");
        activity = new TaskActivity(actTeacherId.join(','),actTeacherName.join(','),"L001",
            "合成课程甲","C001","合成教室A1-1101","011111110",null,null,"","",
            "理论","","A组","","8-10节","");
        index =1*unitCount+7; index =1*unitCount+8; index =1*unitCount+9;
        actTeacherName.push("教师乙");
        activity = new TaskActivity(actTeacherId.join(','),actTeacherName.join(','),"L001",
            "合成课程甲","C001","合成教室A1-1101","010101010",null,null,"","",
            "实验","","B组","","8-10节","");
        index =1*unitCount+7; index =1*unitCount+8; index =1*unitCount+9;
        actTeacherName.push("教师丙");
        activity = new TaskActivity(actTeacherId.join(','),actTeacherName.join(','),"L002",
            "合成课程乙","C002","合成教室A2-2202","001010100",null,null,"","",
            "理论","","","","1-2节","");
        index =0*unitCount+0; index =0*unitCount+1;
        </script>
    """.trimIndent()

    private fun syntheticGradeHistory() = """
        <table><tr><th>学年度</th><th>学期</th><th>门数</th><th>学分</th><th>平均成绩</th><th>平均学分绩点</th><th>排名</th></tr>
        <tr><td>2099-2100</td><td>1</td><td>3</td><td>6.0</td><td>82</td><td>3.2</td><td>--</td></tr></table>
        <table><tr><th>平均成绩</th><th>平均学分绩点</th><th>专业排名</th><th>统计时间</th></tr>
        <tr><td>82</td><td>3.2</td><td>--</td><td>2100-01-01</td></tr></table>
        <table><tr><th>学期</th><th>课程代码</th><th>课程序号</th><th>课程名称</th><th>课程类别</th><th>课程性质</th><th>学分</th><th>平时</th><th>实验</th><th>期末</th><th>总评</th><th>最终成绩</th><th>绩点</th></tr>
        <tr><td>2099-2100 1</td><td>SYN001</td><td>1</td><td>合成课程甲</td><td>基础</td><td>必修</td><td>1.0</td><td>80</td><td>80</td><td>80</td><td>80</td><td>80</td><td>3.0</td></tr>
        <tr><td>2099-2100 1</td><td>SYN002</td><td>2</td><td>合成课程乙</td><td>基础</td><td>必修</td><td>2.0</td><td>85</td><td>85</td><td>85</td><td>85</td><td>85</td><td>3.5</td></tr>
        <tr><td>2099-2100 1</td><td>SYN003</td><td>3</td><td>合成课程丙</td><td>基础</td><td>选修</td><td>3.0</td><td>78</td><td>78</td><td>78</td><td>78</td><td>78</td><td>2.8</td></tr></table>
    """.trimIndent()

    private fun syntheticExamTable() = """
        <table class="gridtable"><tr><th>课程代码</th><th>课程</th><th>院系</th><th>教学班</th><th>学分</th><th>类型</th><th>日期</th><th>时间</th><th>地点</th><th>座位</th><th>状态</th><th>备注</th></tr>
        <tr><td>SYN001</td><td>合成课程甲</td><td>示例学院</td><td>合成班级甲</td><td>1.0</td><td>期末考试</td><td>2100-01-10</td><td>09:00~10:30</td><td>合成考场A</td><td>01</td><td>正常</td><td></td></tr>
        <tr><td>SYN002</td><td>合成课程乙</td><td>示例学院</td><td>合成班级乙</td><td>2.0</td><td>期中考试</td><td>2100-01-11</td><td>14:00~15:30</td><td>合成考场B</td><td>02</td><td>正常</td><td></td></tr></table>
    """.trimIndent()

    // ---------- 用户信息 ----------

    @Test
    fun `parse user info from home page`() {
        val user = EamsParser.parseUserInfo("""<a href="/eams/security/my.action">演示用户(2024000000)</a>""")
        assertNotNull(user)
        assertEquals("演示用户", user!!.name)
        assertEquals("2024000000", user.studentNo)
    }

    // ---------- 个人课表 ----------

    @Test
    fun `parse std ids from course table page`() {
        val page = """if (courseTableType == "std") addInput(form,"ids","900001");"""
        assertEquals("900001", EamsParser.parseStdIds(page))
    }

    @Test
    fun `parse timetable from synthetic page`() {
        val tt = EamsParser.parseTimetable(syntheticTimetable(), 263)
        assertEquals(263L, tt.semesterId)
        assertEquals(18, tt.unitCount)
        assertEquals(3, tt.courses.size)

        // 同一周、同一时段的理论和实验活动合并；其他周只显示当周实际存在的活动。
        val bioTue = tt.coursesOfWeek(1)
            .single { it.name == "合成课程甲" && it.day == 2 && it.startPeriod == 8 }
        assertEquals(10, bioTue.endPeriod)
        assertEquals("合成教室A1-1101", bioTue.room)
        assertTrue(bioTue.teacher.contains("教师甲") && bioTue.teacher.contains("教师乙"))
        assertTrue(bioTue.type.contains("理论") && bioTue.type.contains("实验"))
        assertTrue(bioTue.groupName.contains("A组") && bioTue.groupName.contains("B组"))
        assertTrue("第 1 周应有课", 1 in bioTue.weeks)
        assertEquals(setOf(1, 3, 5, 7), bioTue.weeks)

        val bioEven = tt.coursesOfWeek(2).single { it.name == "合成课程甲" }
        assertEquals("教师甲", bioEven.teacher)
        assertEquals("理论", bioEven.type)
        assertEquals("A组", bioEven.groupName)
        assertEquals(setOf(2, 4, 6), bioEven.weeks)

        // 所有块必须落在合法网格内
        for (c in tt.courses) {
            assertTrue(c.day in 1..7)
            assertTrue(c.startPeriod in 1..tt.unitCount)
            assertTrue(c.endPeriod in c.startPeriod..tt.unitCount)
            assertTrue(c.weeks.all { it in 1..54 })
            assertTrue(c.name.isNotBlank())
        }
    }

    @Test
    fun `timetable keeps same course blocks with different weeks and rooms independent`() {
        val page = """
            <script>
            var unitCount = 18;
            actTeacherName.push("教师甲");
            activity = new TaskActivity(actTeacherId.join(','),actTeacherName.join(','),"L100",
                "分周课程","C100","合成教室A1-1101","010100",null,null,"","",
                "理论","","A组","","1-2节","");
            index =0*unitCount+0; index =0*unitCount+1;
            actTeacherName.push("教师乙");
            activity = new TaskActivity(actTeacherId.join(','),actTeacherName.join(','),"L100",
                "分周课程","C100","合成教室A2-2202","001010",null,null,"","",
                "实验","","B组","","3-4节","");
            index =0*unitCount+2; index =0*unitCount+3;
            </script>
        """.trimIndent()

        val courses = EamsParser.parseTimetable(page, 263).courses
            .sortedBy { it.startPeriod }

        assertEquals(2, courses.size)
        assertEquals(1..2, courses[0].startPeriod..courses[0].endPeriod)
        assertEquals(setOf(1, 3), courses[0].weeks)
        assertEquals("合成教室A1-1101", courses[0].room)
        assertEquals("教师甲", courses[0].teacher)
        assertEquals("理论", courses[0].type)
        assertEquals("A组", courses[0].groupName)

        assertEquals(3..4, courses[1].startPeriod..courses[1].endPeriod)
        assertEquals(setOf(2, 4), courses[1].weeks)
        assertEquals("合成教室A2-2202", courses[1].room)
        assertEquals("教师乙", courses[1].teacher)
        assertEquals("实验", courses[1].type)
        assertEquals("B组", courses[1].groupName)
    }

    @Test
    fun `timetable keeps mutually exclusive weekly variants in the same slot independent`() {
        val page = """
            <script>
            var unitCount = 18;
            actTeacherName.push("教师甲");
            activity = new TaskActivity(actTeacherId.join(','),actTeacherName.join(','),"L200",
                "单双周课程","C200","合成教室A1-1101","010100",null,null,"","",
                "理论","","单周组","","1-2节","");
            index =0*unitCount+0; index =0*unitCount+1;
            actTeacherName.push("教师乙");
            activity = new TaskActivity(actTeacherId.join(','),actTeacherName.join(','),"L200",
                "单双周课程","C200","合成教室A2-2202","001010",null,null,"","",
                "实验","","双周组","","1-2节","");
            index =0*unitCount+0; index =0*unitCount+1;
            </script>
        """.trimIndent()

        val timetable = EamsParser.parseTimetable(page, 263)
        val odd = timetable.coursesOfWeek(1).single()
        val even = timetable.coursesOfWeek(2).single()

        assertEquals("合成教室A1-1101", odd.room)
        assertEquals("教师甲", odd.teacher)
        assertEquals("理论", odd.type)
        assertEquals("单周组", odd.groupName)
        assertEquals(setOf(1, 3), odd.weeks)

        assertEquals("合成教室A2-2202", even.room)
        assertEquals("教师乙", even.teacher)
        assertEquals("实验", even.type)
        assertEquals("双周组", even.groupName)
        assertEquals(setOf(2, 4), even.weeks)
    }

    @Test
    fun `timetable rejects a non timetable response`() {
        val error = assertThrows(EamsException::class.java) {
            EamsParser.parseTimetable("<html><body>service unavailable</body></html>", 263)
        }

        assertTrue(error.message.orEmpty().contains("课表响应格式异常"))
    }

    @Test
    fun `timetable rejects a partial response with a malformed activity`() {
        val page = """
            <script>
            var unitCount = 18;
            actTeacherName.push("教师甲");
            activity = new TaskActivity(actTeacherId.join(','),actTeacherName.join(','),"L300",
                "完整课程","C300","合成教室A1-1101","0110",null,null,"","",
                "理论","","","","1节","");
            index =0*unitCount+0;
            activity = new TaskActivity(actTeacherId.join(','),actTeacherName.join(','),"L301",
                "残缺课程","C301","合成教室A1-1102","0110"
            </script>
        """.trimIndent()

        val error = assertThrows(EamsException::class.java) {
            EamsParser.parseTimetable(page, 263)
        }

        assertTrue(error.message.orEmpty().contains("课表活动格式异常"))
    }

    @Test
    fun `timetable skips an activity with no active teaching week`() {
        val page = """
            <script>
            var unitCount = 18;
            actTeacherName.push("教师甲");
            activity = new TaskActivity(actTeacherId.join(','),actTeacherName.join(','),"L400",
                "已停排课程","C400","合成教室A1-1101","00000",null,null,"","",
                "理论","","","","1节","");
            index =0*unitCount+0;
            </script>
        """.trimIndent()

        assertTrue(EamsParser.parseTimetable(page, 263).courses.isEmpty())
    }

    @Test
    fun `timetable rejects a missing or expression based week bitmap`() {
        val missing = """
            <script>
            var unitCount = 18;
            activity = new TaskActivity([],[],"L401","缺周次课程","C401","A1-1101",null);
            index =0*unitCount+0;
            </script>
        """.trimIndent()
        val expression = """
            <script>
            var unitCount = 18;
            activity = new TaskActivity([],[],"L402","动态周次课程","C402","A1-1101",weekBitmap);
            index =0*unitCount+0;
            </script>
        """.trimIndent()

        assertTrue(
            assertThrows(EamsException::class.java) {
                EamsParser.parseTimetable(missing, 263)
            }.message.orEmpty().contains("周次异常")
        )
        assertTrue(
            assertThrows(EamsException::class.java) {
                EamsParser.parseTimetable(expression, 263)
            }.message.orEmpty().contains("周次异常")
        )
    }

    @Test
    fun `timetable rejects a week bitmap without the protocol placeholder`() {
        val page = """
            <script>
            var unitCount = 18;
            activity = new TaskActivity([],[],"L403","非法周次课程","C403","A1-1101","1110");
            index =0*unitCount+0;
            </script>
        """.trimIndent()

        assertTrue(
            assertThrows(EamsException::class.java) {
                EamsParser.parseTimetable(page, 263)
            }.message.orEmpty().contains("周次异常")
        )
    }

    @Test
    fun `timetable merges adjacent slots after normalizing to the selected week`() {
        val page = """
            <script>
            var unitCount = 18;
            actTeacherName.push("教师甲");
            activity = new TaskActivity([],[],"L404","连续课程","C404","A1-1101","0100",
                null,null,"","","理论","","A组","","1节","");
            index =0*unitCount+0;
            actTeacherName.push("教师甲");
            activity = new TaskActivity([],[],"L404","连续课程","C404","A1-1101","0110",
                null,null,"","","理论","","A组","","2节","");
            index =0*unitCount+1;
            </script>
        """.trimIndent()

        val course = EamsParser.parseTimetable(page, 263).coursesOfWeek(1).single()

        assertEquals(1, course.startPeriod)
        assertEquals(2, course.endPeriod)
    }

    @Test
    fun `timetable accepts a structured response with no activities`() {
        val timetable = EamsParser.parseTimetable("<script>var unitCount = 18;</script>", 263)

        assertTrue(timetable.courses.isEmpty())
        assertEquals(18, timetable.weekCount)
    }

    // ---------- 学期 ----------

    @Test
    fun `parse semester calendar list`() {
        val list = EamsParser.parseSemesters(res("semcal.json"))
        assertTrue(list.size >= 20)
        assertEquals(283L, list.first().id)
        assertTrue(list.any { it.id == 282L && it.schoolYear == "2026-2027" && it.term == "1" })
        assertTrue(list.any { it.id == 263L && it.schoolYear == "2025-2026" && it.term == "2" })
    }

    @Test
    fun `parse week1 monday from calendar views`() {
        assertEquals("2099-09-07", EamsParser.parseWeek1Monday("第1周 2099-09-07"))
        assertEquals("2100-03-01", EamsParser.parseWeek1Monday("标题 第1周\n2100-03-01"))
        assertEquals(
            "2099-09-07",
            EamsParser.parseWeek1Monday("<table><tr><td>2099-09-07</td><td>第1周</td></tr></table>")
        )
    }

    // ---------- 空闲教室 ----------

    @Test
    fun `parse free room search result`() {
        // 公共教室目录的结构化样本：100 条当前页记录，并带服务端总数。
        val result = EamsParser.parseFreeRooms(res("freeresult2.html"))
        assertEquals(391, result.total)
        assertTrue(result.totalIsExplicit)
        assertEquals(100, result.rooms.size)

        val first = result.rooms.first()
        assertEquals("构想楼B1-1206", first.name)
        assertEquals("构想楼", first.building)
        assertEquals("缙云校区", first.campus)
        assertEquals(0, first.capacity)
        assertTrue(first.notes.contains("教室借用"))

        val computerRoom = result.rooms.first { it.name.contains("计算机房") }
        assertEquals(1, computerRoom.capacity)
        assertTrue(computerRoom.notes.contains("排课"))
    }

    @Test
    fun `free room parser marks missing pageInfo as an untrusted fallback`() {
        val result = EamsParser.parseFreeRooms("<table class='gridtable'><tbody></tbody></table>")

        assertEquals(0, result.total)
        assertFalse(result.totalIsExplicit)
    }

    @Test
    fun `free room parser accepts pageInfo with optional trailing arguments`() {
        val page = """
            <table class='gridtable'><tbody></tbody></table>
            <script>page_grid.pageInfo(1, 100, 391, 'server');</script>
        """.trimIndent()

        val result = EamsParser.parseFreeRooms(page)

        assertEquals(391, result.total)
        assertTrue(result.totalIsExplicit)
    }

    @Test
    fun `free room parser accepts quoted pageInfo numbers and whitespace`() {
        val page = """
            <table class='gridtable'><tbody></tbody></table>
            <script>page_grid.pageInfo ('1', "100", '391');</script>
        """.trimIndent()

        val result = EamsParser.parseFreeRooms(page)

        assertEquals(391, result.total)
        assertTrue(result.totalIsExplicit)
    }

    @Test
    fun `free room parser keeps the v1_6_5 empty result behavior when gridtable is absent`() {
        val result = EamsParser.parseFreeRooms("<html><body>暂无符合条件的教室</body></html>")
        val emptyData = EamsParser.parseFreeRooms("<html><body>没有查询到数据</body></html>")

        assertTrue(result.rooms.isEmpty())
        assertEquals(0, result.total)
        assertFalse(result.totalIsExplicit)
        assertTrue(emptyData.rooms.isEmpty())
    }

    @Test
    fun `free room parser rejects a non-result page instead of overwriting cache with empty data`() {
        val error = assertThrows(EamsException::class.java) {
            EamsParser.parseFreeRooms("<html><body>service unavailable</body></html>")
        }

        assertTrue(error.message.orEmpty().contains("响应格式异常"))
        assertThrows(EamsException::class.java) {
            EamsParser.parseFreeRooms("<html><body>您没有权限访问</body></html>")
        }
        assertThrows(EamsException::class.java) {
            EamsParser.parseFreeRooms("<html><body>服务暂无可用</body></html>")
        }
    }

    @Test
    fun `free room parser rejects malformed data rows`() {
        val page = """
            <table class='gridtable'><tbody><tr><td>1</td><td>残缺教室</td></tr></tbody></table>
        """.trimIndent()

        val error = assertThrows(EamsException::class.java) {
            EamsParser.parseFreeRooms(page)
        }

        assertTrue(error.message.orEmpty().contains("响应行格式异常"))
    }

    @Test
    fun `free room parser keeps the v1_6_5 fallback when pageInfo is malformed`() {
        val page = """
            <table class='gridtable'><tbody></tbody></table>
            <script>page_grid.pageInfo(1, 100, 'invalid');</script>
        """.trimIndent()

        val result = EamsParser.parseFreeRooms(page)

        assertTrue(result.rooms.isEmpty())
        assertEquals(0, result.total)
        assertFalse(result.totalIsExplicit)
    }

    @Test
    fun `free room parser rejects conflicting explicit totals`() {
        val page = """
            <table class='gridtable'><tbody></tbody></table>
            <script>
                first.pageInfo(1, 100, 1);
                second.pageInfo(1, 100, 100);
            </script>
        """.trimIndent()

        val error = assertThrows(EamsException::class.java) {
            EamsParser.parseFreeRooms(page)
        }

        assertTrue(error.message.orEmpty().contains("分页总数冲突"))
    }

    // ---------- 登录页 ----------

    @Test
    fun `parse execution and captcha flag from cas login page`() {
        val page = """<input name="execution" value="${"x".repeat(120)}"><script>var casServerCaptchaShow = false;</script>"""
        val exec = EamsParser.parseExecution(page)
        assertNotNull(exec)
        assertTrue(exec!!.length > 100)
        // false 表示服务端初始状态未要求验证码。
        assertFalse(EamsParser.parseCaptchaRequired(page))
    }

    // ---------- 成绩 / 绩点 ----------

    @Test
    fun `parse grade history with gpa`() {
        val book = EamsParser.parseGradeHistory(syntheticGradeHistory())
        assertEquals(3, book.courses.size)
        assertTrue("绩点列应全部可解析", book.courses.all { it.gradePoint != null })
        assertTrue("学分应可解析", book.courses.all { it.credit > 0 })

        val first = book.courses.first()
        assertEquals("2099-2100 1", first.semester)
        assertEquals("SYN001", first.courseCode)
        assertEquals("80", first.finalScore)
        assertEquals(3.0, first.gradePoint!!, 0.001)
        assertEquals(1.0, first.credit, 0.001)

        // 客户端加权 GPA = Σ(学分×绩点)/Σ学分，应与学校口径量级一致
        val gpa = book.computedGpa()
        assertNotNull(gpa)
        assertTrue("加权绩点应在合理区间", gpa!! in 2.0..4.5)
        // 学校自带的分学期汇总与总体统计（必修+限选+公选口径）
        assertTrue("应有分学期汇总", book.semesterSummaries.isNotEmpty())
        assertNotNull("应有总体统计", book.overall)
        book.overall?.gpa?.toDoubleOrNull()?.let { assertTrue(it in 2.0..4.5) }
    }

    // ---------- 考试 ----------

    @Test
    fun `parse exam batches from exam page`() {
        val page = """<select><option value="1181">期末考试</option><option value="1182">期中考试</option><option value="1183" selected>期末补考</option></select>"""
        val batches = EamsParser.parseExamBatches(page)
        assertEquals(3, batches.size)
        assertTrue(batches.contains(ExamBatch(1181L, "期末考试")))
        assertTrue(batches.contains(ExamBatch(1182L, "期中考试")))
        assertEquals("页面默认选中的批次应被标记", "期末补考", batches.first { it.selected }.name)
    }

    @Test
    fun `parse exam table rows`() {
        val items = EamsParser.parseExamTable(syntheticExamTable())
        assertEquals(2, items.size)
        val first = items.first()
        assertEquals("期末考试", first.examType)
        assertEquals("2100-01-10", first.date)
        assertEquals("09:00~10:30", first.time)
        assertEquals("01", first.seat)
        assertEquals("合成考场A", first.place)
        assertEquals("合成班级甲", first.teachClass)
    }
}
