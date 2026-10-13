package com.cqmu.assistant.ui.ekta

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun EktaDetailScreen(
    state: EktaUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit
) {
    val title = state.selectedDimension ?: return
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回二课总览")
                }
                Column(Modifier.weight(1f)) {
                    Text("$title · 得分明细", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "按时间从新到旧排列",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onRetry, enabled = !state.detailBusy) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新$title 明细")
                }
            }
        }

        state.detail?.let { detail ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Text("本分类累计学时", style = MaterialTheme.typography.titleMedium)
                        Text(
                            EktaDetailPresentation.number(detail.totalHours),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "最低要求 ${EktaDetailPresentation.number(detail.minimumHours)} · " +
                                "本学期最低 ${EktaDetailPresentation.number(detail.termMinimumHours)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (state.detailBusy) {
            item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }

        state.detailMessage?.let { message ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            if (state.detailUsingFallback) {
                                "更新失败，当前显示上一次成功明细：$message"
                            } else {
                                message
                            },
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onRetry, enabled = !state.detailBusy) {
                            Text("重新加载")
                        }
                    }
                }
            }
        }

        state.detailUpdatedAtMillis?.let { updatedAt ->
            item {
                Text(
                    "数据更新时间：${EktaDetailPresentation.formatTimestamp(updatedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        val records = state.detail?.records.orEmpty()
        if (!state.detailBusy && state.detail != null && records.isEmpty()) {
            item {
                Text(
                    "该分类暂时没有得分记录",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            }
        }
        itemsIndexed(
            items = records,
            key = { index, record -> "${record.relationId}-${record.timestampMillis}-$index" }
        ) { _, record ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            record.name,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            EktaDetailPresentation.formatHours(record.hours, record.type),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (record.type == 2) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "来源：${EktaDetailPresentation.sourceLabel(record.sourceType)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        EktaDetailPresentation.formatTimestamp(record.timestampMillis),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

internal object EktaDetailPresentation {
    private val chinaZone = ZoneId.of("Asia/Shanghai")
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun formatTimestamp(timestampMillis: Long): String =
        timestampFormatter.format(Instant.ofEpochMilli(timestampMillis).atZone(chinaZone))

    fun formatHours(hours: Double, type: Int): String =
        "${if (type == 2) "-" else "+"}${number(hours)} 学时"

    fun number(value: Double): String =
        BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    fun sourceLabel(sourceType: Int): String = when (sourceType) {
        1 -> "活动"
        2 -> "项目认定"
        3 -> "奖励项"
        4 -> "惩罚项"
        5 -> "志愿实践活动"
        6 -> "答题"
        7 -> "历史活动"
        8 -> "社会实践活动"
        9 -> "青年大学习"
        31, 32 -> "置换"
        else -> "其他来源"
    }
}
