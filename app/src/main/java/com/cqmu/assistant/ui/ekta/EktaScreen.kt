package com.cqmu.assistant.ui.ekta

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cqmu.assistant.data.ekta.EktaRepository
import java.math.BigDecimal

private const val OFFICIAL_ACTIVITY_URL = "https://ekta.cqmu.edu.cn/#/AllActivity"

@Composable
fun EktaScreen(repository: EktaRepository, ownerStudentNo: String) {
    val vm: EktaViewModel = viewModel(
        key = "ekta-$ownerStudentNo-${repository.revision()}",
        factory = EktaViewModel.Factory(repository, repository, ownerStudentNo)
    )
    val state by vm.state.collectAsStateWithLifecycle()

    if (state.showLogin) {
        EktaLogin(
            initialAccount = state.account.ifBlank { ownerStudentNo },
            busy = state.busy,
            message = state.message,
            onLogin = vm::login
        )
    } else if (state.selectedDimension != null) {
        BackHandler(onBack = vm::closeDimension)
        EktaDetailScreen(
            state = state,
            onBack = vm::closeDimension,
            onRetry = vm::refreshDimension
        )
    } else {
        EktaDashboard(
            state = state,
            onRefresh = vm::refresh,
            onLogout = vm::logout,
            onDimensionClick = vm::openDimension
        )
    }
}

@Composable
private fun EktaLogin(
    initialAccount: String,
    busy: Boolean,
    message: String?,
    onLogin: (String, String) -> Unit
) {
    var account by remember(initialAccount) { mutableStateOf(initialAccount) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("登录二课", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "使用二课平台账号登录，与教务登录相互独立。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(18.dp))
                OutlinedTextField(
                    value = account,
                    onValueChange = { account = it },
                    label = { Text("二课账号") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("二课密码") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = if (showPassword) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Filled.VisibilityOff
                                else Icons.Filled.Visibility,
                                contentDescription = if (showPassword) "隐藏密码" else "显示密码"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "首次成功后将自动登录；账号、密码和令牌仅在本机加密保存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onLogin(account, password) },
                    enabled = !busy && account.isNotBlank() && password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (busy) CircularProgressIndicator(
                        modifier = Modifier.width(22.dp).height(22.dp),
                        strokeWidth = 2.dp
                    ) else Text("登录")
                }
            }
        }
    }
}

@Composable
private fun EktaDashboard(
    state: EktaUiState,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    onDimensionClick: (String) -> Unit
) {
    val uriHandler = LocalUriHandler.current
    var linkError by remember { mutableStateOf<String?>(null) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("第二课堂", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        state.account,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onRefresh, enabled = !state.busy) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新二课成绩")
                }
                TextButton(onClick = onLogout, enabled = !state.busy) { Text("退出二课") }
            }
        }

        if (state.busy && state.achievement == null) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }

        state.achievement?.let { achievement ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text("获得成绩", style = MaterialTheme.typography.titleMedium)
                        Text(
                            formatScore(achievement.totalScore),
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("总学时", style = MaterialTheme.typography.titleMedium)
                        Text(
                            achievement.totalHours?.let(::formatScore)
                                ?: "暂不可用（旧缓存）",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            items(achievement.dimensions, key = { it.name }) { dimension ->
                Card(
                    onClick = { onDimensionClick(dimension.name) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            dimension.name,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            formatScore(dimension.score),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = "查看${dimension.name}得分明细"
                        )
                    }
                }
            }
        }

        state.message?.let { message ->
            item {
                Text(
                    if (state.usingFallback) {
                        "更新失败，当前仍显示上一次成功数据：$message"
                    } else {
                        message
                    },
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        item {
            OutlinedButton(
                onClick = {
                    linkError = runCatching { uriHandler.openUri(OFFICIAL_ACTIVITY_URL) }
                        .exceptionOrNull()
                        ?.let { "无法打开官方二课网页，请检查是否已安装浏览器" }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("前往官方网页查看并报名活动")
            }
        }
        linkError?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error) }
        }
        item {
            Text(
                "报名操作仅在重庆医科大学二课官方网页完成，本应用不会代替你提交报名。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 20.dp)
            )
        }
    }
}

private fun formatScore(value: Double): String =
    BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
