package com.cqmu.assistant.ui.ekta

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cqmu.assistant.data.ekta.EktaAchievement
import com.cqmu.assistant.data.ekta.EktaAchievementCategoryDetails
import com.cqmu.assistant.data.ekta.EktaAuthenticationException
import com.cqmu.assistant.data.ekta.EktaCachedAchievementDetails
import com.cqmu.assistant.data.ekta.EktaDataSource
import com.cqmu.assistant.data.ekta.EktaCredentialException
import com.cqmu.assistant.data.ekta.EktaException
import com.cqmu.assistant.data.ekta.EktaSessionStorage
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EktaUiState(
    val busy: Boolean = true,
    val showLogin: Boolean = false,
    val account: String = "",
    val achievement: EktaAchievement? = null,
    val message: String? = null,
    val usingFallback: Boolean = false,
    val selectedDimension: String? = null,
    val detailBusy: Boolean = false,
    val detail: EktaAchievementCategoryDetails? = null,
    val detailMessage: String? = null,
    val detailUsingFallback: Boolean = false,
    val detailUpdatedAtMillis: Long? = null
)

class EktaViewModel(
    private val source: EktaDataSource,
    private val storage: EktaSessionStorage,
    private val ownerStudentNo: String
) : ViewModel() {
    private val _state = MutableStateFlow(EktaUiState())
    val state: StateFlow<EktaUiState> = _state.asStateFlow()
    private var activeJob: Job? = null
    private var generation = 0L
    private var detailJob: Job? = null
    private var detailGeneration = 0L
    private var freshDetails: EktaCachedAchievementDetails? = null

    init { restore() }

    fun login(account: String, password: String) {
        val normalized = account.trim()
        if (normalized.isBlank() || normalized.length > 64 || password.isEmpty() || password.length > 256) {
            _state.update { it.copy(message = "请输入有效的账号和密码") }
            return
        }
        val revision = storage.revision()
        launchLatest(revision, showLoginOnError = true) { ticket ->
            _state.value = _state.value.copy(
                busy = true,
                showLogin = true,
                account = normalized,
                message = null,
                usingFallback = false
            )
            val token = source.login(normalized, password)
            requireCurrent(ticket, revision)
            if (!storage.save(ownerStudentNo, normalized, password, token, revision)) {
                requireStorageWrite(revision)
            }
            val result = source.achievement(token)
            requireCurrent(ticket, revision)
            storage.saveAchievement(ownerStudentNo, normalized, result, revision)
            requireCurrent(ticket, revision)
            _state.value = EktaUiState(
                busy = false,
                showLogin = false,
                account = normalized,
                achievement = result,
                usingFallback = false
            )
        }
    }

    fun refresh() {
        val session = storage.loadSession()
        if (session == null || session.ownerStudentNo != ownerStudentNo) {
            if (session != null) storage.clear()
            _state.value = EktaUiState(busy = false, showLogin = true)
            return
        }
        val revision = storage.revision()
        launchLatest(revision) { ticket ->
            fetchWithSingleRelogin(session.account, session.token, ticket, revision)
        }
    }

    fun openDimension(name: String) {
        val supported = _state.value.achievement?.dimensions?.any { it.name == name } == true
        if (!supported) return
        val session = storage.loadSession()
        if (session == null || session.ownerStudentNo != ownerStudentNo) {
            _state.update {
                it.copy(detailBusy = false, detailMessage = "二课登录已失效，请重新登录")
            }
            return
        }
        val revision = storage.revision()
        val available = freshDetails ?: storage.loadAchievementDetails(ownerStudentNo, session.account)
        _state.update {
            it.copy(
                selectedDimension = name,
                detailBusy = freshDetails == null,
                detail = available?.details?.category(name),
                detailMessage = null,
                detailUsingFallback = false,
                detailUpdatedAtMillis = available?.updatedAtMillis
            )
        }
        if (freshDetails != null) return
        launchDetail(revision, name) { ticket ->
            val result = fetchDetailsWithSingleRelogin(
                account = session.account,
                token = session.token,
                ticket = ticket,
                revision = revision
            )
            requireDetailCurrent(ticket, revision)
            val snapshot = EktaCachedAchievementDetails(result, System.currentTimeMillis())
            val cached = storage.saveAchievementDetails(
                ownerStudentNo,
                session.account,
                snapshot,
                revision
            )
            requireDetailCurrent(ticket, revision)
            freshDetails = snapshot
            _state.update {
                if (it.selectedDimension != name) it else it.copy(
                    detailBusy = false,
                    detail = result.category(name),
                    detailMessage = if (cached) null else "明细已更新，但本机加密缓存写入失败",
                    detailUsingFallback = false,
                    detailUpdatedAtMillis = snapshot.updatedAtMillis
                )
            }
        }
    }

    fun refreshDimension() {
        val name = _state.value.selectedDimension ?: return
        freshDetails = null
        openDimension(name)
    }

    fun closeDimension() {
        detailGeneration += 1
        detailJob?.cancel()
        _state.update {
            it.copy(
                selectedDimension = null,
                detailBusy = false,
                detail = null,
                detailMessage = null,
                detailUsingFallback = false,
                detailUpdatedAtMillis = null
            )
        }
    }

    fun logout() {
        generation += 1
        activeJob?.cancel()
        detailGeneration += 1
        detailJob?.cancel()
        freshDetails = null
        storage.clear()
        _state.value = EktaUiState(busy = false, showLogin = true)
    }

    private fun restore() {
        val session = storage.loadSession()
        if (session == null) {
            _state.value = EktaUiState(busy = false, showLogin = true)
            return
        }
        if (session.ownerStudentNo != ownerStudentNo) {
            storage.clear()
            _state.value = EktaUiState(busy = false, showLogin = true)
            return
        }
        val revision = storage.revision()
        val cached = storage.loadAchievement(ownerStudentNo, session.account)
        _state.value = EktaUiState(
            busy = true,
            showLogin = false,
            account = session.account,
            achievement = cached
        )
        launchLatest(revision) { ticket ->
            fetchWithSingleRelogin(session.account, session.token, ticket, revision)
        }
    }

    private suspend fun fetchWithSingleRelogin(
        account: String,
        token: String,
        ticket: Long,
        revision: Long
    ) {
        _state.value = _state.value.copy(
            busy = true,
            showLogin = false,
            account = account,
            message = null,
            usingFallback = false
        )
        try {
            val result = if (token.isBlank()) throw EktaAuthenticationException()
            else source.achievement(token)
            requireCurrent(ticket, revision)
            storage.saveAchievement(ownerStudentNo, account, result, revision)
            requireCurrent(ticket, revision)
            _state.value = _state.value.copy(
                busy = false,
                achievement = result,
                usingFallback = false
            )
        } catch (expired: EktaAuthenticationException) {
            requireCurrent(ticket, revision)
            val credentials = storage.loadCredentials()
            if (credentials == null || credentials.account != account) {
                storage.clearIfRevision(revision)
                _state.value = EktaUiState(
                    busy = false,
                    showLogin = true,
                    account = account,
                    message = expired.message
                )
                return
            }
            val freshToken = try {
                source.login(credentials.account, credentials.password)
            } catch (invalid: EktaCredentialException) {
                requireCurrent(ticket, revision)
                storage.clearIfRevision(revision)
                _state.value = EktaUiState(
                    busy = false,
                    showLogin = true,
                    account = credentials.account,
                    message = invalid.message
                )
                return
            } catch (failure: EktaException) {
                requireCurrent(ticket, revision)
                val previous = _state.value.achievement
                _state.value = if (previous != null) {
                    _state.value.copy(
                        busy = false,
                        showLogin = false,
                        account = credentials.account,
                        message = failure.message,
                        usingFallback = true
                    )
                } else {
                    EktaUiState(
                        busy = false,
                        showLogin = true,
                        account = credentials.account,
                        message = failure.message
                    )
                }
                return
            }
            requireCurrent(ticket, revision)
            if (!storage.updateToken(ownerStudentNo, credentials.account, freshToken, revision)) {
                requireStorageWrite(revision)
            }
            val result = try {
                source.achievement(freshToken)
            } catch (stillExpired: EktaAuthenticationException) {
                requireCurrent(ticket, revision)
                storage.clearIfRevision(revision)
                _state.value = EktaUiState(
                    busy = false,
                    showLogin = true,
                    account = credentials.account,
                    message = stillExpired.message
                )
                return
            }
            requireCurrent(ticket, revision)
            storage.saveAchievement(ownerStudentNo, credentials.account, result, revision)
            requireCurrent(ticket, revision)
            _state.value = EktaUiState(
                busy = false,
                showLogin = false,
                account = credentials.account,
                achievement = result,
                usingFallback = false
            )
        }
    }

    private suspend fun fetchDetailsWithSingleRelogin(
        account: String,
        token: String,
        ticket: Long,
        revision: Long
    ) = try {
        if (token.isBlank()) throw EktaAuthenticationException()
        source.achievementDetails(token)
    } catch (_: EktaAuthenticationException) {
        requireDetailCurrent(ticket, revision)
        val credentials = storage.loadCredentials()
        if (credentials == null || credentials.account != account) {
            throw EktaAuthenticationException()
        }
        val freshToken = source.login(credentials.account, credentials.password)
        requireDetailCurrent(ticket, revision)
        if (!storage.updateToken(ownerStudentNo, credentials.account, freshToken, revision)) {
            requireStorageWrite(revision)
        }
        source.achievementDetails(freshToken)
    }

    private fun launchLatest(
        revision: Long,
        showLoginOnError: Boolean = false,
        block: suspend (Long) -> Unit
    ) {
        val ticket = ++generation
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            try {
                block(ticket)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (ticket != generation || storage.revision() != revision) return@launch
                val authenticationFailure = e is EktaAuthenticationException ||
                    e is EktaCredentialException
                if (authenticationFailure) storage.clearIfRevision(revision)
                val authenticated = !authenticationFailure &&
                    storage.loadSession()?.ownerStudentNo == ownerStudentNo
                _state.update {
                    val keepsPreviousData = !showLoginOnError &&
                        !authenticationFailure && it.achievement != null
                    it.copy(
                        busy = false,
                        showLogin = showLoginOnError || !authenticated,
                        message = friendly(e),
                        usingFallback = keepsPreviousData
                    )
                }
            }
        }
    }

    private fun launchDetail(
        revision: Long,
        name: String,
        block: suspend (Long) -> Unit
    ) {
        val ticket = ++detailGeneration
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            try {
                block(ticket)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (ticket != detailGeneration || storage.revision() != revision) return@launch
                _state.update {
                    if (it.selectedDimension != name) it else it.copy(
                        detailBusy = false,
                        detailMessage = friendly(e),
                        detailUsingFallback = it.detail != null
                    )
                }
            }
        }
    }

    private suspend fun requireCurrent(ticket: Long, revision: Long) {
        currentCoroutineContext().ensureActive()
        if (ticket != generation || storage.revision() != revision) {
            throw CancellationException("stale ekta request")
        }
    }

    private suspend fun requireDetailCurrent(ticket: Long, revision: Long) {
        currentCoroutineContext().ensureActive()
        if (ticket != detailGeneration || storage.revision() != revision) {
            throw CancellationException("stale ekta detail request")
        }
    }

    private fun requireStorageWrite(revision: Long): Nothing {
        if (storage.revision() != revision) {
            throw CancellationException("ekta session was invalidated")
        }
        throw EktaException("本机加密存储不可用，无法保存二课自动登录信息")
    }

    private fun friendly(error: Exception): String = when (error) {
        is EktaException -> error.message ?: "二课请求失败"
        is IOException -> "网络异常，请检查网络后重试"
        else -> "二课请求失败，请稍后重试"
    }

    class Factory(
        private val source: EktaDataSource,
        private val storage: EktaSessionStorage,
        private val ownerStudentNo: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            EktaViewModel(source, storage, ownerStudentNo) as T
    }
}
