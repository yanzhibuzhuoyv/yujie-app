package com.cqmu.assistant.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.LocalLaundryService
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.LocalLaundryService
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Reorder
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Stars
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.cqmu.assistant.data.EamsRepository
import com.cqmu.assistant.data.HomeTabOrderPolicy
import com.cqmu.assistant.data.UserInfo
import com.cqmu.assistant.data.ekta.EktaRepository
import com.cqmu.assistant.data.laundry.LaundryRepository
import com.cqmu.assistant.ui.exam.ExamScreen
import com.cqmu.assistant.ui.ekta.EktaScreen
import com.cqmu.assistant.ui.freeroom.FreeRoomScreen
import com.cqmu.assistant.ui.grade.GradeScreen
import com.cqmu.assistant.ui.laundry.LaundryScreen
import com.cqmu.assistant.ui.timetable.TimetableScreen

/** 主界面：六个稳定 index；用户只调整显示顺序，不改变各板块的数据与逻辑。 */
private data class HomeTabSpec(
    val id: Int,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val HOME_TABS = listOf(
    HomeTabSpec(0, "空教室", Icons.Filled.MeetingRoom, Icons.Outlined.MeetingRoom),
    HomeTabSpec(1, "课程表", Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth),
    HomeTabSpec(2, "绩点", Icons.Filled.School, Icons.Outlined.School),
    HomeTabSpec(3, "考试", Icons.AutoMirrored.Filled.EventNote, Icons.AutoMirrored.Outlined.EventNote),
    HomeTabSpec(4, "洗衣房", Icons.Filled.LocalLaundryService, Icons.Outlined.LocalLaundryService),
    HomeTabSpec(5, "二课", Icons.Filled.Stars, Icons.Outlined.Stars)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    user: UserInfo,
    repo: EamsRepository,
    ektaRepo: EktaRepository,
    startupNotice: String? = null,
    onLogout: () -> Unit
) {
    val viewModelStore = remember(user.studentNo) { ViewModelStore() }
    val viewModelStoreOwner = remember(viewModelStore) {
        object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = viewModelStore
        }
    }
    DisposableEffect(viewModelStore) {
        onDispose { viewModelStore.clear() }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides viewModelStoreOwner) {
        HomeContent(user, repo, ektaRepo, startupNotice, onLogout)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(
    user: UserInfo,
    repo: EamsRepository,
    ektaRepo: EktaRepository,
    startupNotice: String?,
    onLogout: () -> Unit
) {
    val laundrySource = remember { LaundryRepository.create() }
    var tabOrder by remember(user.studentNo) { mutableStateOf(repo.homeTabOrder()) }
    // 每次进入主界面都打开用户手动顺序中最左侧的板块。
    var tab by remember(user.studentNo) { mutableIntStateOf(tabOrder.first()) }
    // 一次性 UI 事件，不跨 Activity/进程恢复，避免旋转屏幕后误触发查询。
    var tabActivation by remember { mutableLongStateOf(0L) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showOrderDialog by remember { mutableStateOf(false) }

    fun activateTab(index: Int) {
        tab = index
        tabActivation += 1
    }

    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        startupNotice?.let { snackbar.showSnackbar(it) }
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("退出登录") },
            text = { Text("将清除本机会话与记住的密码，确定退出？") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    onLogout()
                }) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("取消") }
            }
        )
    }

    if (showOrderDialog) {
        HomeTabOrderDialog(
            order = tabOrder,
            onDismiss = { showOrderDialog = false },
            onSave = { next ->
                val normalized = HomeTabOrderPolicy.normalize(next)
                repo.saveHomeTabOrder(normalized)
                tabOrder = normalized
                showOrderDialog = false
            }
        )
    }

    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "${user.name} ${user.studentNo}",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "重庆医科大学 教务在线",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    com.cqmu.assistant.ui.common.CrashReportIconButton()
                    IconButton(onClick = { showOrderDialog = true }) {
                        Icon(Icons.Outlined.Reorder, contentDescription = "调整板块顺序")
                    }
                    IconButton(onClick = { showLogoutDialog = true }) {
                        Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = "退出登录")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                NavigationBar {
                    tabOrder.forEach { tabId ->
                        val spec = HOME_TABS.first { it.id == tabId }
                        NavigationBarItem(
                            selected = tab == tabId,
                            onClick = { activateTab(tabId) },
                            icon = {
                                Icon(
                                    if (tab == tabId) spec.selectedIcon else spec.unselectedIcon,
                                    contentDescription = null
                                )
                            },
                            label = { Text(spec.label) },
                            alwaysShowLabel = false
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> FreeRoomScreen(repo, tabActivation)
                1 -> TimetableScreen(repo, tabActivation)
                2 -> GradeScreen(repo)
                3 -> ExamScreen(repo)
                4 -> LaundryScreen(laundrySource, repo, tabActivation)
                else -> EktaScreen(ektaRepo, user.studentNo)
            }
        }
    }
}

@Composable
private fun HomeTabOrderDialog(
    order: List<Int>,
    onDismiss: () -> Unit,
    onSave: (List<Int>) -> Unit
) {
    var draft by remember(order) { mutableStateOf(order) }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("调整板块顺序") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                Text(
                    "底栏按此顺序从左到右排列；每次进入 App 会直接打开第 1 项。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                draft.forEachIndexed { position, tabId ->
                    val spec = HOME_TABS.first { it.id == tabId }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text("${position + 1}. ${spec.label}", modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = {
                                draft = HomeTabOrderPolicy.move(draft, tabId, -1)
                            },
                            enabled = position > 0
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "${spec.label}向左移动"
                            )
                        }
                        IconButton(
                            onClick = {
                                draft = HomeTabOrderPolicy.move(draft, tabId, 1)
                            },
                            enabled = position < draft.lastIndex
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "${spec.label}向右移动"
                            )
                        }
                    }
                }
                }
            },
            confirmButton = { TextButton(onClick = { onSave(draft) }) { Text("保存") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
        )
    }
}
