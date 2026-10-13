package com.cqmu.assistant.ui.timetable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cqmu.assistant.data.EamsRepository
import com.cqmu.assistant.data.CurrentTimeNavigation
import com.cqmu.assistant.data.Semester
import com.cqmu.assistant.data.Timetable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class TimetableUiState(
    val semesters: List<Semester> = emptyList(),
    val selected: Semester? = null,
    val timetable: Timetable? = null,
    val week: Int? = 1,
    val currentWeek: Int? = null,     // 今天所处周次；假期为 null
    val week1Monday: LocalDate? = null,
    val loading: Boolean = false,     // 首次加载（无缓存，必须联网）
    val syncing: Boolean = false,     // 每周对齐（后台，缓存可先显示）
    val error: String? = null,
    val usingFallback: Boolean = false
) {
    val weekCount: Int
        get() = timetable?.weekCount ?: 18
}

/**
 * 课表加载策略（类 Redis 的「缓存 + 周对齐」）：
 * - 首次读取后课表原始页面缓存本地，之后打开秒出，不联网；
 * - 只有「跨过了一个周日锚点」（最近一个周日，含今天）才在后台重新同步一次，防止调课；
 * - 用户不开 App 就不产生任何请求；后台对齐失败保留缓存并明确标注，下次再试；
 * - 下拉/按钮手动刷新 = 强制联网并重置同步日。
 */
class TimetableViewModel(private val repo: EamsRepository) : ViewModel() {

    private val _state = MutableStateFlow(TimetableUiState())
    val state: StateFlow<TimetableUiState> = _state.asStateFlow()
    private var requestGeneration = 0L

    init {
        load()
    }

    fun load() {
        if (_state.value.loading || _state.value.syncing) return
        val ticket = ++requestGeneration

        viewModelScope.launch {
            // 1) 零网络：先展示上次查看的课表缓存（无缓存才转圈等网络）
            val last = runCatching { repo.cachedLastTimetable() }.getOrNull()
            if (!isCurrent(ticket)) return@launch
            if (last != null) {
                val (sem, tt) = last
                val w1 = repo.cachedWeek1Monday(sem.id)
                val cw = currentWeekOf(w1, tt)
                _state.update {
                    it.copy(
                        selected = sem,
                        timetable = tt,
                        week1Monday = w1,
                        currentWeek = cw,
                        week = cw ?: 1,
                        loading = false,
                        error = null,
                        usingFallback = false
                    )
                }
            } else {
                _state.update { it.copy(loading = true, error = null) }
            }

            // 2) 后台：学期列表 / 默认学期 / 周锚点 / 每周对齐。
            //    已有缓存展示时，这一步失败保留缓存并明确标注。
            try {
                val semesters = repo.semesters()
                if (!isCurrent(ticket)) return@launch
                val chosen = if (last != null) {
                    semesters.firstOrNull { it.id == last.first.id } ?: last.first
                } else {
                    val def = repo.resolveDefaultSemester()
                    if (!isCurrent(ticket)) return@launch
                    semesters.firstOrNull { it.id == def.id } ?: def
                }
                val w1 = repo.week1Monday(chosen.id)
                if (!isCurrent(ticket)) return@launch
                val cw = currentWeekOf(w1, _state.value.timetable)
                repo.rememberLastSemester(chosen)
                _state.update {
                    it.copy(
                        semesters = semesters,
                        selected = chosen,
                        week1Monday = w1,
                        currentWeek = cw,
                        week = it.week.takeIf { w -> last != null } ?: (cw ?: 1)
                    )
                }
                loadSemesterData(chosen.id, forceRefresh = false, ticket = ticket)
            } catch (e: Exception) {
                if (!isCurrent(ticket)) return@launch
                if (last == null) {
                    _state.update { it.copy(loading = false, error = friendly(e)) }
                } else {
                    _state.update {
                        it.copy(
                            loading = false,
                            syncing = false,
                            error = friendly(e),
                            usingFallback = true
                        )
                    }
                }
            }
        }
    }

