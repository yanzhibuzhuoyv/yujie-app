package com.cqmu.assistant.data.laundry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.time.LocalDateTime
import okhttp3.HttpUrl.Companion.toHttpUrl

class LaundryPolicyTest {
    @Test
    fun `only supported read-only device categories are accepted`() {
        assertEquals(LaundryCategory.WASHER, LaundryCategory.fromCode("00"))
        assertEquals(LaundryCategory.SHOE_WASHER, LaundryCategory.fromCode("01"))
        assertEquals(LaundryCategory.DRYER, LaundryCategory.fromCode("02"))
        assertNull(LaundryCategory.fromCode("09"))
        assertNull(LaundryCategory.fromCode("00 OR 1=1"))
    }

    @Test
    fun `device state follows official status dictionary and keeps unknown safe`() {
        assertEquals(LaundryDeviceState.IDLE, LaundryDeviceState.fromCode(1))
        assertEquals(LaundryDeviceState.OCCUPIED, LaundryDeviceState.fromCode(2))
        assertEquals(LaundryDeviceState.FAULT, LaundryDeviceState.fromCode(3))
        assertEquals(LaundryDeviceState.UNKNOWN, LaundryDeviceState.fromCode(99))
    }

    @Test
    fun `campus policy requires both official organization and campus address`() {
        assertTrue(
            LaundryCampusPolicy.accepts(
                organizationId = 2_000_007_645,
                address = "重庆市沙坪坝区大学城中路61号"
            )
        )
        assertTrue(
            LaundryCampusPolicy.accepts(
                organizationId = 2_000_007_645,
                address = "重庆市沙坪坝区大学城中路"
            )
        )
        assertFalse(
            LaundryCampusPolicy.accepts(
                organizationId = 2_000_007_645,
                address = "重庆市渝中区医学院路1号"
            )
        )
        assertFalse(
            LaundryCampusPolicy.accepts(
                organizationId = 123,
                address = "重庆市沙坪坝区大学城中路61号"
            )
        )
    }

    @Test
    fun `location parser extracts dorm area building and floor`() {
        assertEquals(
            DormLocation(area = DormArea.GUI, building = 3, floor = 6),
            LaundryLocationParser.parse("重庆医科大学桂苑3栋6楼")
        )
        assertEquals(
            DormLocation(area = DormArea.MEI, building = 2, floor = 4),
            LaundryLocationParser.parse("重庆医科大学梅苑2栋4楼洗衣房")
        )
        assertEquals(
            DormLocation(area = DormArea.ZHU, building = 9, floor = null),
            LaundryLocationParser.parse("竹苑9栋洗衣点")
        )
        assertEquals(
            DormLocation(area = DormArea.JU, building = 2, floor = 4),
            LaundryLocationParser.parse("橘苑2栋4楼洗衣房")
        )
        assertEquals(
            DormLocation(area = DormArea.JU, building = 4, floor = 1),
            LaundryLocationParser.parse("橘园4栋1楼洗衣房")
        )
        assertEquals(
            DormLocation(area = DormArea.XING, building = 2, floor = 5),
            LaundryLocationParser.parse("杏苑2幢5层洗衣房")
        )
        assertEquals(
            DormLocation(area = DormArea.OTHER, building = null, floor = 5),
            LaundryLocationParser.parse("5楼洗衣点")
        )
    }

    @Test
    fun `location formatter uses one compact dorm building floor format and rejects incomplete locations`() {
        assertEquals(
            "桂苑3幢6层",
            LaundryLocationFormatter.format(
                DormLocation(area = DormArea.GUI, building = 3, floor = 6)
            )
        )
        assertNull(
            LaundryLocationFormatter.format(
                DormLocation(area = DormArea.ZHU, building = 9, floor = null)
            )
        )
        assertNull(
            LaundryLocationFormatter.format(
                DormLocation(area = DormArea.OTHER, building = 1, floor = 2)
            )
        )
        assertEquals(
            "杏苑4幢",
            LaundryLocationFormatter.format(
                DormLocation(area = DormArea.XING, building = 4, floor = null)
            )
        )
    }

