package com.cqmu.assistant.data.laundry

import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.text.Normalizer
import okhttp3.HttpUrl
import org.json.JSONObject
import org.json.JSONTokener

object LaundryCampusPolicy {
    const val ORGANIZATION_ID = 2_000_007_645L
    const val CAMPUS_ADDRESS = "重庆市沙坪坝区大学城中路61号"
    private val acceptedAddresses = setOf(
        CAMPUS_ADDRESS,
        "重庆市沙坪坝区大学城中路"
    )

    fun accepts(organizationId: Long, address: String): Boolean =
        organizationId == ORGANIZATION_ID && address.trim() in acceptedAddresses
}

object LaundryLocationParser {
    private val buildingPattern = Regex("(\\d{1,2})(?:栋|幢)")
    private val floorPattern = Regex("(\\d{1,2})(?:层|楼)")

    fun parse(name: String): DormLocation {
        val normalizedName = Normalizer.normalize(name, Normalizer.Form.NFKC)
            .filterNot(Char::isWhitespace)
        return DormLocation(
            area = when {
                "桂苑" in normalizedName -> DormArea.GUI
                "梅苑" in normalizedName -> DormArea.MEI
                "杏苑" in normalizedName -> DormArea.XING
                "竹苑" in normalizedName -> DormArea.ZHU
                "橘苑" in normalizedName || "橘园" in normalizedName -> DormArea.JU
                else -> DormArea.OTHER
            },
            building = buildingPattern.find(normalizedName)
                ?.groupValues?.getOrNull(1)?.toIntOrNull(),
            floor = floorPattern.findAll(normalizedName)
                .lastOrNull()?.groupValues?.getOrNull(1)?.toIntOrNull()
        )
    }
}

object LaundryLocationFormatter {
    fun format(location: DormLocation): String? {
        val building = location.building ?: return null
        if (location.area == DormArea.OTHER) return null
        val buildingName = "${location.area.displayName}${building}幢"
        return location.floor?.let { floor -> "$buildingName${floor}层" }
            ?: buildingName.takeIf { location.area == DormArea.XING }
    }
}

/**
 * 海乐部分历史点位只保留“某楼洗衣点”，苑区和幢号需由同一官方商户分组补全。
 * 仅补全已实测确认的分组；带完整苑区/幢号的名称始终以名称本身为准。
 */
object LaundryLocationHintPolicy {
    private val officialShopLocations = mapOf(
        2_000_004_810L to (DormArea.XING to 1),
        2_000_004_811L to (DormArea.XING to 2),
        2_000_004_812L to (DormArea.XING to 3),
        2_000_012_870L to (DormArea.JU to 1),
        2_000_012_869L to (DormArea.JU to 2),
        2_000_012_872L to (DormArea.JU to 3),
        2_000_012_864L to (DormArea.JU to 4)
    )

    fun enrich(parsed: DormLocation, shopId: Long?): DormLocation? {
        val hint = officialShopLocations[shopId] ?: return parsed
        if (parsed.area != DormArea.OTHER && parsed.area != hint.first) return null
        if (parsed.building != null && parsed.building != hint.second) return null
        return DormLocation(
            area = parsed.area.takeIf { it != DormArea.OTHER } ?: hint.first,
            building = parsed.building ?: hint.second,
            floor = parsed.floor
        )
    }
}

object LaundryPointPolicy {
    private val excludedBambooOne = Regex(
        "竹苑[^0-9一二三四五六七八九十壹]*(?:0*1|一|壹)(?:号)?(?:栋|幢)"
    )

    fun isExcluded(name: String): Boolean {
        val normalizedName = Normalizer.normalize(name, Normalizer.Form.NFKC)
            .filter(Char::isLetterOrDigit)
        return excludedBambooOne.containsMatchIn(normalizedName)
    }
}

object LaundryPointFilter {
    fun filter(
        points: List<LaundryPoint>,
        selection: LaundryFilterSelection
    ): List<LaundryPoint> = points.filter { point ->
        val location = point.location
        (selection.area == null || location.area == selection.area) &&
            (selection.building == null || location.building == selection.building) &&
            (selection.floor == null || location.floor == selection.floor)
    }

    fun buildings(points: List<LaundryPoint>, area: DormArea?): List<Int> = points.asSequence()
        .filter { area == null || it.location.area == area }
        .mapNotNull { it.location.building }
        .distinct()
        .sorted()
        .toList()

    fun floors(points: List<LaundryPoint>, area: DormArea?, building: Int?): List<Int> =
        points.asSequence()
            .filter { area == null || it.location.area == area }
            .filter { building == null || it.location.building == building }
            .mapNotNull { it.location.floor }
            .distinct()
            .sorted()
            .toList()
}

