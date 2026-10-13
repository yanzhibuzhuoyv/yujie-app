package com.cqmu.assistant.ui.freeroom

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cqmu.assistant.data.AcademicBuilding
import com.cqmu.assistant.data.EamsRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 EEEE")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeRoomScreen(repo: EamsRepository, activationToken: Long) {
    val vm: FreeRoomViewModel = viewModel(factory = FreeRoomViewModel.Factory(repo))
    val state by vm.state.collectAsStateWithLifecycle()
    val visibleRooms = state.visibleRooms

    var showDatePicker by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(activationToken) {
        if (activationToken > 0L) vm.jumpToNow()
    }

    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = state.loading || state.syncing,
        onRefresh = { vm.search() },
        modifier = Modifier.fillMaxSize()
    ) {
        Column(Modifier.fillMaxSize()) {

            // ---------- 查询条件 ----------
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "缙云校区 A1 / A2 / A3（已排除教师休息室）",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    // 日期选择行
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.shiftDate(-1) }) {
                            Icon(Icons.Filled.ChevronLeft, contentDescription = "前一天")
                        }
                        Text(
                            text = state.date.format(DateFmt),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { showDatePicker = true },
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        IconButton(onClick = { vm.shiftDate(1) }) {
                            Icon(Icons.Filled.ChevronRight, contentDescription = "后一天")
                        }
                        TextButton(onClick = { vm.jumpToNow() }) {
                            Text("现在")
                        }
                    }

                Spacer(Modifier.height(4.dp))

                // 小节快捷区间
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.beginPeriod == 1 && state.endPeriod == 18,
                        onClick = { vm.setPeriodRange(1, 18) },
                        label = { Text("全天 1-18") }
                    )
                    FilterChip(
                        selected = state.beginPeriod == 1 && state.endPeriod == 5,
                        onClick = { vm.setPeriodRange(1, 5) },
                        label = { Text("1-5节") }
                    )
                    FilterChip(
                        selected = state.beginPeriod == 6 && state.endPeriod == 12,
                        onClick = { vm.setPeriodRange(6, 12) },
                        label = { Text("6-12节") }
                    )
                    FilterChip(
                        selected = state.beginPeriod == 13 && state.endPeriod == 18,
                        onClick = { vm.setPeriodRange(13, 18) },
                        label = { Text("13-18节") }
                    )
                }

                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "小节区间：",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    PickerDropdown(
                        modifier = Modifier.width(96.dp),
                        label = "起",
                        options = (1..18).map { it.toString() to it.toString() },
                        selectedId = state.beginPeriod.toString(),
                        onSelected = { vm.setPeriodRange(it.toIntOrNull() ?: 1, state.endPeriod) },
                        dense = true
                    )
                    Text("—", style = MaterialTheme.typography.bodyMedium)
                    PickerDropdown(
                        modifier = Modifier.width(96.dp),
                        label = "止",
                        options = (1..18).map { it.toString() to it.toString() },
                        selectedId = state.endPeriod.toString(),
                        onSelected = { vm.setPeriodRange(state.beginPeriod, it.toIntOrNull() ?: state.endPeriod) },
                        dense = true
                    )
                    Spacer(Modifier.width(10.dp))
                    Button(onClick = { vm.search() }, enabled = !state.loading) {
                        Text(if (state.loading) "查询中…" else "查询")
                    }
                }

            }
        }

        // ---------- 结果 ----------
        if (state.error != null && !state.usingFallback) {
            ErrorHint(state.error!!, onRetry = { vm.search() })
        } else {
            val resultQuery = state.resultQuery
            val filtered = state.roomFilter.building != null || state.roomFilter.floor != null
            val countLabel = if (filtered) {
                "筛选后 ${visibleRooms.size} / 共 ${state.total} 间"
            } else {
                "共 ${state.total} 间"
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp, end = 12.dp, bottom = 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    RoomLocationFilters(
                        selectedBuilding = state.roomFilter.building,
                        selectedFloor = state.roomFilter.floor,
                        availableFloors = state.availableFloors,
                        onBuildingSelected = vm::selectBuilding,
                        onFloorSelected = vm::selectFloor
                    )
                }

                if (state.usingFallback) {
                    item {
                        Text(
                            "更新失败，当前仍显示上一次成功数据：${state.error}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                when {
                    state.loadedOnce && state.rooms.isEmpty() && !state.loading -> item {
                        EmptyResultHint(
                        "该时段 A1 / A2 / A3 没有空闲教室\n（可能都在上课/考试/被借用，试试其它时段或日期）",
                        )
                    }

                    state.loadedOnce && visibleRooms.isEmpty() && !state.loading && !state.syncing -> item {
                        EmptyResultHint("当前楼栋/楼层没有匹配的空闲教室\n请切换筛选条件")
                    }

                    else -> {
                        item {
                            Text(
                                when {
                        state.loading && state.rooms.isEmpty() -> "正在查询…"
                        state.syncing -> "已显示缓存，后台对齐中…（$countLabel）"
                        resultQuery == null -> "条件已修改，请点击查询"
                        else -> "$countLabel 空闲（${resultQuery.date} 第${resultQuery.beginPeriod}-${resultQuery.endPeriod}节）· 点教室看当天占用"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                            )
                        }
                        items(visibleRooms.size) { idx ->
                            val room = visibleRooms[idx]
                            RoomItem(
                                room = room,
                                blocks = state.occupancy[room.name],
                                loading = room.name in state.occupancyLoading,
                                expanded = state.occupancy.containsKey(room.name),
                                onToggle = { vm.toggleOccupancy(room) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.date
                .atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { ms ->
                        vm.setDate(Instant.ofEpochMilli(ms).atZone(ZoneId.of("UTC")).toLocalDate())
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } }
        ) {
            DatePicker(state = pickerState)
        }
    }
    }
}

@Composable
private fun RoomLocationFilters(
    selectedBuilding: AcademicBuilding?,
    selectedFloor: Int?,
    availableFloors: List<Int>,
    onBuildingSelected: (AcademicBuilding?) -> Unit,
    onFloorSelected: (Int?) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
        )
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text("结果筛选 · 数据每日 5 点失效，查询可实时更新", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Text("楼栋", style = MaterialTheme.typography.labelSmall)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val buildings = listOf<AcademicBuilding?>(null) + AcademicBuilding.entries
                buildings.forEach { building ->
                    FilterChip(
                        selected = selectedBuilding == building,
                        onClick = { onBuildingSelected(building) },
                        label = { Text(building?.name ?: "全部") }
                    )
                }
            }

            Text("楼层（房号第二位数字）", style = MaterialTheme.typography.labelSmall)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val floors = listOf<Int?>(null) + availableFloors
                floors.forEach { floor ->
                    FilterChip(
                        selected = selectedFloor == floor,
                        onClick = { onFloorSelected(floor) },
                        label = { Text(floor?.let { "${it}楼" } ?: "全部楼层") }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyResultHint(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun PickerDropdown(
    modifier: Modifier = Modifier,
    label: String,
    options: List<Pair<String, String>>,
    selectedId: String,
    onSelected: (String) -> Unit,
    dense: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = options.firstOrNull { it.first == selectedId }?.second ?: "请选择"
    Box(modifier) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = if (dense) null else ({ Icon(Icons.Filled.ExpandMore, contentDescription = null) }),
            modifier = Modifier.fillMaxWidth(),
            textStyle = if (dense) MaterialTheme.typography.bodyMedium.copy(
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            ) else MaterialTheme.typography.bodyMedium,
            singleLine = true
        )
        // 透明覆盖层拦截点击，兼容各版本 Material3
        Box(
            Modifier
                .matchParentSize()
                .clickable { expanded = true }
        )
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onSelected(id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun RoomItem(
    room: com.cqmu.assistant.data.FreeRoom,
    blocks: List<BlockOccupancy>?,
    loading: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    room.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${room.capacity}座",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (expanded) "收起 ▲" else "当天占用 ▼",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                listOf(room.building, room.campus, room.facility)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (room.notes.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "占用标注：${room.notes}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(Modifier.height(8.dp))
                when {
                    loading || blocks == null -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                Modifier.size(14.dp), strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "正在查询当天各节占用…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    else -> {
                        val freeCount = blocks.count { it.occupied == false }
                        Text(
                            "当天空闲 $freeCount/${blocks.size} 个大节：" +
                                "绿=空闲，红=有排课/考试/借用",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        // 两行摆 9 个大节块
                        blocks.chunked(5).forEach { rowBlocks ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                rowBlocks.forEach { b ->
                                    val color = when (b.occupied) {
                                        false -> Color(0xFFB6EFE2)   // 空闲
                                        true -> Color(0xFFFFC9C9)    // 占用
                                        null -> Color(0xFFE0E0E0)    // 未知
                                    }
                                    val label = when (b.occupied) {
                                        false -> "空闲"
                                        true -> "占用"
                                        null -> "未知"
                                    }
                                    Text(
                                        "${b.begin}-${b.end}节 $label",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF333333),
                                        modifier = Modifier
                                            .background(color, RoundedCornerShape(6.dp))
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorHint(message: String, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("查询失败", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry) { Text("重试") }
    }
}
