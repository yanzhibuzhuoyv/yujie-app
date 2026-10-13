package com.cqmu.assistant.data.laundry

import java.time.LocalDateTime

enum class LaundryCategory(val code: String, val displayName: String) {
    WASHER("00", "洗衣机"),
    SHOE_WASHER("01", "洗鞋机"),
    DRYER("02", "烘干机");

    companion object {
        fun fromCode(code: String?): LaundryCategory? = entries.firstOrNull { it.code == code }
    }
}

enum class LaundryDeviceState(val code: Int, val displayName: String) {
    IDLE(1, "空闲"),
    OCCUPIED(2, "占用"),
    FAULT(3, "故障"),
    UNKNOWN(-1, "未知");

    companion object {
        fun fromCode(code: Int): LaundryDeviceState = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

enum class DormArea(val displayName: String) {
    GUI("桂苑"),
    MEI("梅苑"),
    XING("杏苑"),
    ZHU("竹苑"),
    JU("橘苑"),
    OTHER("其他")
}

data class DormLocation(
    val area: DormArea,
    val building: Int?,
    val floor: Int?
)

data class LaundryPoint(
    val positionId: Long,
    val name: String,
    val address: String,
    val idleCount: Int,
    val categories: Set<LaundryCategory>,
    val location: DormLocation
)

data class LaundryDevice(
    val deviceId: Long,
    val name: String,
    val floorCode: String,
    val state: LaundryDeviceState,
    val finishTime: LocalDateTime?
)

data class LaundryFilterSelection(
    val area: DormArea? = null,
    val building: Int? = null,
    val floor: Int? = null
)

data class LaundryLastLocation(
    val area: DormArea,
    val building: Int? = null,
    val floor: Int? = null
) {
    init {
        require(area != DormArea.OTHER) { "不能记住未知苑区" }
        require(building == null || building in 1..99) { "楼幢编号异常" }
        require(floor == null || floor in 1..99) { "楼层编号异常" }
        require(floor == null || building != null) { "楼层必须属于具体楼幢" }
    }

    fun toFilter(): LaundryFilterSelection = LaundryFilterSelection(area, building, floor)
}

data class LaundryLocationSession(
    val owner: String,
    val revision: Long
) {
    init {
        require(owner.isNotBlank()) { "账号标识不能为空" }
        require(revision >= 0L) { "会话代次异常" }
    }
}

fun LaundryFilterSelection.toLastLocation(): LaundryLastLocation? {
    val selectedArea = area?.takeIf { it != DormArea.OTHER } ?: return null
    return runCatching { LaundryLastLocation(selectedArea, building, floor) }.getOrNull()
}

data class LaundryPointQuery(val category: LaundryCategory)

interface LaundryLocationStore {
    fun currentSession(): LaundryLocationSession?
    fun lastLocation(session: LaundryLocationSession): LaundryLastLocation?
    fun saveLastLocation(session: LaundryLocationSession, location: LaundryLastLocation?)

    companion object {
        val NONE: LaundryLocationStore = object : LaundryLocationStore {
            override fun currentSession(): LaundryLocationSession? = null
            override fun lastLocation(session: LaundryLocationSession): LaundryLastLocation? = null
            override fun saveLastLocation(
                session: LaundryLocationSession,
                location: LaundryLastLocation?
            ) = Unit
        }
    }
}

data class LaundryDeviceSummary(
    val idle: Int,
    val occupied: Int,
    val fault: Int,
    val unknown: Int
) {
    companion object {
        fun from(devices: List<LaundryDevice>): LaundryDeviceSummary = LaundryDeviceSummary(
            idle = devices.count { it.state == LaundryDeviceState.IDLE },
            occupied = devices.count { it.state == LaundryDeviceState.OCCUPIED },
            fault = devices.count { it.state == LaundryDeviceState.FAULT },
            unknown = devices.count { it.state == LaundryDeviceState.UNKNOWN }
        )
    }
}

internal data class LaundryParsedPage<T>(
    val page: Int,
    val total: Int,
    val rawItemCount: Int,
    val rawKeys: Set<Long>,
    val items: List<T>
)