object LaundryFilterReducer {
    fun selectArea(current: LaundryFilterSelection, area: DormArea?): LaundryFilterSelection =
        current.copy(area = area, building = null, floor = null)

    fun selectBuilding(current: LaundryFilterSelection, building: Int?): LaundryFilterSelection =
        current.copy(building = building, floor = null)

    fun selectFloor(current: LaundryFilterSelection, floor: Int?): LaundryFilterSelection =
        current.copy(floor = floor)
}

object LaundryLastLocationCodec {
    fun encode(owner: String, location: LaundryLastLocation): String = JSONObject()
        .put("v", VERSION)
        .put("owner", owner.requireValidOwner())
        .put("area", location.area.name)
        .put("building", location.building ?: JSONObject.NULL)
        .put("floor", location.floor ?: JSONObject.NULL)
        .toString()

    fun decode(raw: String?, expectedOwner: String): LaundryLastLocation? = runCatching {
        if (raw.isNullOrBlank() || expectedOwner.isBlank()) return null
        LaundryJsonSafety.requireSafeShape(raw)
        val tokener = JSONTokener(raw)
        val root = tokener.nextValue() as? JSONObject ?: return null
        if (tokener.nextClean() != '\u0000') return null
        if (root.keys().asSequence().toSet() != REQUIRED_KEYS) return null
        if (root.opt("v") != VERSION) return null
        if (root.opt("owner") != expectedOwner) return null
        val areaName = root.opt("area") as? String ?: return null
        val area = DormArea.entries.firstOrNull { it.name == areaName }
            ?.takeIf { it != DormArea.OTHER }
            ?: return null
        val building = optionalLevel(root, "building") ?: if (root.isNull("building")) null else return null
        val floor = optionalLevel(root, "floor") ?: if (root.isNull("floor")) null else return null
        LaundryLastLocation(area, building, floor)
    }.getOrNull()

    private fun optionalLevel(root: JSONObject, key: String): Int? {
        val value = root.opt(key)
        if (value == null || value == JSONObject.NULL) return null
        val number = value as? Number ?: return null
        val long = number.toLong()
        if (!number.toDouble().isFinite() || number.toDouble() != long.toDouble()) return null
        return long.takeIf { it in 1..99 }?.toInt()
    }

    private fun String.requireValidOwner(): String = apply {
        require(isNotBlank() && length <= MAX_OWNER_LENGTH) { "账号标识异常" }
    }

    private const val VERSION = 1
    private const val MAX_OWNER_LENGTH = 64
    private val REQUIRED_KEYS = setOf("v", "owner", "area", "building", "floor")
}

object LaundryTime {
    private val completionTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun remainingMinutes(now: LocalDateTime, finishTime: LocalDateTime?): Long? {
        finishTime ?: return null
        val seconds = Duration.between(now, finishTime).seconds
        if (seconds <= 0L) return 0L
        return (seconds + 59L) / 60L
    }

    fun completionLabel(now: LocalDateTime, finishTime: LocalDateTime?): String? {
        finishTime ?: return null
        val absolute = finishTime.format(completionTimeFormatter)
        val remaining = remainingMinutes(now, finishTime) ?: return null
        return if (remaining > 0L) {
            "预计 $absolute 完成（还有 $remaining 分钟）"
        } else {
            "预计 $absolute 完成"
        }
    }
}

object LaundryResponseValidator {
    private val finishTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
        .withResolverStyle(ResolverStyle.STRICT)

    fun point(
        positionId: Long,
        name: String,
        address: String,
        organizationId: Long,
        idleCount: Int,
        categoryCodes: List<String>,
        shopId: Long? = null
    ): LaundryPoint? {
        if (!LaundryCampusPolicy.accepts(organizationId, address)) return null
        if (positionId <= 0L || name.isBlank() || name.length > 120) return null
        if (idleCount !in 0..10_000) return null
        val categories = categoryCodes.mapNotNull(LaundryCategory::fromCode).toSet()
        if (categories.isEmpty()) return null
        val cleanName = name.trim()
        if (LaundryPointPolicy.isExcluded(cleanName)) return null
        val location = LaundryLocationHintPolicy.enrich(
            LaundryLocationParser.parse(cleanName),
            shopId
        ) ?: return null
        if (location.area == DormArea.ZHU && location.building == 1) return null
        val displayName = LaundryLocationFormatter.format(location) ?: return null
        return LaundryPoint(
            positionId = positionId,
            name = displayName,
            address = address.trim(),
            idleCount = idleCount,
            categories = categories,
            location = location
        )
    }

