package com.cqmu.assistant.data

private const val MAX_FREE_ROOM_PERIOD = 18

internal object CacheOwnershipPolicy {
    fun identityChanged(previousOwner: String, authenticatedOwner: String): Boolean =
        previousOwner != authenticatedOwner

    fun canRead(cacheOwner: String, authenticatedOwner: String): Boolean =
        cacheOwner.isNotBlank() && authenticatedOwner.isNotBlank() &&
            cacheOwner == authenticatedOwner

    fun requiresNewSession(
        previousOwner: String,
        authenticatedOwner: String,
        wasLoggedOut: Boolean
    ): Boolean = wasLoggedOut || identityChanged(previousOwner, authenticatedOwner)
}

/** 学校 Beangle 分页读取 pageNo；pageIndex 只会被当作普通查询条件原样回显。 */
internal object FreeRoomSearchParams {
    fun forPage(
        query: FreeRoomQuery,
        pageNo: Int,
        pageSize: Int = 100
    ): Map<String, String> = linkedMapOf(
        "classroom.type.id" to query.typeId,
        "classroom.campus.id" to query.campusId,
        "classroom.building.id" to "",
        "seats" to query.minSeats,
        "classroom.name" to query.roomName,
        "cycleTime.cycleCount" to "1",
        "cycleTime.cycleType" to "1",
        "cycleTime.dateBegin" to query.date.toString(),
        "cycleTime.dateEnd" to query.date.toString(),
        "roomApplyTimeType" to "0",
        "timeBegin" to query.beginPeriod.coerceIn(1, MAX_FREE_ROOM_PERIOD).toString(),
        "timeEnd" to query.endPeriod.coerceIn(1, MAX_FREE_ROOM_PERIOD).toString(),
        "pageNo" to pageNo.coerceAtLeast(1).toString(),
        "pageSize" to pageSize.coerceAtLeast(1).toString()
    )
}

internal object FreeRoomPaginationPolicy {
    /**
     * 学校的 AJAX 片段并不保证携带 pageInfo。已有显式总数时继续沿用；从未拿到
     * 显式总数时保持 unknown，由调用方继续翻页直到收到空页，不能把当前页行数当总数。
     */
    fun mergeReportedTotal(
        expectedTotal: Int?,
        reportedTotal: Int,
        isExplicit: Boolean
    ): Int? {
        if (!isExplicit) return expectedTotal
        if (expectedTotal != null && reportedTotal != expectedTotal) {
            throw EamsException("空闲教室分页总数变化（$expectedTotal → $reportedTotal），请重试")
        }
        return reportedTotal
    }

    fun isComplete(
        loadedCount: Int,
        expectedTotal: Int?,
        latestPageCount: Int
    ): Boolean = latestPageCount == 0 || expectedTotal?.let { loadedCount >= it } == true

    fun requireComplete(loadedCount: Int, total: Int) {
        if (loadedCount != total) {
            throw EamsException("空闲教室分页不完整（已获取 $loadedCount / $total），请重试")
        }
    }
}

/** 纯分页状态机：兼容无总数响应，同时拒绝重复页、总数变化和不完整结果。 */
internal data class FreeRoomPaginationState(
    val rooms: List<FreeRoom> = emptyList(),
    val expectedTotal: Int? = null,
    // 服务端 total 统计原始行；rooms 为按教室身份去重后的展示结果，两者不能混用。
    val receivedCount: Int = 0,
    val latestPageCount: Int = -1
) {
    val isComplete: Boolean
        get() = FreeRoomPaginationPolicy.isComplete(
            loadedCount = receivedCount,
            expectedTotal = expectedTotal,
            latestPageCount = latestPageCount
        )

    fun accept(result: FreeRoomResult): FreeRoomPaginationState {
        val nextTotal = FreeRoomPaginationPolicy.mergeReportedTotal(
            expectedTotal = expectedTotal,
            reportedTotal = result.total,
            isExplicit = result.totalIsExplicit
        )
        if (result.rooms.isEmpty()) {
            return copy(expectedTotal = nextTotal, latestPageCount = 0)
        }
        val merged = (rooms + result.rooms).distinctBy { room ->
            Triple(room.campus, room.building, room.name)
        }
        if (merged.size == rooms.size) {
            throw EamsException("空闲教室分页无进展，请重试")
        }
        return FreeRoomPaginationState(
            rooms = merged,
            expectedTotal = nextTotal,
            receivedCount = receivedCount + result.rooms.size,
            latestPageCount = result.rooms.size
        )
    }

    fun resultOrThrow(maxPages: Int): List<FreeRoom> {
        if (!isComplete) {
            throw EamsException("空闲教室分页超过 $maxPages 页仍未结束，请缩小查询范围")
        }
        expectedTotal?.let { FreeRoomPaginationPolicy.requireComplete(receivedCount, it) }
        return rooms
    }
}

internal data class FreeRoomLoadTicket(
    val generation: Long,
    val query: FreeRoomQuery
)

/**
 * draft 是用户正在编辑的条件，resultQuery 是当前列表对应的已提交条件。
 * 每次编辑或重新查询都会推进 generation，使旧缓存/网络/占用响应失效。
 */
internal data class FreeRoomQuerySession(
    val draft: FreeRoomQuery,
    val resultQuery: FreeRoomQuery? = null,
    val generation: Long = 0L
) {
    fun edit(newDraft: FreeRoomQuery): FreeRoomQuerySession =
        if (newDraft == draft) this
        else copy(draft = newDraft, resultQuery = null, generation = generation + 1)

    fun beginLoad(): Pair<FreeRoomQuerySession, FreeRoomLoadTicket> {
        val nextGeneration = generation + 1
        val loading = copy(resultQuery = draft, generation = nextGeneration)
        return loading to FreeRoomLoadTicket(nextGeneration, draft)
    }

    fun canCommit(ticket: FreeRoomLoadTicket): Boolean =
        generation == ticket.generation && resultQuery == ticket.query
}
