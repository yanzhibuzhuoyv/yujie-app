package com.cqmu.assistant.ui.freeroom

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cqmu.assistant.data.AcademicBuilding
import com.cqmu.assistant.data.EamsRepository
import com.cqmu.assistant.data.FreeRoom
import com.cqmu.assistant.data.FreeRoomLoadTicket
import com.cqmu.assistant.data.FreeRoomQuery
import com.cqmu.assistant.data.FreeRoomQuerySession
import com.cqmu.assistant.data.CurrentTimeNavigation
import com.cqmu.assistant.data.RoomFilter
import com.cqmu.assistant.data.RoomFilterSelection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

/** 某间教室展开后的单块占用信息；occupied=null 表示查询失败/未知 */
data class BlockOccupancy(
    val begin: Int,
    val end: Int,
    val occupied: Boolean?
)

data class FreeRoomUiState(
    val date: LocalDate = CurrentTimeNavigation.campusNow().toLocalDate(),
    val beginPeriod: Int = 1,
    val endPeriod: Int = 18,
    val rooms: List<FreeRoom> = emptyList(),
    /** 当前列表真正对应的已提交条件；null 表示条件已编辑、尚未查询。 */
    val resultQuery: FreeRoomQuery? = null,
    val total: Int = 0,
    val loading: Boolean = false,    // 无缓存，必须联网
    val syncing: Boolean = false,    // 有缓存，后台对齐中
    val loadedOnce: Boolean = false,
    val error: String? = null,
    val usingFallback: Boolean = false,
    val roomFilter: RoomFilterSelection = RoomFilterSelection(),
    // 展开的教室名 -> 各大节占用
    val occupancy: Map<String, List<BlockOccupancy>> = emptyMap(),
    val occupancyLoading: Set<String> = emptySet()
) {
    val visibleRooms: List<FreeRoom>
        get() = RoomFilter.filterByLocation(
            rooms = rooms,
            building = roomFilter.building,
            floor = roomFilter.floor
        )

    val availableFloors: List<Int>
        get() = (RoomFilter.availableFloors(rooms, roomFilter.building) + listOfNotNull(roomFilter.floor))
            .distinct()
            .sorted()
}

class FreeRoomViewModel(private val repo: EamsRepository) : ViewModel() {

    private val _state = MutableStateFlow(FreeRoomUiState())
    val state: StateFlow<FreeRoomUiState> = _state.asStateFlow()

    private var lastQuery: FreeRoomQuery? = null
    private var querySession = FreeRoomQuerySession(queryFromState(_state.value))

    init {
        // 打开板块：缓存优先（每日 5 点锚点后过期），过期才联网对齐
        load(live = false)
    }

    fun setDate(date: LocalDate) = editDraft { it.copy(date = date) }
    fun setPeriodRange(begin: Int, end: Int) = editDraft {
        val safeBegin = begin.coerceIn(1, 18)
        val safeEnd = end.coerceIn(safeBegin, 18)
        it.copy(beginPeriod = safeBegin, endPeriod = safeEnd)
    }

    fun shiftDate(days: Long) = editDraft { it.copy(date = it.date.plusDays(days)) }

    /** 仅筛选当前教务系统结果，不触发查询，也不产生独立缓存。 */
    fun selectBuilding(building: AcademicBuilding?) = _state.update {
        it.copy(roomFilter = it.roomFilter.selectBuilding(building))
    }

    /** 仅筛选当前教务系统结果；null 表示全部楼层。 */
    fun selectFloor(floor: Int?) = _state.update {
        it.copy(roomFilter = it.roomFilter.selectFloor(floor))
    }

    /** 底栏重新进入/点击“现在”：查今天当前小节，课间查下一节。 */
    fun jumpToNow(now: LocalDateTime = CurrentTimeNavigation.campusNow()) {
        val target = CurrentTimeNavigation.freeRoomTarget(now)
        val updated = _state.value.copy(
                date = target.date,
                beginPeriod = target.period,
                endPeriod = target.period
            )
        querySession = querySession.edit(queryFromState(updated))
        lastQuery = null
        _state.value = clearResults(updated)
        load(live = false, supersede = true)
    }

    /** 「查询」按钮：用户显式动作，强制实时拉取并取代未完成的旧查询。 */
    fun search() {
        _state.value = clearResults(_state.value)
        load(live = true, supersede = true)
    }

