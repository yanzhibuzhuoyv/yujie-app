package com.cqmu.assistant.ui.grade

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cqmu.assistant.data.EamsRepository
import com.cqmu.assistant.data.GradeBook
import com.cqmu.assistant.data.GradeLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GradeUiState(
    val book: GradeBook? = null,
    val loading: Boolean = false,
    val syncing: Boolean = false,
    val error: String? = null,
    val usingFallback: Boolean = false,
    /** false = 只看最新学期；true = 展示全部学期（从新到旧） */
    val showAll: Boolean = false
)

/**
 * 成绩页：首读缓存本地，跨过「最近周日」锚点才后台对齐一次
 *（补录/成绩更正为低频操作），对齐失败保留缓存并明确标注。
 */
class GradeViewModel(private val repo: EamsRepository) : ViewModel() {

    private val _state = MutableStateFlow(GradeUiState())
    val state: StateFlow<GradeUiState> = _state.asStateFlow()
    private var requestGeneration = 0L

    init {
        load()
    }

    fun load() {
        if (_state.value.loading) return
        val ticket = ++requestGeneration
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val cached = runCatching { repo.cachedGradeBook() }.getOrNull()
            if (ticket != requestGeneration) return@launch
            if (cached != null) {
                _state.update { it.copy(book = cached, loading = false) }
            }
            val shouldSync = cached == null || repo.needsGradeSync()
            if (!shouldSync) {
                _state.update { it.copy(loading = false, syncing = false) }
                return@launch
            }
            if (cached == null) _state.update { it.copy(loading = true) }
            else _state.update { it.copy(syncing = true) }
            try {
                val book = repo.fetchGradeBook()
                if (ticket != requestGeneration) return@launch
                _state.update {
                    it.copy(
                        book = book,
                        loading = false,
                        syncing = false,
                        error = null,
                        usingFallback = false
                    )
                }
            } catch (e: Exception) {
                if (cached == null) {
                    _state.update { it.copy(loading = false, syncing = false, error = friendly(e)) }
                } else {
                    _state.update {
                        it.copy(syncing = false, error = friendly(e), usingFallback = true)
                    }
                }
            }
        }
    }

    fun refresh() {
        if (_state.value.loading || _state.value.syncing) return
        val ticket = ++requestGeneration
        _state.update { it.copy(syncing = true, error = null, usingFallback = false) }
        viewModelScope.launch {
            try {
                val book = repo.fetchGradeBook()
                if (ticket != requestGeneration) return@launch
                _state.update {
                    it.copy(
                        book = book,
                        loading = false,
                        syncing = false,
                        error = null,
                        usingFallback = false
                    )
                }
            } catch (e: Exception) {
                if (ticket != requestGeneration) return@launch
                _state.update {
                    it.copy(
                        loading = false,
                        syncing = false,
                        error = friendly(e),
                        usingFallback = it.book != null
                    )
                }
            }
        }
    }

    fun toggleShowAll() = _state.update { it.copy(showAll = !it.showAll) }

    private fun friendly(e: Exception): String {
        return when (e) {
        is com.cqmu.assistant.data.EamsException -> e.message ?: "加载失败"
        else -> "网络异常：${e.message ?: "未知错误"}（请检查校园网/VPN）"
        }
    }

    class Factory(private val repo: EamsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = GradeViewModel(repo) as T
    }
}