    @Test
    fun `official shop groups restore abbreviated xing and ju locations`() {
        val officialGroups = mapOf(
            2_000_004_810L to (DormArea.XING to 1),
            2_000_004_811L to (DormArea.XING to 2),
            2_000_004_812L to (DormArea.XING to 3),
            2_000_012_870L to (DormArea.JU to 1),
            2_000_012_869L to (DormArea.JU to 2),
            2_000_012_872L to (DormArea.JU to 3),
            2_000_012_864L to (DormArea.JU to 4)
        )

        officialGroups.forEach { (shopId, expected) ->
            assertEquals(
                DormLocation(expected.first, building = expected.second, floor = 5),
                LaundryLocationHintPolicy.enrich(
                    DormLocation(DormArea.OTHER, building = null, floor = 5),
                    shopId
                )
            )
        }
    }

    @Test
    fun `official shop hints reject explicit area or building conflicts`() {
        assertNull(
            LaundryLocationHintPolicy.enrich(
                DormLocation(DormArea.GUI, building = null, floor = 5),
                shopId = 2_000_004_810L
            )
        )
        assertNull(
            LaundryLocationHintPolicy.enrich(
                DormLocation(DormArea.OTHER, building = 4, floor = 5),
                shopId = 2_000_004_810L
            )
        )
    }

    @Test
    fun `point filter combines area building and floor without changing source`() {
        val points = listOf(
            point(1, "桂苑1栋2层洗衣房"),
            point(2, "桂苑1栋5层洗衣房"),
            point(3, "桂苑2栋2层洗衣房"),
            point(4, "梅苑1栋2层洗衣房")
        )

        val filtered = LaundryPointFilter.filter(
            points = points,
            selection = LaundryFilterSelection(
                area = DormArea.GUI,
                building = 1,
                floor = 2
            )
        )

        assertEquals(listOf(1L), filtered.map { it.positionId })
        assertEquals(4, points.size)
    }

    @Test
    fun `available buildings and floors follow the current parent filter`() {
        val points = listOf(
            point(1, "桂苑1栋2层洗衣房"),
            point(2, "桂苑1栋5层洗衣房"),
            point(3, "桂苑2栋3层洗衣房"),
            point(4, "梅苑1栋6层洗衣房")
        )

        assertEquals(listOf(1, 2), LaundryPointFilter.buildings(points, DormArea.GUI))
        assertEquals(
            listOf(2, 5),
            LaundryPointFilter.floors(points, DormArea.GUI, building = 1)
        )
    }

    @Test
    fun `remaining minutes rounds up and never returns a negative value`() {
        val now = LocalDateTime.of(2026, 8, 30, 19, 24, 30)

        assertEquals(6L, LaundryTime.remainingMinutes(now, now.plusMinutes(5).plusSeconds(1)))
        assertEquals(1L, LaundryTime.remainingMinutes(now, now.plusSeconds(1)))
        assertEquals(0L, LaundryTime.remainingMinutes(now, now.minusSeconds(1)))
        assertNull(LaundryTime.remainingMinutes(now, null))
    }

    @Test
    fun `completion label includes absolute finish time and remaining minutes`() {
        val now = LocalDateTime.of(2026, 8, 30, 19, 24, 30)

        assertEquals(
            "预计 19:30 完成（还有 6 分钟）",
            LaundryTime.completionLabel(now, LocalDateTime.of(2026, 8, 30, 19, 30, 22))
        )
        assertEquals(
            "预计 19:20 完成",
            LaundryTime.completionLabel(now, LocalDateTime.of(2026, 8, 30, 19, 20, 0))
        )
        assertNull(LaundryTime.completionLabel(now, null))
    }

