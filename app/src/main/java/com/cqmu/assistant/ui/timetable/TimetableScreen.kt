package com.cqmu.assistant.ui.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cqmu.assistant.data.Course
import com.cqmu.assistant.data.CurrentTimeNavigation
import com.cqmu.assistant.data.EamsRepository
import com.cqmu.assistant.data.Semester
import java.time.format.DateTimeFormatter

private val RowHeight = 46.dp
private val DayColWidth = 48.dp
private val PeriodColWidth = 34.dp
private val DayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

private val BlockColors = listOf(
    Color(0xFFB6EFE2), Color(0xFFFFD9C2), Color(0xFFD6E4FF), Color(0xFFFFF3C4),
    Color(0xFFE8DDFF), Color(0xFFFFD6E0), Color(0xFFCDEFF9), Color(0xFFE2F3C6)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen(repo: EamsRepository, activationToken: Long) {
    val vm: TimetableViewModel = viewModel(factory = TimetableViewModel.Factory(repo))
    val state by vm.state.collectAsStateWithLifecycle()

    var detail by remember { mutableStateOf<Course?>(null) }
    var showSemesterPicker by remember { mutableStateOf(false) }
    var focusRequest by remember { mutableLongStateOf(0L) }
    var handledFocusRequest by remember { mutableLongStateOf(0L) }

    androidx.compose.runtime.LaunchedEffect(activationToken) {
        if (activationToken > 0L) {
            vm.jumpToNow()
            focusRequest += 1
        }
    }

    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = state.loading || state.syncing,
        onRefresh = { vm.refresh() },
        modifier = Modifier.fillMaxSize()
    ) {
        Column(Modifier.fillMaxSize()) {

        // ---------- 学期 + 周次工具条 ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SemesterDropdown(
                semesters = state.semesters,
                selected = state.selected,
                onSelected = vm::selectSemester,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { vm.refresh() }) {
                if (state.loading || state.syncing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { vm.setWeek((state.week ?: 1) - 1) }) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "上一周")
            }
            // 左右滑动切换周次
            val dragAcc = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
            val latestState by androidx.compose.runtime.rememberUpdatedState(state)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragAcc.floatValue = 0f },
                            onDragEnd = {
                                if (dragAcc.floatValue > 120f) {
                                    vm.setWeek((latestState.week ?: 1) - 1)
                                } else if (dragAcc.floatValue < -120f) {
                                    vm.setWeek((latestState.week ?: 1) + 1)
                                }
                            }
                        ) { change, amount ->
                            change.consume()
                            dragAcc.floatValue += amount
                        }
                    }
            ) {
                Text("第 ${state.week ?: "-"} 周", style = MaterialTheme.typography.titleMedium)
                state.week1Monday?.let { w1 ->
                    val monday = w1.plusWeeks(((state.week ?: 1) - 1).toLong())
                    Text(
                        "${monday.format(Mdf)} ~ ${monday.plusDays(6).format(Mdf)} · 左右滑动切换周",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            AssistChip(
                onClick = {
                    vm.jumpToNow()
                    focusRequest += 1
                },
                label = { Text(if (state.currentWeek == null) "假期" else "现在·本周${state.currentWeek}") },
                enabled = state.currentWeek != null
            )
            IconButton(onClick = { vm.setWeek((state.week ?: 1) + 1) }) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "下一周")
            }
        }

        if (state.error != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            ) {
                Text(
                    if (state.usingFallback) {
                        "更新失败，当前仍显示上一次成功数据：${state.error}"
                    } else {
                        "加载失败：${state.error}"
                    },
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { vm.load() }) { Text("重试") }
            }
        }
        if (state.currentWeek == null && state.week1Monday != null) {
            Text(
                "当前假期中，显示第 ${state.week} 周内容（学期第1周：${state.week1Monday?.format(Mdf)}）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }

        // ---------- 课表网格 ----------
        val tt = state.timetable
        if (tt == null && state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (tt == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无课表数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val weekCourses = tt.coursesOfWeek(state.week)
            val today = CurrentTimeNavigation.campusNow().toLocalDate()
            val vertical = rememberScrollState()
            val horizontal = rememberScrollState()
            val density = LocalDensity.current
            // 紧凑行：只渲染到全学期最晚有课的节次（整学期口径，行高稳定）
            val rowsShown = (
                tt.courses.maxOfOrNull { it.endPeriod } ?: tt.unitCount
                ).coerceIn(1, tt.unitCount)

            androidx.compose.runtime.LaunchedEffect(focusRequest, vertical.maxValue, rowsShown) {
                if (focusRequest > handledFocusRequest && vertical.maxValue > 0) {
                    val period = CurrentTimeNavigation.periodAtOrNext(
                        CurrentTimeNavigation.campusNow().toLocalTime()
                    ) ?: rowsShown
                    val target = with(density) {
                        (RowHeight * (period.coerceIn(1, rowsShown) - 1)).roundToPx()
                    }
                    vertical.animateScrollTo(target.coerceAtMost(vertical.maxValue))
                    handledFocusRequest = focusRequest
                }
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(vertical)
            ) {
                // 表头
                Row(
                    Modifier
                        .horizontalScroll(horizontal)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                ) {
                    GridCell(width = PeriodColWidth, text = "") {}
                    DayNames.forEachIndexed { i, name ->
                        val date = state.week1Monday?.plusWeeks(((state.week ?: 1) - 1).toLong())
                            ?.plusDays(i.toLong())
                        val isToday = date == today
                        GridCell(
                            width = DayColWidth,
                            text = name,
                            sub = date?.format(Md),
                            highlight = isToday
                        ) {}
                    }
                }

                // 网格主体（节次列 + 7 天列）
                Row(Modifier.horizontalScroll(horizontal)) {
                    // 节次列（节次号 + 参考起始时间）
                    Column {
                        repeat(rowsShown) { r ->
                            Box(
                                Modifier
                                    .width(PeriodColWidth)
                                    .height(RowHeight),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        "${r + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    com.cqmu.assistant.data.PeriodTimes.start(r + 1)?.let { st ->
                                        Text(
                                            st,
                                            fontSize = 7.sp,
                                            lineHeight = 8.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // 每天一列：行分隔线 + 课程块覆盖
                    DayNames.forEachIndexed { dayIdx, _ ->
                        val day = dayIdx + 1
                        Box(
                            Modifier
                                .width(DayColWidth)
                                .height(RowHeight * rowsShown)
                        ) {
                            Column(Modifier.fillMaxSize()) {
                                repeat(rowsShown) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(RowHeight)
                                            .border(
                                                0.5.dp,
                                                MaterialTheme.colorScheme.surfaceVariant,
                                                RoundedCornerShape(0.dp)
                                            )
                                    )
                                }
                            }
                            val dayCourses = weekCourses.filter { it.day == day }
                            dayCourses.forEach { c ->
                                val span = (c.endPeriod - c.startPeriod + 1)
                                    .coerceIn(1, tt.unitCount - c.startPeriod + 1)
                                val color = BlockColors[
                                    (c.lessonId.hashCode().let { if (it == Int.MIN_VALUE) 0 else Math.abs(it) }) %
                                        BlockColors.size
                                ]
                                Box(
                                    modifier = Modifier
                                        .offset(y = RowHeight * (c.startPeriod - 1))
                                        .padding(1.5.dp)
                                        .height(RowHeight * span - 3.dp)
                                        .fillMaxWidth()
                                        .background(color, RoundedCornerShape(6.dp))
                                        .clickable { detail = c }
                                        .padding(3.dp),
                                    contentAlignment = Alignment.TopStart
                                ) {
                                    Column {
                                        Text(
                                            c.name,
                                            fontSize = 9.sp,
                                            lineHeight = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (c.room.isNotBlank()) {
                                            Text(
                                                c.room,
                                                fontSize = 8.sp,
                                                lineHeight = 10.sp,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                color = Color(0xFF444444)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "共 ${weekCourses.size} 门次 · 点击课程查看详情 · 节次时间为参考作息，以学校安排为准",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                )
            }
        }
    }
    }

    detail?.let { c ->
        AlertDialog(
            onDismissRequest = { detail = null },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("关闭") } },
            title = { Text(c.name) },
            text = {
                Column {
                    DetailLine(
                        "时间",
                        "周${"一二三四五六日"[c.day - 1]} 第${c.startPeriod}-${c.endPeriod}节 " +
                            "(${com.cqmu.assistant.data.PeriodTimes.start(c.startPeriod)}-" +
                            "${com.cqmu.assistant.data.PeriodTimes.endMinutes(c.endPeriod)})"
                    )
                    DetailLine("教师", c.teacher.ifBlank { "—" })
                    DetailLine("教室", c.room.ifBlank { "—" })
                    DetailLine("类型", c.type.ifBlank { "—" })
                    if (c.groupName.isNotBlank()) DetailLine("分组", c.groupName)
                    DetailLine(
                        "周次",
                        if (c.weeks.isEmpty()) "每周"
                        else c.weeks.sorted().joinToString("、")
                    )
                }
            }
        )
    }

    if (showSemesterPicker) {
        AlertDialog(
            onDismissRequest = { showSemesterPicker = false },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSemesterPicker = false }) { Text("关闭") }
            },
            title = { Text("选择学期") },
            text = {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .height(360.dp)
                ) {
                    state.semesters.forEach { s ->
                        Surface(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.selectSemester(s)
                                    showSemesterPicker = false
                                }
                                .padding(vertical = 6.dp)
                        ) {
                            Text(
                                s.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (s.id == state.selected?.id)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        )
    }
}

private val Mdf = DateTimeFormatter.ofPattern("M月d日")
private val Md = DateTimeFormatter.ofPattern("M/d")

@Composable
private fun GridCell(
    width: androidx.compose.ui.unit.Dp,
    text: String,
    sub: String? = null,
    highlight: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .width(width)
            .height(if (sub == null) 30.dp else 42.dp)
            .background(
                if (highlight) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else Color.Transparent
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text,
                fontSize = 11.sp,
                fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
                color = if (highlight) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
            if (sub != null) {
                Text(
                    sub,
                    fontSize = 9.sp,
                    color = if (highlight) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp)
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SemesterDropdown(
    semesters: List<Semester>,
    selected: Semester?,
    onSelected: (Semester) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = selected?.displayName ?: "加载中…",
            onValueChange = {},
            readOnly = true,
            label = { Text("学年学期") },
            trailingIcon = { Icon(Icons.Filled.ExpandMore, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium,
            singleLine = true
        )
        Box(
            Modifier
                .matchParentSize()
                .clickable { expanded = true }
        )
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            semesters.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.displayName) },
                    onClick = {
                        onSelected(s)
                        expanded = false
                    }
                )
            }
        }
    }
}