    /**
     * @param live true=强制联网；false=缓存优先，跨过凌晨 5 点锚点才后台对齐
     */
    private fun load(live: Boolean, supersede: Boolean = false) {
        if (_state.value.loading && !supersede) return
        val s = _state.value
        val currentDraft = queryFromState(s)
        if (querySession.draft != currentDraft) querySession = querySession.edit(currentDraft)
        val (loadingSession, ticket) = querySession.beginLoad()
        querySession = loadingSession
        val query = ticket.query
        lastQuery = query
        _state.update {
            it.copy(loading = true, resultQuery = query, error = null, usingFallback = false)
        }

        viewModelScope.launch {
            // 1) 精确匹配当前条件的上次结果可先显示；是否需要联网仍由 5 点锚点决定。
            val fallback = runCatching {
                repo.cachedFreeRooms(query, allowStale = true)
            }.getOrNull()
            val fresh = if (live) null else runCatching { repo.cachedFreeRooms(query) }.getOrNull()
            if (!querySession.canCommit(ticket)) return@launch
            if (fallback != null) {
                val filtered = RoomFilter.filter(fallback)
                _state.update {
                    it.copy(
                        rooms = filtered,
                        total = filtered.size,
                        loading = false,
                        loadedOnce = true,
                        syncing = fresh == null,
                        usingFallback = false
                    )
                }
            }
            // 2) live / 无缓存 / 跨锚点 → 联网
            if (live || fresh == null) {
                if (fallback != null) _state.update { it.copy(syncing = true) }
                try {
                    val rooms = RoomFilter.filter(repo.fetchFreeRooms(query)).sortedWith(
                        compareBy({ it.building }, { it.name })
                    )
                    if (!querySession.canCommit(ticket)) return@launch
                    _state.update {
                        it.copy(
                            rooms = rooms, total = rooms.size, loading = false,
                            syncing = false, loadedOnce = true, error = null,
                            usingFallback = false
                        )
                    }
                } catch (e: Exception) {
                    if (!querySession.canCommit(ticket)) return@launch
                    if (fallback != null) {
                        _state.update {
                            it.copy(
                                loading = false,
                                syncing = false,
                                loadedOnce = true,
                                error = friendly(e),
                                usingFallback = true
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(loading = false, error = friendly(e), usingFallback = false)
                        }
                    }
                }
            } else {
                if (querySession.canCommit(ticket)) {
                    _state.update { it.copy(loading = false) }
                }
            }
        }
    }

    /** 点击展开/收起某间教室的当天占用 */
    fun toggleOccupancy(room: FreeRoom) {
        val s = _state.value
        if (s.occupancy.containsKey(room.name)) {
            _state.update { it.copy(occupancy = it.occupancy - room.name) }
            return
        }
        if (room.name in s.occupancyLoading) return
        val query = lastQuery ?: return
        val ticket = FreeRoomLoadTicket(querySession.generation, query)
        _state.update { it.copy(occupancyLoading = it.occupancyLoading + room.name) }
        viewModelScope.launch {
            val blocks = runCatching { repo.roomDayOccupancy(query, room.name) }.getOrNull()
            if (!querySession.canCommit(ticket)) return@launch
            _state.update {
                it.copy(
                    occupancyLoading = it.occupancyLoading - room.name,
                    occupancy = if (blocks == null) it.occupancy else it.occupancy + (room.name to blocks.map {
                            (b, occupied) -> BlockOccupancy(b.begin, b.end, occupied)
                    })
                )
            }
        }
    }

    private fun editDraft(transform: (FreeRoomUiState) -> FreeRoomUiState) {
        val updated = transform(_state.value)
        val editedSession = querySession.edit(queryFromState(updated))
        if (editedSession == querySession) return
        querySession = editedSession
        lastQuery = null
        _state.value = clearResults(updated)
    }

    private fun queryFromState(state: FreeRoomUiState): FreeRoomQuery = FreeRoomQuery(
        date = state.date,
        beginPeriod = state.beginPeriod,
        endPeriod = state.endPeriod,
        campusId = "3"
    )

    private fun clearResults(state: FreeRoomUiState): FreeRoomUiState = state.copy(
        rooms = emptyList(),
        resultQuery = null,
        total = 0,
        loading = false,
        syncing = false,
        loadedOnce = false,
        error = null,
        usingFallback = false,
        occupancy = emptyMap(),
        occupancyLoading = emptySet()
    )

    private fun friendly(e: Exception): String {
        return when (e) {
        is com.cqmu.assistant.data.EamsException -> e.message ?: "查询失败"
        else -> "网络异常：${e.message ?: "未知错误"}（请检查校园网/VPN）"
        }
    }

    class Factory(private val repo: EamsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = FreeRoomViewModel(repo) as T
    }
}