    @Test
    fun `refresh session rejects a stale response after query changes`() {
        val initial = LaundryPointQuery(LaundryCategory.WASHER)
        val first = LaundryRefreshSession(initial)
        val (loading, ticket) = first.beginLoad()
        val edited = loading.edit(LaundryPointQuery(LaundryCategory.DRYER))

        assertFalse(edited.canCommit(ticket))

        val (latest, latestTicket) = edited.beginLoad()
        assertTrue(latest.canCommit(latestTicket))
    }

    @Test(expected = IllegalStateException::class)
    fun `pagination rejects truncated device results`() {
        LaundryPaginationPolicy.requireComplete(received = 19, reportedTotal = 20)
    }

    @Test
    fun `pagination accepts complete device results`() {
        LaundryPaginationPolicy.requireComplete(received = 20, reportedTotal = 20)
    }

    @Test
    fun `response validator discards foreign campus and unsafe values`() {
        assertNull(
            LaundryResponseValidator.point(
                positionId = 1,
                name = "其他学校洗衣房",
                address = "重庆市沙坪坝区大学城中路61号",
                organizationId = 99,
                idleCount = 2,
                categoryCodes = listOf("00")
            )
        )
        assertNull(
            LaundryResponseValidator.point(
                positionId = 2,
                name = "重庆医科大学竹苑1栋2楼洗衣房",
                address = LaundryCampusPolicy.CAMPUS_ADDRESS,
                organizationId = LaundryCampusPolicy.ORGANIZATION_ID,
                idleCount = 1,
                categoryCodes = listOf("00")
            )
        )
        assertNull(
            LaundryResponseValidator.point(
                positionId = 3,
                name = "竹苑1幢3层洗衣房",
                address = LaundryCampusPolicy.CAMPUS_ADDRESS,
                organizationId = LaundryCampusPolicy.ORGANIZATION_ID,
                idleCount = 1,
                categoryCodes = listOf("00")
            )
        )
        listOf(
            "竹苑 一 幢3层洗衣房",
            "竹苑１幢4层洗衣房",
            "竹苑第一号栋5层洗衣房",
            "竹苑留学生1幢3层洗衣房",
            "竹苑01幢3层洗衣房",
            "桂苑旁竹苑留学生1幢6层洗衣房",
            "桂苑旁竹苑01幢6层洗衣房",
            "桂苑旁竹苑1幢6层洗衣房"
        ).forEachIndexed { index, name ->
            assertNull(
                LaundryResponseValidator.point(
                    positionId = 10L + index,
                    name = name,
                    address = LaundryCampusPolicy.CAMPUS_ADDRESS,
                    organizationId = LaundryCampusPolicy.ORGANIZATION_ID,
                    idleCount = 1,
                    categoryCodes = listOf("00")
                )
            )
        }
        assertEquals(
            2,
            LaundryResponseValidator.point(
                positionId = 20,
                name = "竹苑10幢2层洗衣房",
                address = LaundryCampusPolicy.CAMPUS_ADDRESS,
                organizationId = LaundryCampusPolicy.ORGANIZATION_ID,
                idleCount = 1,
                categoryCodes = listOf("00")
            )?.location?.floor
        )
        listOf(
            "5楼洗衣点",
            "桂苑2栋洗衣房",
            "梅苑3层洗衣房"
        ).forEachIndexed { index, name ->
            assertNull(
                LaundryResponseValidator.point(
                    positionId = 30L + index,
                    name = name,
                    address = LaundryCampusPolicy.CAMPUS_ADDRESS,
                    organizationId = LaundryCampusPolicy.ORGANIZATION_ID,
                    idleCount = 1,
                    categoryCodes = listOf("00")
                )
            )
        }
        assertEquals(
            "杏苑2幢5层",
            LaundryResponseValidator.point(
                positionId = 40,
                name = "重庆医科大学杏苑2栋5楼洗衣房",
                address = LaundryCampusPolicy.CAMPUS_ADDRESS,
                organizationId = LaundryCampusPolicy.ORGANIZATION_ID,
                idleCount = 1,
                categoryCodes = listOf("00")
            )?.name
        )
        assertNull(
            LaundryResponseValidator.point(
                positionId = 1,
                name = "重庆医科大学桂苑1栋",
                address = LaundryCampusPolicy.CAMPUS_ADDRESS,
                organizationId = LaundryCampusPolicy.ORGANIZATION_ID,
                idleCount = -1,
                categoryCodes = listOf("00")
            )
        )
    }