    fun device(
        deviceId: Long,
        name: String,
        floorCode: String,
        stateCode: Int,
        finishTime: String?
    ): LaundryDevice? {
        if (deviceId <= 0L || name.isBlank() || name.length > 120) return null
        if (floorCode.length > 20) return null
        val parsedFinishTime = finishTime?.takeIf(String::isNotBlank)?.let { value ->
            runCatching { LocalDateTime.parse(value, finishTimeFormatter) }.getOrNull()
        }
        return LaundryDevice(
            deviceId = deviceId,
            name = name.trim(),
            floorCode = floorCode.trim(),
            state = LaundryDeviceState.fromCode(stateCode),
            finishTime = parsedFinishTime
        )
    }
}

internal object LaundryEndpointPolicy {
    const val OFFICIAL_HOST = "yshz-user.haier-ioc.com"
    private val allowedPaths = setOf(
        "/position/nearPosition",
        "/position/deviceDetailPage"
    )

    fun requireAllowed(baseUrl: HttpUrl, path: String) {
        check(baseUrl.scheme == "https" && baseUrl.host == OFFICIAL_HOST && baseUrl.port == 443) {
            "洗衣房服务地址不受信任"
        }
        check(path in allowedPaths) { "洗衣房接口不在只读白名单中" }
    }

    fun requireAllowedUrl(url: HttpUrl) {
        requireAllowed(url, url.encodedPath)
        check(url.querySize == 0) { "洗衣房接口包含非预期查询参数" }
    }
}

internal object LaundryJsonSafety {
    private const val MAX_DEPTH = 32

    fun requireSafeShape(raw: String) {
        var depth = 0
        var inString = false
        var escaped = false
        raw.forEach { char ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    '{', '[' -> {
                        depth += 1
                        if (depth > MAX_DEPTH) {
                            throw LaundryException("海乐生活响应嵌套过深")
                        }
                    }
                    '}', ']' -> {
                        depth -= 1
                        if (depth < 0) throw LaundryException("海乐生活响应结构异常")
                    }
                }
            }
        }
        if (inString || escaped || depth != 0) {
            throw LaundryException("海乐生活响应结构异常")
        }
    }
}

internal data class LaundryRefreshTicket(
    val generation: Long,
    val query: LaundryPointQuery
)

internal data class LaundryRefreshSession(
    val query: LaundryPointQuery,
    val loadingQuery: LaundryPointQuery? = null,
    val generation: Long = 0L
) {
    fun edit(newQuery: LaundryPointQuery): LaundryRefreshSession =
        if (newQuery == query) this
        else copy(query = newQuery, loadingQuery = null, generation = generation + 1L)

    fun beginLoad(): Pair<LaundryRefreshSession, LaundryRefreshTicket> {
        val nextGeneration = generation + 1L
        val loading = copy(loadingQuery = query, generation = nextGeneration)
        return loading to LaundryRefreshTicket(nextGeneration, query)
    }

    fun canCommit(ticket: LaundryRefreshTicket): Boolean =
        generation == ticket.generation && loadingQuery == ticket.query
}

internal object LaundryPaginationPolicy {
    const val MAX_PAGES = 5
    const val PAGE_SIZE = 100

    fun requireComplete(received: Int, reportedTotal: Int) {
        check(reportedTotal in 0..MAX_PAGES * PAGE_SIZE) {
            "洗衣房响应数量异常，请稍后重试"
        }
        check(received == reportedTotal) {
            "洗衣房响应分页不完整（已获取 $received / $reportedTotal），请稍后重试"
        }
    }
}

internal class LaundryPageAccumulator<T>(private val keyOf: (T) -> Long) {
    private val receivedKeys = mutableSetOf<Long>()
    private val result = linkedMapOf<Long, T>()
    private var expectedTotal: Int? = null

    fun accept(expectedPage: Int, page: LaundryParsedPage<T>): Boolean {
        if (page.page != expectedPage || page.rawKeys.size != page.rawItemCount) {
            throw LaundryException("洗衣房响应页码异常，请稍后重试")
        }
        val total = expectedTotal ?: page.total.also { expectedTotal = it }
        if (page.total != total) {
            throw LaundryException("洗衣房数据刷新中，请稍后重试")
        }
        val previousCount = receivedKeys.size
        receivedKeys += page.rawKeys
        page.items.forEach { result.putIfAbsent(keyOf(it), it) }
        if (receivedKeys.size > total) {
            throw LaundryException("洗衣房分页总数异常，请稍后重试")
        }
        if (receivedKeys.size == total) return true
        if (page.rawItemCount == 0 || receivedKeys.size == previousCount) {
            LaundryPaginationPolicy.requireComplete(receivedKeys.size, total)
        }
        return false
    }

    fun requireResult(): List<T> {
        LaundryPaginationPolicy.requireComplete(receivedKeys.size, expectedTotal ?: 0)
        return result.values.toList()
    }
}