    fun selectSemester(semester: Semester) {
        if (_state.value.selected?.id == semester.id) return
        val ticket = ++requestGeneration
        repo.setSemesterCookie(semester.id)
        repo.rememberLastSemester(semester)
        _state.update {
            it.copy(
                selected = semester,
                timetable = null,
                loading = true,
                error = null,
                usingFallback = false,
                week = 1,
                currentWeek = null
            )
        }
        viewModelScope.launch {
            try {
                val w1 = repo.week1Monday(semester.id)
                if (!isCurrent(ticket, semester.id)) return@launch
                _state.update {
                    it.copy(week1Monday = w1, currentWeek = currentWeekOf(w1), week = currentWeekOf(w1) ?: 1)
                }
                loadSemesterData(semester.id, forceRefresh = false, ticket = ticket)
            } catch (e: Exception) {
                if (!isCurrent(ticket, semester.id)) return@launch
                _state.update { it.copy(loading = false, error = friendly(e)) }
            }
        }
    }

    fun setWeek(week: Int) {
        _state.update { it.copy(week = week.coerceIn(1, it.weekCount)) }
    }

    fun jumpToNow(now: java.time.LocalDateTime = CurrentTimeNavigation.campusNow()) {
        _state.update {
            val current = it.week1Monday?.let { monday ->
                CurrentTimeNavigation.semesterWeek(monday, now.toLocalDate(), it.weekCount)
            }
            it.copy(currentWeek = current, week = current ?: it.week)
        }
    }

    fun refresh() {
        if (_state.value.loading || _state.value.syncing) return
        val id = _state.value.selected?.id ?: return
        val ticket = ++requestGeneration
        _state.update { it.copy(error = null, usingFallback = false) }
        viewModelScope.launch {
            loadSemesterData(id, forceRefresh = true, ticket = ticket)
        }
    }

    /** 缓存优先秒出；仅当强制刷新 / 无缓存 / 跨过周日锚点时才后台联网对齐 */
    private suspend fun loadSemesterData(
        semesterId: Long,
        forceRefresh: Boolean,
        ticket: Long
    ) {
        val cached = runCatching { repo.cachedTimetable(semesterId) }.getOrNull()
        if (!isCurrent(ticket, semesterId)) return
        if (cached != null) {
            val current = currentWeekOf(_state.value.week1Monday, cached)
            _state.update {
                val firstData = it.timetable == null
                it.copy(
                    timetable = cached,
                    loading = false,
                    currentWeek = current,
                    week = if (firstData) current ?: it.week else it.week
                )
            }
        }
        val shouldSync = forceRefresh || cached == null || repo.needsWeeklySync(semesterId)
        if (!shouldSync) {
            _state.update { it.copy(loading = false, syncing = false) }
            return
        }
        if (cached == null) {
            _state.update { it.copy(loading = true) }
        } else {
            _state.update { it.copy(syncing = true) }
        }
        try {
            val tt = repo.fetchTimetable(semesterId)
            if (!isCurrent(ticket, semesterId)) return
            val current = currentWeekOf(_state.value.week1Monday, tt)
            _state.update {
                val firstData = it.timetable == null
                it.copy(
                    timetable = tt,
                    loading = false,
                    syncing = false,
                    error = null,
                    usingFallback = false,
                    currentWeek = current,
                    week = if (firstData) current ?: it.week else it.week
                )
            }
        } catch (e: Exception) {
            if (!isCurrent(ticket, semesterId)) return
            if (cached == null) {
                _state.update { it.copy(loading = false, syncing = false, error = friendly(e)) }
            } else {
                _state.update {
                    it.copy(syncing = false, error = friendly(e), usingFallback = true)
                }
            }
        }
    }

    private fun isCurrent(ticket: Long, semesterId: Long? = null): Boolean =
        ticket == requestGeneration &&
            (semesterId == null || _state.value.selected?.id == semesterId)

    private fun currentWeekOf(week1Monday: LocalDate?, timetable: Timetable? = null): Int? {
        week1Monday ?: return null
        return CurrentTimeNavigation.semesterWeek(
            week1Monday,
            CurrentTimeNavigation.campusNow().toLocalDate(),
            maxWeeks = timetable?.weekCount ?: 18
        )
    }

    private fun friendly(e: Exception): String {
        return when (e) {
        is com.cqmu.assistant.data.EamsException -> e.message ?: "加载失败"
        else -> "网络异常：${e.message ?: "未知错误"}（请检查校园网/VPN）"
        }
    }

    class Factory(private val repo: EamsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TimetableViewModel(repo) as T
    }
}