    @Test
    fun `response validator maps only the minimum safe device fields`() {
        val device = LaundryResponseValidator.device(
            deviceId = 7,
            name = "桂苑3栋6层2号机",
            floorCode = "6",
            stateCode = 2,
            finishTime = "2026-08-30 19:30:22"
        )

        assertEquals(LaundryDeviceState.OCCUPIED, device?.state)
        assertEquals(LocalDateTime.of(2026, 8, 30, 19, 30, 22), device?.finishTime)
        assertNull(
            LaundryResponseValidator.device(
                deviceId = 0,
                name = "异常设备",
                floorCode = "6",
                stateCode = 1,
                finishTime = null
            )
        )
    }

    @Test
    fun `endpoint policy allows only official host and read-only routes`() {
        val official = "https://yshz-user.haier-ioc.com/".toHttpUrl()
        LaundryEndpointPolicy.requireAllowed(official, "/position/nearPosition")
        LaundryEndpointPolicy.requireAllowed(official, "/position/deviceDetailPage")

        runCatching {
            LaundryEndpointPolicy.requireAllowed(
                "https://example.com/".toHttpUrl(),
                "/position/nearPosition"
            )
        }.also { assertTrue(it.isFailure) }
        runCatching {
            LaundryEndpointPolicy.requireAllowed(official, "/order/create")
        }.also { assertTrue(it.isFailure) }
    }

    @Test
    fun `changing a parent filter clears invalid child filters`() {
        val selected = LaundryFilterSelection(
            area = DormArea.GUI,
            building = 3,
            floor = 6
        )

        assertEquals(
            LaundryFilterSelection(area = DormArea.MEI),
            LaundryFilterReducer.selectArea(selected, DormArea.MEI)
        )
        assertEquals(
            LaundryFilterSelection(area = DormArea.GUI, building = 2),
            LaundryFilterReducer.selectBuilding(selected, 2)
        )
        assertEquals(
            selected.copy(floor = 4),
            LaundryFilterReducer.selectFloor(selected, 4)
        )
    }

    @Test
    fun `device summary counts every supported state`() {
        val devices = listOf(
            device(1, LaundryDeviceState.IDLE),
            device(2, LaundryDeviceState.IDLE),
            device(3, LaundryDeviceState.OCCUPIED),
            device(4, LaundryDeviceState.FAULT),
            device(5, LaundryDeviceState.UNKNOWN)
        )

        assertEquals(
            LaundryDeviceSummary(idle = 2, occupied = 1, fault = 1, unknown = 1),
            LaundryDeviceSummary.from(devices)
        )
    }

    @Test
    fun `json parser keeps only validated campus points while preserving page counts`() {
        val root = JSONObject(
            """{
              "code":0,
              "data":{"page":1,"pageSize":100,"total":2,"items":[
                {"id":34056,"name":"重庆医科大学桂苑3栋6楼","address":"重庆市沙坪坝区大学城中路61号","organizationId":2000007645,"idleCount":1,"categoryCodeList":["00","02"]},
                {"id":9,"name":"其他学校","address":"重庆市沙坪坝区大学城中路61号","organizationId":9,"idleCount":9,"categoryCodeList":["00"]}
              ]}
            }""".trimIndent()
        )

        val page = LaundryJsonParser.parsePointPage(root)

        assertEquals(2, page.rawItemCount)
        assertEquals(2, page.total)
        assertEquals(listOf(34056L), page.items.map { it.positionId })
        assertEquals(
            setOf(LaundryCategory.WASHER, LaundryCategory.DRYER),
            page.items.single().categories
        )
    }

