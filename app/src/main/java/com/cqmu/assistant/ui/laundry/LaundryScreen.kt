package com.cqmu.assistant.ui.laundry

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cqmu.assistant.data.CurrentTimeNavigation
import com.cqmu.assistant.data.laundry.DormArea
import com.cqmu.assistant.data.laundry.LaundryCategory
import com.cqmu.assistant.data.laundry.LaundryDataSource
import com.cqmu.assistant.data.laundry.LaundryDevice
import com.cqmu.assistant.data.laundry.LaundryDeviceState
import com.cqmu.assistant.data.laundry.LaundryLocationStore
import com.cqmu.assistant.data.laundry.LaundryPoint
import com.cqmu.assistant.data.laundry.LaundryTime
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

private val UpdateTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaundryScreen(
    source: LaundryDataSource,
    locationStore: LaundryLocationStore,
    activationToken: Long
) {
    val vm: LaundryViewModel = viewModel(factory = LaundryViewModel.Factory(source, locationStore))
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    BackHandler(enabled = state.selectedPoint != null) { vm.closePoint() }

    LaunchedEffect(activationToken) {
        if (activationToken > 0L) vm.autoRefresh()
    }

    LaunchedEffect(
        lifecycleOwner,
        state.selectedPoint?.positionId,
        state.detailCategory,
        state.category
    ) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(60_000L)
                vm.autoRefresh()
            }
        }
    }

    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = state.isRefreshing ||
            (state.selectedPoint != null && state.isDetailLoading && state.devices.isNotEmpty()),
        onRefresh = vm::refreshActive,
        modifier = Modifier.fillMaxSize()
    ) {
        if (state.selectedPoint == null) {
            LaundryPointList(state, vm)
        } else {
            LaundryDeviceList(state, vm)
        }
    }
}

