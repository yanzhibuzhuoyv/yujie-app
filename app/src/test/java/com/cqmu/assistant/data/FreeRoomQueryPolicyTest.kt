package com.cqmu.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FreeRoomQueryPolicyTest {
    private fun room(name: String) = FreeRoom(
        name = name,
        building = "合成教学楼",
        campus = "合成校区",
        facility = "",
        capacity = 40,
        notes = ""
    )

    @Test
    fun `cache ownership rejects blank or different account`() {
        assertTrue(CacheOwnershipPolicy.identityChanged("old", "new"))
        assertTrue(CacheOwnershipPolicy.identityChanged("", "new"))
        assertFalse(CacheOwnershipPolicy.identityChanged("same", "same"))
        assertTrue(CacheOwnershipPolicy.canRead("same", "same"))
        assertFalse(CacheOwnershipPolicy.canRead("old", "new"))
        assertFalse(CacheOwnershipPolicy.canRead("", "new"))
        assertFalse(CacheOwnershipPolicy.canRead("same", ""))
        assertFalse(CacheOwnershipPolicy.requiresNewSession("same", "same", wasLoggedOut = false))
        assertTrue(CacheOwnershipPolicy.requiresNewSession("old", "new", wasLoggedOut = false))
        assertTrue(CacheOwnershipPolicy.requiresNewSession("same", "same", wasLoggedOut = true))
    }


    private val queryA = FreeRoomQuery(
        date = LocalDate.of(2026, 9, 7),
        beginPeriod = 1,
        endPeriod = 2,
        campusId = "3",
        typeId = "15",
        minSeats = "40",
        roomName = "A1"
    )
    private val queryB = queryA.copy(beginPeriod = 15, endPeriod = 18)

    @Test
    fun `free room request sends pageNo and every query condition`() {
        val params = FreeRoomSearchParams.forPage(queryA, pageNo = 2)

        assertEquals("2", params["pageNo"])
        assertFalse(params.containsKey("pageIndex"))
        assertEquals("100", params["pageSize"])
        assertEquals("2026-09-07", params["cycleTime.dateBegin"])
        assertEquals("2026-09-07", params["cycleTime.dateEnd"])
        assertEquals("1", params["timeBegin"])
        assertEquals("2", params["timeEnd"])
        assertEquals("3", params["classroom.campus.id"])
        assertEquals("15", params["classroom.type.id"])
        assertEquals("40", params["seats"])
        assertEquals("A1", params["classroom.name"])
        assertEquals("0", params["roomApplyTimeType"])
    }

    @Test
    fun `only pageNo changes between pagination requests`() {
        val first = FreeRoomSearchParams.forPage(queryA, pageNo = 1)
        val second = FreeRoomSearchParams.forPage(queryA, pageNo = 2)

        assertEquals(first - "pageNo", second - "pageNo")
        assertEquals("1", first["pageNo"])
        assertEquals("2", second["pageNo"])
    }

    @Test
    fun `editing conditions invalidates an in-flight response`() {
        val (loadingA, ticketA) = FreeRoomQuerySession(queryA).beginLoad()
        val edited = loadingA.edit(queryB)

        assertFalse(edited.canCommit(ticketA))
        assertNull(edited.resultQuery)
        assertEquals(queryB, edited.draft)
    }

    @Test
    fun `only the newest submitted query may commit and label results`() {
        val (loadingA, ticketA) = FreeRoomQuerySession(queryA).beginLoad()
        val editedB = loadingA.edit(queryB)
        val (loadingB, ticketB) = editedB.beginLoad()

        assertFalse(loadingB.canCommit(ticketA))
        assertTrue(loadingB.canCommit(ticketB))
        assertEquals(queryB, loadingB.resultQuery)
    }

    @Test
    fun `incomplete pagination is never accepted as a successful result`() {
        val error = assertThrows(EamsException::class.java) {
            FreeRoomPaginationPolicy.requireComplete(loadedCount = 200, total = 421)
        }

        assertTrue(error.message.orEmpty().contains("200 / 421"))
        assertThrows(EamsException::class.java) {
            FreeRoomPaginationPolicy.requireComplete(loadedCount = 422, total = 421)
        }
        FreeRoomPaginationPolicy.requireComplete(loadedCount = 421, total = 421)
    }

    @Test
    fun `missing pageInfo keeps total unknown so pagination can continue to an empty page`() {
        val unknown = FreeRoomPaginationPolicy.mergeReportedTotal(
            expectedTotal = null,
            reportedTotal = 20,
            isExplicit = false
        )

        assertNull(unknown)
        assertFalse(
            FreeRoomPaginationPolicy.isComplete(
                loadedCount = 20,
                expectedTotal = unknown,
                latestPageCount = 20
            )
        )
        assertTrue(
            FreeRoomPaginationPolicy.isComplete(
                loadedCount = 20,
                expectedTotal = unknown,
                latestPageCount = 0
            )
        )
    }

    @Test
    fun `later pages may omit but cannot change the first explicit total`() {
        val expected = FreeRoomPaginationPolicy.mergeReportedTotal(
            expectedTotal = null,
            reportedTotal = 421,
            isExplicit = true
        )

        assertEquals(
            expected,
            FreeRoomPaginationPolicy.mergeReportedTotal(
                expected,
                reportedTotal = 0,
                isExplicit = false
            )
        )
        assertThrows(EamsException::class.java) {
            FreeRoomPaginationPolicy.mergeReportedTotal(
                expected,
                reportedTotal = 400,
                isExplicit = true
            )
        }
    }

    @Test
    fun `pagination without pageInfo completes on empty page and keeps parsed rooms`() {
        val first = FreeRoomPaginationState().accept(
            FreeRoomResult(listOf(room("合成教室A")), total = 1, totalIsExplicit = false)
        )

        assertFalse(first.isComplete)
        val terminal = first.accept(
            FreeRoomResult(emptyList(), total = 0, totalIsExplicit = false)
        )

        assertTrue(terminal.isComplete)
        assertEquals(listOf("合成教室A"), terminal.resultOrThrow(maxPages = 10).map { it.name })
    }

    @Test
    fun `pagination without pageInfo accepts a legitimate empty result`() {
        val terminal = FreeRoomPaginationState().accept(
            FreeRoomResult(emptyList(), total = 0, totalIsExplicit = false)
        )

        assertTrue(terminal.isComplete)
        assertTrue(terminal.resultOrThrow(maxPages = 10).isEmpty())
    }

    @Test
    fun `repeated page without progress is rejected`() {
        val page = FreeRoomResult(listOf(room("合成教室A")), total = 1, totalIsExplicit = false)
        val first = FreeRoomPaginationState().accept(page)

        val error = assertThrows(EamsException::class.java) { first.accept(page) }

        assertTrue(error.message.orEmpty().contains("无进展"))
    }

    @Test
    fun `empty page before an explicit total is reached fails as incomplete`() {
        val first = FreeRoomPaginationState().accept(
            FreeRoomResult(listOf(room("合成教室A")), total = 2, totalIsExplicit = true)
        )
        val terminal = first.accept(
            FreeRoomResult(emptyList(), total = 0, totalIsExplicit = false)
        )

        assertTrue(terminal.isComplete)
        val error = assertThrows(EamsException::class.java) {
            terminal.resultOrThrow(maxPages = 10)
        }
        assertTrue(error.message.orEmpty().contains("1 / 2"))
    }

    @Test
    fun `explicit total counts received rows while returned rooms stay deduplicated`() {
        val first = FreeRoomPaginationState().accept(
            FreeRoomResult(
                rooms = listOf(room("合成教室A"), room("合成教室B")),
                total = 4,
                totalIsExplicit = true
            )
        )

        val terminal = first.accept(
            FreeRoomResult(
                rooms = listOf(room("合成教室B"), room("合成教室C")),
                total = 4,
                totalIsExplicit = true
            )
        )

        assertTrue(terminal.isComplete)
        assertEquals(4, terminal.receivedCount)
        assertEquals(
            listOf("合成教室A", "合成教室B", "合成教室C"),
            terminal.resultOrThrow(maxPages = 10).map { it.name }
        )
    }
}