    @Test
    fun `json parser restores abbreviated official point names and keeps building only points`() {
        val root = JSONObject(
            """{
              "code":0,
              "data":{"page":1,"pageSize":100,"total":3,"items":[
                {"id":33169,"shopId":2000004810,"name":"1楼自助洗衣房","address":"重庆市沙坪坝区大学城中路61号","organizationId":2000007645,"idleCount":7,"categoryCodeList":["00"]},
                {"id":33172,"shopId":2000004813,"name":"重庆医科大学杏苑4栋洗衣房","address":"重庆市沙坪坝区大学城中路61号","organizationId":2000007645,"idleCount":11,"categoryCodeList":["00"]},
                {"id":47732,"shopId":2000012872,"name":"4楼洗衣点","address":"重庆市沙坪坝区大学城中路61号","organizationId":2000007645,"idleCount":1,"categoryCodeList":["00"]}
              ]}
            }""".trimIndent()
        )

        val points = LaundryJsonParser.parsePointPage(root).items

        assertEquals(
            listOf("杏苑1幢1层", "杏苑4幢", "橘苑3幢4层"),
            points.map { it.name }
        )
    }

    @Test
    fun `json parser maps device status without retaining imei`() {
        val root = JSONObject(
            """{
              "code":0,
              "data":{"page":1,"pageSize":100,"total":1,"items":[
                {"id":85601501,"name":"桂苑3栋6层2号机","imei":"must-not-leave-parser","floorCode":"6","state":2,"finishTime":"2026-08-30 19:30:22"}
              ]}
            }""".trimIndent()
        )

        val device = LaundryJsonParser.parseDevicePage(root).items.single()

        assertEquals(85601501L, device.deviceId)
        assertEquals(LaundryDeviceState.OCCUPIED, device.state)
        assertFalse(device.toString().contains("must-not-leave-parser"))
    }

    @Test
    fun `json parser accepts official inclusive page boundary and exposes raw keys`() {
        val root = JSONObject(
            """{
              "code":0,
              "data":{"page":1,"pageSize":1,"total":2,"items":[
                {"id":1,"name":"桂苑1栋1楼","address":"重庆市沙坪坝区大学城中路61号","organizationId":2000007645,"idleCount":1,"categoryCodeList":["00"]},
                {"id":2,"name":"桂苑1栋2楼","address":"重庆市沙坪坝区大学城中路61号","organizationId":2000007645,"idleCount":1,"categoryCodeList":["00"]}
              ]}
            }""".trimIndent()
        )

        val page = LaundryJsonParser.parsePointPage(root)

        assertEquals(setOf(1L, 2L), page.rawKeys)
        assertEquals(2, page.rawItemCount)
    }

    @Test
    fun `page accumulator deduplicates the official overlapping boundary item`() {
        val firstKeys = (1L..101L).toSet()
        val secondKeys = (101L..150L).toSet()
        val accumulator = LaundryPageAccumulator<Long> { it }

        assertFalse(
            accumulator.accept(
                expectedPage = 1,
                page = LaundryParsedPage(1, 150, 101, firstKeys, firstKeys.toList())
            )
        )
        assertTrue(
            accumulator.accept(
                expectedPage = 2,
                page = LaundryParsedPage(2, 150, 50, secondKeys, secondKeys.toList())
            )
        )
        assertEquals(150, accumulator.requireResult().size)
    }

