package com.cqmu.assistant.ui.laundry

import com.cqmu.assistant.data.laundry.DormLocation
import com.cqmu.assistant.data.laundry.DormArea
import com.cqmu.assistant.data.laundry.LaundryCategory
import com.cqmu.assistant.data.laundry.LaundryDataSource
import com.cqmu.assistant.data.laundry.LaundryDevice
import com.cqmu.assistant.data.laundry.LaundryDeviceState
import com.cqmu.assistant.data.laundry.LaundryLastLocation
import com.cqmu.assistant.data.laundry.LaundryLocationSession
import com.cqmu.assistant.data.laundry.LaundryLocationStore
import com.cqmu.assistant.data.laundry.LaundryPoint
import com.cqmu.assistant.data.laundry.toLastLocation
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LaundryViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `category switch cancels old load and commits only latest response`() = runTest(dispatcher) {
        val source = object : LaundryDataSource {
            override suspend fun fetchPoints(category: LaundryCategory): List<LaundryPoint> {
                if (category == LaundryCategory.WASHER) delay(10_000L)
                return listOf(point(category.ordinal + 1L, category))
            }

            override suspend fun fetchDevices(
                positionId: Long,
                category: LaundryCategory
            ): List<LaundryDevice> = emptyList()
        }
        val vm = LaundryViewModel(source)
        runCurrent()

        vm.selectCategory(LaundryCategory.DRYER)
        advanceUntilIdle()

        assertEquals(LaundryCategory.DRYER, vm.state.value.category)
        assertEquals(listOf(3L), vm.state.value.points.map { it.positionId })
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun `refresh error keeps last successful point snapshot`() = runTest(dispatcher) {
        var calls = 0
        val source = object : LaundryDataSource {
            override suspend fun fetchPoints(category: LaundryCategory): List<LaundryPoint> {
                calls += 1
                if (calls > 1) throw IOException("temporary outage")
                return listOf(point(1, category))
            }

            override suspend fun fetchDevices(
                positionId: Long,
                category: LaundryCategory
            ): List<LaundryDevice> = emptyList()
        }
        val vm = LaundryViewModel(source)
        advanceUntilIdle()
        vm.refreshPoints()
        advanceUntilIdle()

        assertEquals(listOf(1L), vm.state.value.points.map { it.positionId })
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.isRefreshing)
    }

    @Test
    fun `last full location opens matching point after live list loads`() = runTest(dispatcher) {
        var devicePositionId: Long? = null
        val source = object : LaundryDataSource {
            override suspend fun fetchPoints(category: LaundryCategory): List<LaundryPoint> = listOf(
                point(1, category, DormArea.GUI, building = 1, floor = 1),
                point(2, category, DormArea.MEI, building = 2, floor = 3)
            )

            override suspend fun fetchDevices(
                positionId: Long,
                category: LaundryCategory
            ): List<LaundryDevice> {
                devicePositionId = positionId
                return listOf(device(1))
            }
        }
        val locations = MemoryLocationStore(LaundryLastLocation(DormArea.MEI, 2, 3))
        val vm = LaundryViewModel(source, locations)

        advanceUntilIdle()

        assertEquals(LaundryLastLocation(DormArea.MEI, 2, 3), vm.state.value.filter.toLastLocation())
        assertEquals(2L, vm.state.value.selectedPoint?.positionId)
        assertEquals(2L, devicePositionId)
        assertEquals(listOf(1L), vm.state.value.devices.map { it.deviceId })
    }

    @Test
    fun `last building location restores filters without opening a floor`() = runTest(dispatcher) {
        var deviceCalls = 0
        val source = object : LaundryDataSource {
            override suspend fun fetchPoints(category: LaundryCategory): List<LaundryPoint> = listOf(
                point(1, category, DormArea.MEI, building = 2, floor = 3),
                point(2, category, DormArea.MEI, building = 2, floor = 4),
                point(3, category, DormArea.GUI, building = 1, floor = 1)
            )

            override suspend fun fetchDevices(
                positionId: Long,
                category: LaundryCategory
            ): List<LaundryDevice> {
                deviceCalls += 1
                return emptyList()
            }
        }
        val locations = MemoryLocationStore(LaundryLastLocation(DormArea.MEI, 2, null))
        val vm = LaundryViewModel(source, locations)

        advanceUntilIdle()

        assertEquals(LaundryLastLocation(DormArea.MEI, 2, null), vm.state.value.filter.toLastLocation())
        assertEquals(listOf(1L, 2L), vm.state.value.visiblePoints.map { it.positionId })
        assertEquals(null, vm.state.value.selectedPoint)
        assertEquals(0, deviceCalls)
    }

    @Test
    fun `missing remembered floor safely falls back to its building`() = runTest(dispatcher) {
        val source = object : LaundryDataSource {
            override suspend fun fetchPoints(category: LaundryCategory): List<LaundryPoint> = listOf(
                point(1, category, DormArea.MEI, building = 2, floor = 4),
                point(2, category, DormArea.GUI, building = 1, floor = 1)
            )

            override suspend fun fetchDevices(
                positionId: Long,
                category: LaundryCategory
            ): List<LaundryDevice> = emptyList()
        }
        val locations = MemoryLocationStore(LaundryLastLocation(DormArea.MEI, 2, 3))
        val vm = LaundryViewModel(source, locations)

        advanceUntilIdle()

        assertEquals(LaundryLastLocation(DormArea.MEI, 2, null), vm.state.value.filter.toLastLocation())
        assertEquals(listOf(1L), vm.state.value.visiblePoints.map { it.positionId })
        assertEquals(null, vm.state.value.selectedPoint)
        assertEquals(LaundryLastLocation(DormArea.MEI, 2, 3), locations.location)
    }

    @Test
    fun `manual hierarchy selections replace remembered location`() = runTest(dispatcher) {
        val source = object : LaundryDataSource {
            override suspend fun fetchPoints(category: LaundryCategory): List<LaundryPoint> = listOf(
                point(1, category, DormArea.MEI, building = 2, floor = 3)
            )

            override suspend fun fetchDevices(
                positionId: Long,
                category: LaundryCategory
            ): List<LaundryDevice> = emptyList()
        }
        val locations = MemoryLocationStore()
        val vm = LaundryViewModel(source, locations)
        advanceUntilIdle()

        vm.selectArea(DormArea.MEI)
        assertEquals(LaundryLastLocation(DormArea.MEI, null, null), locations.location)
        vm.selectBuilding(2)
        assertEquals(LaundryLastLocation(DormArea.MEI, 2, null), locations.location)
        vm.selectFloor(3)
        assertEquals(LaundryLastLocation(DormArea.MEI, 2, 3), locations.location)
        vm.selectArea(null)
        assertEquals(null, locations.location)
    }

    @Test
    fun `stale view model cannot write after authenticated session changes`() = runTest(dispatcher) {
        val source = object : LaundryDataSource {
            override suspend fun fetchPoints(category: LaundryCategory): List<LaundryPoint> = listOf(
                point(1, category, DormArea.GUI, building = 1, floor = 1)
            )

            override suspend fun fetchDevices(
                positionId: Long,
                category: LaundryCategory
            ): List<LaundryDevice> = emptyList()
        }
        val locations = MemoryLocationStore(LaundryLastLocation(DormArea.GUI, 1, null))
        val vm = LaundryViewModel(source, locations)
        advanceUntilIdle()

        locations.revision += 1L
        vm.selectArea(DormArea.MEI)

        assertEquals(LaundryLastLocation(DormArea.GUI, 1, null), locations.location)
    }

    @Test
    fun `duplicate points on remembered floor do not open an arbitrary detail`() = runTest(dispatcher) {
        var deviceCalls = 0
        val source = object : LaundryDataSource {
            override suspend fun fetchPoints(category: LaundryCategory): List<LaundryPoint> = listOf(
                point(1, category, DormArea.MEI, building = 2, floor = 3),
                point(2, category, DormArea.MEI, building = 2, floor = 3)
            )

            override suspend fun fetchDevices(
                positionId: Long,
                category: LaundryCategory
            ): List<LaundryDevice> {
                deviceCalls += 1
                return emptyList()
            }
        }
        val vm = LaundryViewModel(
            source,
            MemoryLocationStore(LaundryLastLocation(DormArea.MEI, 2, 3))
        )

        advanceUntilIdle()

        assertEquals(LaundryLastLocation(DormArea.MEI, 2, 3), vm.state.value.filter.toLastLocation())
        assertEquals(null, vm.state.value.selectedPoint)
        assertEquals(0, deviceCalls)
    }

    @Test
    fun `failed first load keeps restoration pending until retry succeeds`() = runTest(dispatcher) {
        var calls = 0
        val source = object : LaundryDataSource {
            override suspend fun fetchPoints(category: LaundryCategory): List<LaundryPoint> {
                calls += 1
                if (calls == 1) throw IOException("temporary outage")
                return listOf(point(2, category, DormArea.MEI, building = 2, floor = 3))
            }

            override suspend fun fetchDevices(
                positionId: Long,
                category: LaundryCategory
            ): List<LaundryDevice> = listOf(device(1))
        }
        val vm = LaundryViewModel(
            source,
            MemoryLocationStore(LaundryLastLocation(DormArea.MEI, 2, 3))
        )
        advanceUntilIdle()

        assertEquals(null, vm.state.value.selectedPoint)
        vm.refreshPoints()
        advanceUntilIdle()

        assertEquals(2L, vm.state.value.selectedPoint?.positionId)
        assertEquals(listOf(1L), vm.state.value.devices.map { it.deviceId })
    }

    @Test
    fun `closing auto opened detail prevents ordinary refresh from reopening it`() = runTest(dispatcher) {
        val source = object : LaundryDataSource {
            override suspend fun fetchPoints(category: LaundryCategory): List<LaundryPoint> = listOf(
                point(2, category, DormArea.MEI, building = 2, floor = 3)
            )

            override suspend fun fetchDevices(
                positionId: Long,
                category: LaundryCategory
            ): List<LaundryDevice> = emptyList()
        }
        val vm = LaundryViewModel(
            source,
            MemoryLocationStore(LaundryLastLocation(DormArea.MEI, 2, 3))
        )
        advanceUntilIdle()
        vm.closePoint()

        vm.refreshPoints()
        advanceUntilIdle()

        assertEquals(null, vm.state.value.selectedPoint)
        assertEquals(LaundryLastLocation(DormArea.MEI, 2, 3), vm.state.value.filter.toLastLocation())
    }

    @Test
    fun `category switch reapplies full location and opens the matching point`() = runTest(dispatcher) {
        val source = object : LaundryDataSource {
            override suspend fun fetchPoints(category: LaundryCategory): List<LaundryPoint> = listOf(
                point(category.ordinal + 1L, category, DormArea.MEI, building = 2, floor = 3)
            )

            override suspend fun fetchDevices(
                positionId: Long,
                category: LaundryCategory
            ): List<LaundryDevice> = emptyList()
        }
        val vm = LaundryViewModel(
            source,
            MemoryLocationStore(LaundryLastLocation(DormArea.MEI, 2, 3))
        )
        advanceUntilIdle()
        vm.closePoint()

        vm.selectCategory(LaundryCategory.DRYER)
        advanceUntilIdle()

        assertEquals(LaundryCategory.DRYER, vm.state.value.category)
        assertEquals(LaundryCategory.DRYER.ordinal + 1L, vm.state.value.selectedPoint?.positionId)
    }

    @Test
    fun `category switch fallback does not erase remembered full location`() = runTest(dispatcher) {
        val source = object : LaundryDataSource {
            override suspend fun fetchPoints(category: LaundryCategory): List<LaundryPoint> = listOf(
                point(
                    id = category.ordinal + 1L,
                    category = category,
                    area = DormArea.MEI,
                    building = 2,
                    floor = if (category == LaundryCategory.WASHER) 3 else 4
                )
            )

            override suspend fun fetchDevices(
                positionId: Long,
                category: LaundryCategory
            ): List<LaundryDevice> = emptyList()
        }
        val locations = MemoryLocationStore(LaundryLastLocation(DormArea.MEI, 2, 3))
        val vm = LaundryViewModel(source, locations)
        advanceUntilIdle()
        vm.closePoint()

        vm.selectCategory(LaundryCategory.DRYER)
        advanceUntilIdle()

        assertEquals(LaundryLastLocation(DormArea.MEI, 2, null), vm.state.value.filter.toLastLocation())
        assertEquals(null, vm.state.value.selectedPoint)
        assertEquals(LaundryLastLocation(DormArea.MEI, 2, 3), locations.location)
    }

    @Test
    fun `detail category switch rejects the old device response`() = runTest(dispatcher) {
        val source = object : LaundryDataSource {
            override suspend fun fetchPoints(category: LaundryCategory): List<LaundryPoint> = listOf(
                point(1, category).copy(
                    categories = setOf(LaundryCategory.WASHER, LaundryCategory.DRYER)
                )
            )

            override suspend fun fetchDevices(
                positionId: Long,
                category: LaundryCategory
            ): List<LaundryDevice> {
                if (category == LaundryCategory.WASHER) delay(10_000L)
                return listOf(device(category.ordinal + 1L))
            }
        }
        val vm = LaundryViewModel(source)
        advanceUntilIdle()
        vm.openPoint(vm.state.value.points.single())
        runCurrent()

        vm.selectDetailCategory(LaundryCategory.DRYER)
        advanceUntilIdle()

        assertEquals(LaundryCategory.DRYER, vm.state.value.detailCategory)
        assertEquals(listOf(3L), vm.state.value.devices.map { it.deviceId })
        assertFalse(vm.state.value.isDetailLoading)
    }

    private fun point(
        id: Long,
        category: LaundryCategory,
        area: DormArea = DormArea.GUI,
        building: Int = 1,
        floor: Int = 1
    ) = LaundryPoint(
        positionId = id,
        name = "测试洗衣点$id",
        address = "重庆市沙坪坝区大学城中路61号",
        idleCount = 1,
        categories = setOf(category),
        location = DormLocation(area, building = building, floor = floor)
    )

    private fun device(id: Long) = LaundryDevice(
        deviceId = id,
        name = "测试设备$id",
        floorCode = "1",
        state = LaundryDeviceState.IDLE,
        finishTime = null
    )

    private class MemoryLocationStore(
        var location: LaundryLastLocation? = null,
        var owner: String = "test-owner",
        var revision: Long = 0L
    ) : LaundryLocationStore {
        override fun currentSession(): LaundryLocationSession =
            LaundryLocationSession(owner, revision)

        override fun lastLocation(session: LaundryLocationSession): LaundryLastLocation? =
            location.takeIf { session == currentSession() }

        override fun saveLastLocation(
            session: LaundryLocationSession,
            location: LaundryLastLocation?
        ) {
            if (session == currentSession()) this.location = location
        }
    }
}
