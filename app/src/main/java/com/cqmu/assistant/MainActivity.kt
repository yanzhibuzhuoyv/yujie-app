package com.cqmu.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cqmu.assistant.data.EamsRepository
import com.cqmu.assistant.data.UserInfo
import com.cqmu.assistant.ui.home.HomeScreen
import com.cqmu.assistant.ui.login.LoginScreen
import com.cqmu.assistant.ui.login.LoginViewModel
import com.cqmu.assistant.ui.theme.AppTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = (application as App).repository
        val ektaRepo = (application as App).ektaRepository
        setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(repo, ektaRepo)
                }
            }
        }
    }
}

@Composable
private fun AppRoot(
    repo: EamsRepository,
    ektaRepo: com.cqmu.assistant.data.ekta.EktaRepository
) {
    var user by remember { mutableStateOf<UserInfo?>(null) }
    var homeNotice by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(true) }
    // 登录 VM 提到顶层：退出时必须在切回登录页【之前】同步清掉残留登录态
    val loginVm: LoginViewModel = viewModel(factory = LoginViewModel.Factory(repo))

    // 启动时先用持久化 Cookie 试探会话；失败则进入登录页
    LaunchedEffect(Unit) {
        user = runCatching { repo.checkSession() }.getOrNull()
        checking = false
    }

    when {
        checking -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        user != null -> HomeScreen(
            user = user!!,
            repo = repo,
            ektaRepo = ektaRepo,
            startupNotice = homeNotice,
            onLogout = {
                // 本地密钥与会话同步先清，退出不依赖远端网络，也不会被进程终止打断。
                ektaRepo.clear()
                repo.clearLocalSession()
                // 关键顺序：先清登录 VM 残留态，再置空用户，
                // 否则登录页挂载瞬间会读到旧 loggedInUser 把会话“复活”
                loginVm.clearLoggedInUser()
                user = null
            }
        )

        else -> {
            // 双保险：万一上面清理时序被改变，这里兜底（幂等）
            androidx.compose.runtime.SideEffect { loginVm.clearLoggedInUser() }
            LoginScreen(viewModel = loginVm) { u, warning ->
                homeNotice = warning
                user = u
            }
        }
    }
}