    @Test
    fun `page accumulator rejects changing totals and pages without progress`() {
        val changedTotal = LaundryPageAccumulator<Long> { it }
        changedTotal.accept(1, LaundryParsedPage(1, 2, 1, setOf(1L), listOf(1L)))
        assertTrue(
            runCatching {
                changedTotal.accept(2, LaundryParsedPage(2, 3, 1, setOf(2L), listOf(2L)))
            }.isFailure
        )

        val noProgress = LaundryPageAccumulator<Long> { it }
        noProgress.accept(1, LaundryParsedPage(1, 2, 1, setOf(1L), listOf(1L)))
        assertTrue(
            runCatching {
                noProgress.accept(2, LaundryParsedPage(2, 2, 1, setOf(1L), listOf(1L)))
            }.isFailure
        )
    }

    @Test
    fun `json safety rejects excessive nesting but ignores braces inside strings`() {
        LaundryJsonSafety.requireSafeShape("""{"message":"[[[{{{"}""")
        val deep = "[".repeat(33) + "0" + "]".repeat(33)

        assertTrue(runCatching { LaundryJsonSafety.requireSafeShape(deep) }.isFailure)
    }

    @Test
    fun `json parser does not coerce string identifiers into numbers`() {
        val root = JSONObject(
            """{
              "code":0,
              "data":{"page":1,"pageSize":100,"total":1,"items":[
                {"id":1,"name":"桂苑1栋1楼","address":"重庆市沙坪坝区大学城中路61号","organizationId":"2000007645","idleCount":1,"categoryCodeList":["00"]}
              ]}
            }""".trimIndent()
        )

        assertTrue(LaundryJsonParser.parsePointPage(root).items.isEmpty())
    }

    @Test
    fun `last laundry location codec preserves valid hierarchy`() {
        val full = LaundryLastLocation(DormArea.MEI, building = 2, floor = 3)
        val building = LaundryLastLocation(DormArea.GUI, building = 4, floor = null)

        assertEquals(
            full,
            LaundryLastLocationCodec.decode(
                LaundryLastLocationCodec.encode("test-owner-001", full),
                "test-owner-001"
            )
        )
        assertEquals(
            building,
            LaundryLastLocationCodec.decode(
                LaundryLastLocationCodec.encode("test-owner-001", building),
                "test-owner-001"
            )
        )
    }

    @Test
    fun `last laundry location codec rejects malformed or broken hierarchy`() {
        val invalid = listOf(
            "not-json",
            """{"v":1,"owner":"test-owner-001","area":"OTHER","building":1,"floor":1}""",
            """{"v":1,"owner":"test-owner-001","area":"UNKNOWN","building":1,"floor":1}""",
            """{"v":1,"owner":"test-owner-001","area":"MEI","building":null,"floor":1}""",
            """{"v":1,"owner":"test-owner-001","area":"MEI","building":0,"floor":null}""",
            """{"v":1,"owner":"test-owner-001","area":"MEI","building":1.5,"floor":2}""",
            """{"v":2,"owner":"test-owner-001","area":"MEI","building":1,"floor":2}""",
            """{"v":1,"owner":"test-owner-001","area":"MEI","building":1,"floor":2}garbage""",
            """{"v":1,"owner":"test-owner-001","area":"MEI","building":1,"floor":2}{"v":1}""",
            """{"v":1,"owner":"test-owner-001","area":"MEI","building":1,"floor":2,"extra":true}"""
        )

        invalid.forEach { raw ->
            assertNull(LaundryLastLocationCodec.decode(raw, "test-owner-001"))
        }
        assertNull(
            LaundryLastLocationCodec.decode(
                LaundryLastLocationCodec.encode(
                    "test-owner-001",
                    LaundryLastLocation(DormArea.MEI, 1, 2)
                ),
                "another-account"
            )
        )
    }

    private fun point(id: Long, name: String) = LaundryPoint(
        positionId = id,
        name = name,
        address = "重庆市沙坪坝区大学城中路61号",
        idleCount = 1,
        categories = setOf(LaundryCategory.WASHER),
        location = LaundryLocationParser.parse(name)
    )

    private fun device(id: Long, state: LaundryDeviceState) = LaundryDevice(
        deviceId = id,
        name = "设备$id",
        floorCode = "1",
        state = state,
        finishTime = null
    )
}
