package com.cqmu.assistant.ui.exam

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cqmu.assistant.data.EamsRepository
import com.cqmu.assistant.data.ExamDisplayPolicy
import com.cqmu.assistant.data.ExamBatch
import com.cqmu.assistant.data.ExamItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamScreen(repo: EamsRepository) {
    val vm: ExamViewModel = viewModel(factory = ExamViewModel.Factory(repo))
    val state by vm.state.collectAsStateWithLifecycle()
    var showBatchPicker by remember { mutableStateOf(false) }

    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = state.loading || state.syncing,
        onRefresh = { vm.refresh() },
        modifier = Modifier.fillMaxSize()
    ) {
        when {
            state.batches.isEmpty() && state.loading -> Box(
                Modifier.fillMaxSize(), contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            state.batches.isEmpty() -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("考试安排加载失败", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    state.error ?: "未知错误",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { vm.load() }) { Text("重试") }
            }

            else -> Column(Modifier.fillMaxSize()) {
                // 批次选择
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        "批次：",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    BatchDropdown(
                        batches = state.batches,
                        selectedId = state.selectedBatchId,
                        batchTerms = state.batchTerms,
                        onSelected = vm::setBatch
                    )
                }

                if (state.error != null) {
                    Text(
                        if (state.usingFallback) {
                            "更新失败，当前仍显示上一次成功数据：${state.error}"
                        } else {
                            state.error!!
                        },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }

                val items = state.items
                if (items.isEmpty() && !state.loading && !state.syncing) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "该批次下没有你的考试安排",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    Text(
                        when {
                            state.loading -> "正在查询…"
                            state.syncing -> "已显示缓存，后台对齐中…（共 ${items.size} 场）"
                            else -> "共 ${items.size} 场考试"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 12.dp, end = 12.dp, bottom = 16.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(items.size) { idx ->
                            val exam = items[idx]
                            ExamItemCard(exam, state.gradePoints[exam.name])
                        }
                    }
                }
            }
        }
    }

    if (showBatchPicker) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showBatchPicker = false },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showBatchPicker = false }) { Text("关闭") }
            },
            title = { Text("选择考试批次") },
            text = {
                Column(
                    Modifier
                        .padding(vertical = 4.dp)
                ) {
                    state.batches.forEach { b ->
                        Text(
                            b.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (b.id == state.selectedBatchId) FontWeight.Bold else FontWeight.Normal,
                            color = if (b.id == state.selectedBatchId) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.setBatch(b)
                                    showBatchPicker = false
                                }
                                .padding(vertical = 8.dp)
                        )
                    }
                }
            }
        )
    }
}

@Composable
private fun BatchDropdown(
    batches: List<ExamBatch>,
    selectedId: Long,
    batchTerms: Map<Long, String>,
    onSelected: (ExamBatch) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = batches.firstOrNull { it.id == selectedId }
    val selectedLabel = selected?.let { b ->
        batchTerms[b.id]?.let { "${b.name} · $it" } ?: b.name
    } ?: "请选择"
    Box {
        Text(
            selectedLabel,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { expanded = true }
        )
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            batches.forEach { b ->
                val term = batchTerms[b.id]
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(b.name)
                            if (term != null) {
                                Text(
                                    term,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    onClick = {
                        onSelected(b)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ExamItemCard(e: ExamItem, gradePoint: Double?) {
    val finished = ExamDisplayPolicy.isFinished(e.date)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    e.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        e.status.ifBlank { e.examType },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (e.status.contains("正常") || e.status.isBlank())
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                    if (finished) {
                        Text(
                            "已考完",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (finished && gradePoint != null) {
                        Text(
                            String.format(java.util.Locale.US, "绩点 %.1f", gradePoint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${e.date}  ${e.time}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
            }
            if (e.place.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "地点：${e.place}${if (e.seat.isNotBlank()) " · 座位 ${e.seat}" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ChipText("${e.examType}")
                ChipText("${e.credit}学分")
                if (e.note.isNotBlank()) ChipText(e.note)
            }
        }
    }
}

@Composable
private fun ChipText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFF444444),
        modifier = Modifier
            .background(Color(0xFFF0F0F0), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}
