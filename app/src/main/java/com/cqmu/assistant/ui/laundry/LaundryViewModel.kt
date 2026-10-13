package com.cqmu.assistant.ui.laundry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cqmu.assistant.data.CurrentTimeNavigation
import com.cqmu.assistant.data.laundry.DormArea
import com.cqmu.assistant.data.laundry.LaundryCategory
import com.cqmu.assistant.data.laundry.LaundryDataSource
import com.cqmu.assistant.data.laundry.LaundryDevice
import com.cqmu.assistant.data.laundry.LaundryDeviceState
import com.cqmu.assistant.data.laundry.LaundryDeviceSummary
import com.cqmu.assistant.data.laundry.LaundryFilterReducer
import com.cqmu.assistant.data.laundry.LaundryFilterSelection
import com.cqmu.assistant.data.laundry.LaundryLastLocation
import com.cqmu.assistant.data.laundry.LaundryLocationStore
import com.cqmu.assistant.data.laundry.LaundryPoint
import com.cqmu.assistant.data.laundry.LaundryPointFilter
import com.cqmu.assistant.data.laundry.LaundryPointQuery
import com.cqmu.assistant.data.laundry.LaundryRefreshSession
import com.cqmu.assistant.data.laundry.toLastLocation
import java.time.LocalDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LaundryUiState(
    val category: LaundryCategory = LaundryCategory.WASHER,
    val points: List<LaundryPoint> = emptyList(),
    val filter: LaundryFilterSelection = LaundryFilterSelection(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val updatedAt: LocalDateTime? = null,
    val selectedPoint: LaundryPoint? = null,
    val detailCategory: LaundryCategory = LaundryCategory.WASHER,
    val devices: List<LaundryDevice> = emptyList(),
    val isDetailLoading: Boolean = false,
    val detailError: String? = null,
    val detailUpdatedAt: LocalDateTime? = null
) {
    val visiblePoints: List<LaundryPoint>
        get() = LaundryPointFilter.filter(points, filter)

    val availableAreas: List<DormArea>
        get() = points.map { it.location.area }.distinct().sortedBy { it.ordinal }

    val availableBuildings: List<Int>
        get() = LaundryPointFilter.buildings(points, filter.area)

    val availableFloors: List<Int>
        get() = LaundryPointFilter.floors(points, filter.area, filter.building)

    val deviceSummary: LaundryDeviceSummary
        get() = LaundryDeviceSummary.from(devices)
}

class LaundryViewModel(
    private val source: LaundryDataSource,
    private val locationStore: LaundryLocationStore = LaundryLocationStore.NONE
) : ViewModel() {
    private val _state = MutableStateFlow(LaundryUiState())
    val state: StateFlow<LaundryUiState> = _state.asStateFlow()

    private var pointSession = LaundryRefreshSession(LaundryPointQuery(LaundryCategory.WASHER))
    private var pointJob: Job? = null
    private var detailJob: Job? = null
    private var detailGeneration = 0L
    private var restoreLastLocationOnNextLoad = true
    private val locationSession = locationStore.currentSession()

    init {
        refreshPoints()
    }

    fun selectCategory(category: LaundryCategory) {
        if (_state.value.category == category) return
        pointSession = pointSession.edit(LaundryPointQuery(category))
        detailGeneration += 1L
        restoreLastLocationOnNextLoad = true
        detailJob?.cancel()
        _state.update {
            it.copy(
                category = category,
                points = emptyList(),
                filter = LaundryFilterSelection(),
                error = null,
                updatedAt = null,
                selectedPoint = null,
                detailCategory = category,
                devices = emptyList(),
                detailError = null,
                detailUpdatedAt = null
            )
        }
        refreshPoints()
    }

    fun selectArea(area: DormArea?) {
        updateFilter { LaundryFilterReducer.selectArea(it, area) }
    }

    fun selectBuilding(building: Int?) {
        updateFilter { LaundryFilterReducer.selectBuilding(it, building) }
    }

    fun selectFloor(floor: Int?) {
        updateFilter { LaundryFilterReducer.selectFloor(it, floor) }
    }

    fun refreshActive() {
        if (_state.value.selectedPoint == null) refreshPoints() else refreshDevices()
    }

    fun autoRefresh() {
        val current = _state.value
        if (current.isLoading || current.isRefreshing || current.isDetailLoading) return
        refreshActive()
    }

    fun openPoint(point: LaundryPoint) {
        if (point.positionId <= 0L) return
        val category = _state.value.category.takeIf { it in point.categories }
            ?: point.categories.firstOrNull()
            ?: return
        _state.update {
            it.copy(
                selectedPoint = point,
                detailCategory = category,
                devices = emptyList(),
                detailError = null,
                detailUpdatedAt = null
            )
        }
        saveLocation(
            LaundryFilterSelection(
                area = point.location.area,
                building = point.location.building,
                floor = point.location.floor
            ).toLastLocation()
        )
        refreshDevices()
    }

    fun closePoint() {
        detailGeneration += 1L
        detailJob?.cancel()
        _state.update {
            it.copy(
                selectedPoint = null,
                devices = emptyList(),
                isDetailLoading = false,
                detailError = null,
                detailUpdatedAt = null
            )
        }
    }

    fun selectDetailCategory(category: LaundryCategory) {
        val current = _state.value
        if (category == current.detailCategory || category !in current.selectedPoint?.categories.orEmpty()) {
            return
        }
        _state.update {
            it.copy(
                detailCategory = category,
                devices = emptyList(),
                detailError = null,
                detailUpdatedAt = null
            )
        }
        refreshDevices()
    }

    fun refreshPoints() {
        val (loadingSession, ticket) = pointSession.beginLoad()
        pointSession = loadingSession
        pointJob?.cancel()
        val hasData = _state.value.points.isNotEmpty()
        _state.update {
            it.copy(
                isLoading = !hasData,
                isRefreshing = hasData,
                error = null
            )
        }
        pointJob = viewModelScope.launch {
            try {
                val points = source.fetchPoints(ticket.query.category)
                if (!pointSession.canCommit(ticket)) return@launch
                val restoration = if (restoreLastLocationOnNextLoad) {
                    restoreLocation(
                        points,
                        ticket.query.category,
                        locationSession?.let(locationStore::lastLocation)
                    )
                } else {
                    null
                }
                restoreLastLocationOnNextLoad = false
                _state.update {
                    it.copy(
                        points = points,
                        filter = restoration?.filter ?: it.filter,
                        isLoading = false,
                        isRefreshing = false,
                        error = null,
                        updatedAt = CurrentTimeNavigation.campusNow()
                    )
                }
                restoration?.point?.let(::openPoint)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!pointSession.canCommit(ticket)) return@launch
                _state.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = error.message ?: "洗衣房状态获取失败"
                    )
                }
            }
        }
    }

    fun refreshDevices() {
        val current = _state.value
        val point = current.selectedPoint ?: return
        val category = current.detailCategory
        val generation = ++detailGeneration
        detailJob?.cancel()
        _state.update { it.copy(isDetailLoading = true, detailError = null) }
        detailJob = viewModelScope.launch {
            try {
                val devices = source.fetchDevices(point.positionId, category).sortedWith(
                    compareBy<LaundryDevice> { stateOrder(it.state) }.thenBy { it.name }
                )
                val latest = _state.value
                if (generation != detailGeneration ||
                    latest.selectedPoint?.positionId != point.positionId ||
                    latest.detailCategory != category
                ) {
                    return@launch
                }
                _state.update {
                    it.copy(
                        devices = devices,
                        isDetailLoading = false,
                        detailError = null,
                        detailUpdatedAt = CurrentTimeNavigation.campusNow()
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation != detailGeneration) return@launch
                _state.update {
                    it.copy(
                        isDetailLoading = false,
                        detailError = error.message ?: "设备状态获取失败"
                    )
                }
            }
        }
    }

    private fun stateOrder(state: LaundryDeviceState): Int = when (state) {
        LaundryDeviceState.IDLE -> 0
        LaundryDeviceState.OCCUPIED -> 1
        LaundryDeviceState.FAULT -> 2
        LaundryDeviceState.UNKNOWN -> 3
    }

    private fun updateFilter(
        reducer: (LaundryFilterSelection) -> LaundryFilterSelection
    ) {
        val next = reducer(_state.value.filter)
        _state.update { it.copy(filter = next) }
        saveLocation(next.toLastLocation())
    }

    private fun saveLocation(location: LaundryLastLocation?) {
        locationSession?.let { locationStore.saveLastLocation(it, location) }
    }

    private fun restoreLocation(
        points: List<LaundryPoint>,
        category: LaundryCategory,
        remembered: LaundryLastLocation?
    ): RestoredLocation? {
        remembered ?: return null
        val categoryPoints = points.filter { category in it.categories }
        val areaPoints = categoryPoints.filter { it.location.area == remembered.area }
        if (areaPoints.isEmpty()) return RestoredLocation(LaundryFilterSelection(), null)
        val areaFilter = LaundryFilterSelection(area = remembered.area)
        val building = remembered.building ?: return RestoredLocation(areaFilter, null)
        val buildingPoints = areaPoints.filter { it.location.building == building }
        if (buildingPoints.isEmpty()) return RestoredLocation(areaFilter, null)
        val buildingFilter = areaFilter.copy(building = building)
        val floor = remembered.floor ?: return RestoredLocation(buildingFilter, null)
        val floorPoints = buildingPoints.filter { it.location.floor == floor }
        if (floorPoints.isEmpty()) return RestoredLocation(buildingFilter, null)
        val floorFilter = buildingFilter.copy(floor = floor)
        return RestoredLocation(
            filter = floorFilter,
            point = floorPoints.singleOrNull()
        )
    }

    private data class RestoredLocation(
        val filter: LaundryFilterSelection,
        val point: LaundryPoint?
    )

    class Factory(
        private val source: LaundryDataSource,
        private val locationStore: LaundryLocationStore = LaundryLocationStore.NONE
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LaundryViewModel(source, locationStore) as T
    }
}
