package com.cqmu.assistant.ui.common

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cqmu.assistant.App
import com.cqmu.assistant.PrivacySanitizer
import java.io.File

/** 崩溃报告存取（单文件，覆盖写） */
object CrashReport {
    fun file(context: Context): File = File((context.applicationContext as App).filesDir, "crash_latest.txt")
    fun has(context: Context): Boolean = file(context).let { it.exists() && it.length() > 0 }
    fun read(context: Context): String = runCatching {
        PrivacySanitizer.crashText(file(context).readText())
    }.getOrDefault("")
    fun clear(context: Context) { runCatching { file(context).delete() } }
}

/**
 * 崩溃报告入口（登录页文本按钮版）：
 * 存在崩溃报告时显示一个按钮，点开可查看全文并一键复制发给开发者。
 */
@Composable
fun CrashReportEntry() {
    val context = LocalContext.current
    var show by remember { mutableStateOf(false) }
    var exists by remember { mutableStateOf(CrashReport.has(context)) }
    if (!exists) return

    TextButton(onClick = { show = true }) {
        Text(
            "查看异常报告（点开复制发给我）",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }

    if (show) CrashReportDialog(
        onClose = { exists = CrashReport.has(context); show = false }
    )
}

/** 崩溃报告入口（主界面顶栏图标版） */
@Composable
fun CrashReportIconButton() {
    val context = LocalContext.current
    var show by remember { mutableStateOf(false) }
    var exists by remember { mutableStateOf(CrashReport.has(context)) }
    if (!exists) return
    androidx.compose.material3.IconButton(onClick = { show = true }) {
        androidx.compose.material3.Icon(
            Icons.Outlined.BugReport,
            contentDescription = "异常报告"
        )
    }
    if (show) CrashReportDialog(
        onClose = { exists = CrashReport.has(context); show = false }
    )
}

@Composable
private fun CrashReportDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val text = CrashReport.read(context)
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(text))
                onClose()
            }) { Text("复制并关闭") }
        },
        dismissButton = {
            TextButton(onClick = {
                CrashReport.clear(context)
                onClose()
            }) { Text("清除") }
        },
        title = { Text("上次异常报告") },
        text = {
            Column {
                Text(
                    "复制后发给开发者即可定位问题",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall
                )
                androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text(
                    text,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    )
}

