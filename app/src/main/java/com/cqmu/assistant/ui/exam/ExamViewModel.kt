package com.cqmu.assistant.ui.exam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cqmu.assistant.data.EamsRepository
import com.cqmu.assistant.data.ExamBatch
import com.cqmu.assistant.data.ExamItem
import com.cqmu.assistant.data.TermLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExamUiState(
    val batches: List<ExamBatch> = emptyList(),
    val selectedBatchId: Long = 0L,
    val items: List<ExamItem> = emptyList(),
    val loading: Boolean = false,
    val syncing: Boolean = false,
    val error: String? = null,
    val usingFallback: Boolean = false,
    /** batchId -> 由考试日期推算的学年学期（如 "2025-2026学年 第2学期"） */
    val batchTerms: Map<Long, String> = emptyMap(),
    /** 课程名 -> 最新一次绩点（用于已考完的考试展示） */
    val gradePoints: Map<String, Double> = emptyMap()
) {
    val selectedBatch: ExamBatch?
        get() = batches.firstOrNull { it.id == selectedBatchId }
}

/**
 * 考试页：批次列表缓存本地；每个批次的考试安排首读落缓存，
 * 跨过「每日凌晨 5 点」锚点才后台对齐一次（临考调整更及时），失败保留缓存并明确标注。
 */
class ExamViewModel(private val repo: EamsRepository) : ViewModel() {

    private val _state = MutableStateFlow(ExamUiState())
    val state: StateFlow<ExamUiState> = _state.asStateFlow()
    private var requestGeneration = 0L
    private val successfulBatchIds = mutableSetOf<Long>()

    init {
        load()
        loadGradePoints()
    }

    /** 拉成绩册（优先缓存），供"已考完"的考试展示对应绩点 */
    private fun loadGradePoints() {
        viewModelScope.launch {
            val book = runCatching { repo.cachedGradeBook() }.getOrNull()
                ?: runCatching { repo.fetchGradeBook() }.getOrNull()
                ?: return@launch
            val newestFirst = book.courses
                .filter { it.gradePoint != null }
                .sortedByDescending { it.semester }
            _state.update { it.copy(gradePoints = newestFirst.associateBy({ it.name }) { it.gradePoint!! }) }
        }
    }

    fun load() {
        if (_state.value.loading) return
        val ticket = ++requestGeneration
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val batches = repo.examBatches()
                if (!isCurrent(ticket)) return@launch
                if (batches.isEmpty()) {
                    _state.update { it.copy(loading = false, error = "没有可查询的考试批次") }
                    return@launch
                }
                val chosen = batches.firstOrNull { it.selected } ?: batches.first()
                _state.update {
                    it.copy(
                        batches = batches,
                        selectedBatchId = chosen.id,
                        loading = true
                    )
                }
                loadBatch(chosen.id, forceRefresh = false, ticket = ticket)
            } catch (e: Exception) {
                if (!isCurrent(ticket)) return@launch
                _state.update { it.copy(loading = false, error = friendly(e)) }
            }
        }
    }

    fun setBatch(batch: ExamBatch) {
        if (_state.value.selectedBatchId == batch.id) return
        val ticket = ++requestGeneration
        _state.update {
            it.copy(
                selectedBatchId = batch.id,
                items = emptyList(),
                loading = true,
                syncing = false,
                error = null,
                usingFallback = false
            )
        }
        viewModelScope.launch {
            loadBatch(batch.id, forceRefresh = false, ticket = ticket)
        }
    }

    fun refresh() {
        if (_state.value.loading || _state.value.syncing) return
        val id = _state.value.selectedBatchId
        if (id == 0L) return
        val ticket = ++requestGeneration
        val hadSuccessfulData = id in successfulBatchIds
        viewModelScope.launch {
            if (!isCurrent(ticket, id)) return@launch
            _state.update { it.copy(syncing = true, error = null, usingFallback = false) }
            try {
                val items = repo.fetchExamTable(id)
                if (isCurrent(ticket, id)) {
                    successfulBatchIds += id
                    _state.update {
                        it.copy(items = items, syncing = false, usingFallback = false)
                    }
                }
            } catch (e: Exception) {
                if (isCurrent(ticket, id)) {
                    _state.update {
                        it.copy(
                            syncing = false,
                            error = friendly(e),
                            usingFallback = hadSuccessfulData
                        )
                    }
                }
            }
        }
    }

    private suspend fun loadBatch(batchId: Long, forceRefresh: Boolean, ticket: Long) {
        val cached = runCatching { repo.cachedExamTable(batchId) }.getOrNull()
        if (!isCurrent(ticket, batchId)) return
        if (cached != null) {
            successfulBatchIds += batchId
            _state.update { it.copy(items = cached, loading = false) }
        }
        val shouldSync = forceRefresh || cached == null || repo.needsExamSync(batchId)
        if (!shouldSync) {
            if (_state.value.selectedBatchId == batchId) {
                _state.update { it.copy(loading = false, syncing = false) }
            }
            return
        }
        if (cached == null && _state.value.selectedBatchId == batchId) {
            _state.update { it.copy(loading = true) }
        } else {
            _state.update { it.copy(syncing = true) }
        }
        try {
            val items = repo.fetchExamTable(batchId)
            if (isCurrent(ticket, batchId)) {
                successfulBatchIds += batchId
                _state.update {
                    it.copy(
                        items = items,
                        loading = false,
                        syncing = false,
                        error = null,
                        usingFallback = false
                    )
                }
            }
            if (isCurrent(ticket, batchId)) updateTermLabel(batchId, items)
        } catch (e: Exception) {
            if (!isCurrent(ticket, batchId)) return
            if (cached == null) {
                if (_state.value.selectedBatchId == batchId) {
                    _state.update {
                        it.copy(
                            loading = false,
                            syncing = false,
                            error = friendly(e)
                        )
                    }
                }
            } else if (_state.value.selectedBatchId == batchId) {
                _state.update {
                    it.copy(syncing = false, error = friendly(e), usingFallback = true)
                }
            }
        }
    }

    private fun isCurrent(ticket: Long, batchId: Long? = null): Boolean =
        ticket == requestGeneration &&
            (batchId == null || _state.value.selectedBatchId == batchId)

    /** 用考试日期推算该批次所属学年学期，供按学年查看 */
    private fun updateTermLabel(batchId: Long, items: List<ExamItem>) {
        val term = items.mapNotNull { TermLabel.fromDate(it.date) }.firstOrNull() ?: return
        _state.update { it.copy(batchTerms = it.batchTerms + (batchId to term)) }
    }

    private fun friendly(e: Exception): String {
        return when (e) {
        is com.cqmu.assistant.data.EamsException -> e.message ?: "加载失败"
        else -> "网络异常：${e.message ?: "未知错误"}（请检查校园网/VPN）"
        }
    }

    class Factory(private val repo: EamsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ExamViewModel(repo) as T
    }
}