@Composable
private fun LaundryPointList(state: LaundryUiState, vm: LaundryViewModel) {
    when {
        state.isLoading && state.points.isEmpty() -> LoadingState("正在获取洗衣点实时状态…")
        state.points.isEmpty() && state.error != null -> FullErrorState(state.error, vm::refreshPoints)
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("缙云校区洗衣房", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "数据来自海乐生活 · 约每 60 秒更新",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = vm::refreshPoints, enabled = !state.isRefreshing) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新洗衣点")
                        }
                    }
                    CategoryRow(state.category, LaundryCategory.entries, vm::selectCategory)
                    Spacer(Modifier.height(6.dp))
                    FilterRow(
                        title = "苑区",
                        allSelected = state.filter.area == null,
                        allLabel = "全部",
                        items = state.availableAreas,
                        selected = state.filter.area,
                        label = DormArea::displayName,
                        onSelect = vm::selectArea
                    )
                    if (state.availableBuildings.isNotEmpty()) {
                        FilterRow(
                            title = "楼幢",
                            allSelected = state.filter.building == null,
                            allLabel = "全部",
                            items = state.availableBuildings,
                            selected = state.filter.building,
                            label = { "${it}幢" },
                            onSelect = vm::selectBuilding
                        )
                    }
                    if (state.availableFloors.isNotEmpty()) {
                        FilterRow(
                            title = "楼层",
                            allSelected = state.filter.floor == null,
                            allLabel = "全部",
                            items = state.availableFloors,
                            selected = state.filter.floor,
                            label = { "${it}层" },
                            onSelect = vm::selectFloor
                        )
                    }
                }
            }
            state.error?.let { message ->
                item { InlineError(message, state.updatedAt, vm::refreshPoints) }
            }
            item {
                Text(
                    "${state.category.displayName}洗衣点 ${state.visiblePoints.size} 个" +
                        state.updatedAt?.let { " · ${it.format(UpdateTimeFormat)} 更新" }.orEmpty(),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state.visiblePoints.isEmpty()) {
                item {
                    Text(
                        "当前筛选条件下没有洗衣点",
                        modifier = Modifier.fillMaxWidth().padding(28.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(state.visiblePoints, key = LaundryPoint::positionId) { point ->
                    LaundryPointCard(point, state.category, onClick = { vm.openPoint(point) })
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun LaundryDeviceList(state: LaundryUiState, vm: LaundryViewModel) {
    val point = state.selectedPoint ?: return
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = vm::closePoint) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回洗衣点")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            point.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            state.detailUpdatedAt?.let { "${it.format(UpdateTimeFormat)} 更新" }
                                ?: "正在读取设备状态",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = vm::refreshDevices, enabled = !state.isDetailLoading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新设备状态")
                    }
                }
                CategoryRow(
                    selected = state.detailCategory,
                    categories = point.categories.sortedBy { it.ordinal },
                    onSelect = vm::selectDetailCategory
                )
                Spacer(Modifier.height(8.dp))
                val summary = state.deviceSummary
                Text(
                    "空闲 ${summary.idle} · 占用 ${summary.occupied} · 故障 ${summary.fault}" +
                        if (summary.unknown > 0) " · 未知 ${summary.unknown}" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        state.detailError?.let { message ->
            item { InlineError(message, state.detailUpdatedAt, vm::refreshDevices) }
        }
        if (state.isDetailLoading && state.devices.isEmpty()) {
            item { LoadingRow("正在获取设备状态…") }
        } else if (state.devices.isEmpty() && state.detailError == null) {
            item {
                Text(
                    "该洗衣点暂无${state.detailCategory.displayName}设备",
                    modifier = Modifier.fillMaxWidth().padding(28.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(state.devices, key = LaundryDevice::deviceId) { device ->
                LaundryDeviceCard(device)
            }
        }
        item {
            Text(
                "状态仅供参考，请以设备现场显示为准",
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LaundryPointCard(
    point: LaundryPoint,
    category: LaundryCategory,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(point.name, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    point.idleCount.toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text("台${category.displayName}空闲", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun LaundryDeviceCard(device: LaundryDevice) {
    val color = statusColor(device.state)
    val completion = LaundryTime.completionLabel(
        CurrentTimeNavigation.campusNow(),
        device.finishTime
    )
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f))
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(device.name, fontWeight = FontWeight.SemiBold)
                if (device.floorCode.isNotBlank()) {
                    Text(
                        device.floorCode.takeIf { "F" in it || it == "其它" }
                            ?: "${device.floorCode}F",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(device.state.displayName, color = color, fontWeight = FontWeight.Bold)
                if (device.state == LaundryDeviceState.OCCUPIED && completion != null) {
                    Text(completion, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(
    selected: LaundryCategory,
    categories: List<LaundryCategory>,
    onSelect: (LaundryCategory) -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(categories, key = LaundryCategory::code) { category ->
            FilterChip(
                selected = category == selected,
                onClick = { onSelect(category) },
                label = { Text(category.displayName) }
            )
        }
    }
}

@Composable
private fun <T> FilterRow(
    title: String,
    allSelected: Boolean,
    allLabel: String,
    items: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T?) -> Unit
) {
    Text(title, style = MaterialTheme.typography.labelSmall)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FilterChip(selected = allSelected, onClick = { onSelect(null) }, label = { Text(allLabel) })
        }
        items(items) { item ->
            FilterChip(
                selected = item == selected,
                onClick = { onSelect(item) },
                label = { Text(label(item)) }
            )
        }
    }
}

@Composable
private fun InlineError(message: String, updatedAt: LocalDateTime?, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (updatedAt == null) message else "$message\n仍显示 ${updatedAt.format(UpdateTimeFormat)} 的上次数据",
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun LoadingState(label: String) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LoadingRow(label: String) {
    Row(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(Modifier.width(24.dp).height(24.dp))
        Spacer(Modifier.width(10.dp))
        Text(label)
    }
}

@Composable
private fun FullErrorState(message: String, onRetry: () -> Unit) =
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("洗衣房状态获取失败", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }

@Composable
private fun statusColor(state: LaundryDeviceState): Color = when (state) {
    LaundryDeviceState.IDLE -> Color(0xFF14804A)
    LaundryDeviceState.OCCUPIED -> Color(0xFFE06C00)
    LaundryDeviceState.FAULT -> MaterialTheme.colorScheme.error
    LaundryDeviceState.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}
