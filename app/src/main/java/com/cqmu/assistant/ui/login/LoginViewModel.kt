package com.cqmu.assistant.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cqmu.assistant.data.EamsRepository
import com.cqmu.assistant.data.LoginStep
import com.cqmu.assistant.data.UserInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val busy: Boolean = false,
    val showCaptcha: Boolean = false,
    val captchaBytes: ByteArray? = null,
    val message: String? = null,
    val accountMaintenanceUrl: String? = null,
    val loggedInUser: UserInfo? = null
)

internal suspend fun attemptAutomaticLogin(
    challenge: LoginStep.Challenge,
    credentials: Pair<String, String>?,
    submit: suspend (String, String, String) -> LoginStep
): LoginStep? {
    if (credentials == null || challenge.execution.isBlank() || challenge.needCaptcha) return null
    return submit(challenge.execution, credentials.first, credentials.second)
}

internal fun automaticLoginStorageWarning(saved: Boolean): String? = if (saved) null else
    "登录成功，但本机加密存储不可用，暂时无法自动登录"

class LoginViewModel(private val repo: EamsRepository) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    private var execution: String = ""
    private var mfaEnabled: Boolean = false

    /**
     * 清掉上一次登录成功残留的状态。退出登录后重新挂载登录页时必须调用，
     * 否则残留的 loggedInUser 会立刻把用户重新“登录”回去（退出失效的根因）。
     */
    fun clearLoggedInUser() {
        if (_state.value.loggedInUser != null) {
            _state.update { it.copy(loggedInUser = null, busy = false) }
        }
    }

    fun prefillUsername(): String = repo.rememberedCredentials()?.first
        ?: run {
            // 未开记住密码时也回显上次登录的学号
            repo.savedUser()?.studentNo ?: ""
        }

    fun prefillPassword(): String = repo.rememberedCredentials()?.second ?: ""

    /** 打开登录链；若教务会话仍然有效则直接进入主界面 */
    fun start() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null, accountMaintenanceUrl = null) }
        viewModelScope.launch {
            try {
                when (val step = repo.beginLogin()) {
                    is LoginStep.Success -> {
                        _state.update { it.copy(busy = false, loggedInUser = step.user) }
                    }
                    is LoginStep.Challenge -> {
                        execution = step.execution
                        mfaEnabled = step.mfaEnabled
                        val remembered = repo.rememberedCredentials()
                        val automatic = attemptAutomaticLogin(step, remembered) {
                                autoExecution, autoUsername, autoPassword ->
                            repo.submitLogin(
                                autoExecution,
                                autoUsername,
                                autoPassword,
                                null,
                                mfaEnabled
                            )
                        }
                        if (automatic != null) {
                            when (automatic) {
                                is LoginStep.Success -> _state.update {
                                    it.copy(busy = false, loggedInUser = automatic.user)
                                }
                                is LoginStep.Challenge -> {
                                    execution = automatic.execution
                                    mfaEnabled = automatic.mfaEnabled
                                    repo.saveRememberedCredentials("", null, false)
                                    val bytes = if (automatic.needCaptcha) {
                                        runCatching { repo.fetchCaptchaImage() }.getOrNull()
                                    } else null
                                    _state.update {
                                        it.copy(
                                            busy = false,
                                            showCaptcha = automatic.needCaptcha,
                                            captchaBytes = bytes,
                                            message = automatic.message
                                                ?: "自动登录失败，请重新输入账号密码",
                                            accountMaintenanceUrl = automatic.accountMaintenanceUrl
                                        )
                                    }
                                }
                            }
                        } else {
                            val bytes = if (step.needCaptcha) {
                                runCatching { repo.fetchCaptchaImage() }.getOrNull()
                            } else null
                            _state.update {
                                it.copy(
                                    busy = false,
                                    message = step.message,
                                    accountMaintenanceUrl = step.accountMaintenanceUrl,
                                    showCaptcha = step.needCaptcha,
                                    captchaBytes = bytes
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = friendly(e)) }
            }
        }
    }

    fun submit(username: String, password: String, captcha: String) {
        if (_state.value.busy || execution.isBlank()) return
        _state.update { it.copy(busy = true, message = null, accountMaintenanceUrl = null) }
        viewModelScope.launch {
            try {
                when (val step = repo.submitLogin(
                    execution,
                    username,
                    password,
                    captcha.ifBlank { null },
                    mfaEnabled
                )) {
                    is LoginStep.Success -> {
                        val saved = repo.saveRememberedCredentials(
                            username,
                            password,
                            remember = true
                        )
                        _state.update {
                            it.copy(
                                busy = false,
                                message = automaticLoginStorageWarning(saved),
                                loggedInUser = step.user
                            )
                        }
                    }
                    is LoginStep.Challenge -> {
                        execution = step.execution
                        mfaEnabled = step.mfaEnabled
                        val needCaptcha = step.needCaptcha
                        var bytes = _state.value.captchaBytes
                        if (needCaptcha || _state.value.showCaptcha) {
                            bytes = runCatching { repo.fetchCaptchaImage() }.getOrNull() ?: bytes
                        }
                        _state.update {
                            it.copy(
                                busy = false,
                                showCaptcha = needCaptcha || it.showCaptcha,
                                captchaBytes = bytes,
                                message = step.message ?: "登录失败，请核对账号密码后重试" +
                                    (if (it.showCaptcha) "（需输入验证码）" else ""),
                                accountMaintenanceUrl = step.accountMaintenanceUrl
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = friendly(e)) }
            }
        }
    }

    fun refreshCaptcha() {
        viewModelScope.launch {
            _state.update { it.copy(captchaBytes = null) }
            val bytes = runCatching { repo.fetchCaptchaImage() }.getOrNull()
            _state.update { it.copy(captchaBytes = bytes) }
        }
    }

    private fun friendly(e: Exception): String = when (e) {
        is com.cqmu.assistant.data.EamsException -> e.message ?: "请求失败"
        else -> "网络异常：${e.message ?: "未知错误"}（请检查校园网/VPN）"
    }

    class Factory(private val repo: EamsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LoginViewModel(repo) as T
    }
}
