package com.cqmu.assistant.ui.grade

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cqmu.assistant.data.EamsRepository
import com.cqmu.assistant.data.GradeBook
import com.cqmu.assistant.data.GradeLine
import com.cqmu.assistant.data.SemesterOrder
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GradeScreen(repo: EamsRepository) {
    val vm: GradeViewModel = viewModel(factory = GradeViewModel.Factory(repo))
    val state by vm.state.collectAsStateWithLifecycle()

    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = state.loading || state.syncing,
        onRefresh = { vm.refresh() },
        modifier = Modifier.fillMaxSize()
    ) {
        when {
            state.book == null && state.loading -> Box(
                Modifier.fillMaxSize(), contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            state.book == null -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("成绩加载失败", style = MaterialTheme.typography.titleMedium)
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

            else -> GradeContent(
                book = state.book!!,
                error = state.error,
                usingFallback = state.usingFallback,
                syncing = state.syncing,
                showAll = state.showAll,
                onToggleShowAll = { vm.toggleShowAll() },
                onRetry = { vm.load() }
            )
        }
    }
}

@Composable
private fun GradeContent(
    book: GradeBook,
    error: String?,
    usingFallback: Boolean,
    syncing: Boolean,
    showAll: Boolean,
    onToggleShowAll: () -> Unit,
    onRetry: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 12.dp, end = 12.dp, bottom = 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ---------- 总览 ----------
        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                )
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("GPA 总览", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = onToggleShowAll) {
                            Text(if (showAll) "收起，只看最新" else "全部展示")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    book.overall?.let { o ->
                        StatRow("学校口径（必修+限选+公选）", null)
                        Spacer(Modifier.height(4.dp))
                        Row {
                            StatCell("平均成绩", o.avgScore, Modifier.weight(1f))
                            StatCell("平均学分绩点", o.gpa, Modifier.weight(1f))
                            StatCell("专业排名", o.rank.ifBlank { "—" }, Modifier.weight(1f))
                        }
                        if (o.statTime.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "统计时间：${o.statTime}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        Spacer(Modifier.height(10.dp))
                    }
                    StatRow("全部课程（客户端按学分加权）", null)
                    Spacer(Modifier.height(4.dp))
                    Row {
                        StatCell("门数", "${book.courses.size}", Modifier.weight(1f))
                        StatCell(
                            "总学分",
                            String.format(Locale.US, "%.1f", book.courses.sumOf { it.credit }),
                            Modifier.weight(1f)
                        )
                        StatCell(
                            "平均绩点",
                            book.computedGpa()?.let { String.format(Locale.US, "%.2f", it) } ?: "—",
                            Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        if (error != null) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (usingFallback) {
                            "更新失败，当前仍显示上一次成功数据：$error"
                        } else {
                            "后台对齐失败：$error"
                        },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onRetry) { Text("重试") }
                }
            }
        }

        // ---------- 分学期（从新到旧；默认只看最新学期） ----------
        val grouped = book.bySemester().sortedWith(
            compareByDescending<Pair<String, List<GradeLine>>> { SemesterOrder.parse(it.first).first }
                .thenByDescending { SemesterOrder.parse(it.first).second }
        )
        val visible = if (showAll) grouped else grouped.take(1)
        if (!showAll && grouped.size > 1) {
            item {
                Text(
                    "仅显示最新学期，共 ${grouped.size} 个学期（点右上「全部展示」查看历史）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        visible.forEach { (semester, courses) ->
            item(key = "head_$semester") {
                val gpa = book.computedGpa(courses)
                val credits = courses.sumOf { it.credit }
                Column(Modifier.padding(top = 8.dp, bottom = 2.dp)) {
                    Text(semester, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${courses.size} 门 · ${String.format(Locale.US, "%.1f", credits)} 学分 · " +
                            (gpa?.let { String.format(Locale.US, "%.2f", it) } ?: "绩点—"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(courses.size) { idx ->
                GradeRow(courses[idx])
            }
        }

        if (syncing) {
            item {
                Text(
                    "后台对齐中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String?) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun GradeRow(g: GradeLine) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    g.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    g.finalScore.ifBlank { "—" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOf(
                        "${g.credit}学分",
                        g.nature,
                        g.gradePoint?.let { "绩点 ${String.format(Locale.US, "%.1f", it)}" } ?: "无绩点"
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
