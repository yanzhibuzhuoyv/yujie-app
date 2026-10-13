package com.cqmu.assistant.data.laundry

import org.json.JSONArray
import org.json.JSONObject

internal object LaundryJsonParser {
    fun parseRoot(raw: String): JSONObject {
        LaundryJsonSafety.requireSafeShape(raw)
        val root = JSONObject(raw)
        if (root.strictInt("code") != 0) {
            throw LaundryException("海乐生活暂时无法提供设备状态")
        }
        return root
    }

    fun parsePointPage(root: JSONObject): LaundryParsedPage<LaundryPoint> {
        val data = requiredData(root)
        val items = requiredItems(data)
        val rawKeys = rawKeys(items)
        val accepted = buildList {
            repeat(items.length()) { index ->
                val item = items.optJSONObject(index) ?: return@repeat
                LaundryResponseValidator.point(
                    positionId = item.strictLong("id") ?: -1L,
                    name = item.safeString("name"),
                    address = item.safeString("address"),
                    organizationId = item.strictLong("organizationId") ?: -1L,
                    idleCount = item.strictInt("idleCount") ?: -1,
                    categoryCodes = item.optJSONArray("categoryCodeList").stringValues(),
                    shopId = item.strictLong("shopId")
                )?.let(::add)
            }
        }
        return page(data, items.length(), rawKeys, accepted)
    }

    fun parseDevicePage(root: JSONObject): LaundryParsedPage<LaundryDevice> {
        val data = requiredData(root)
        val items = requiredItems(data)
        val rawKeys = rawKeys(items)
        val devices = buildList {
            repeat(items.length()) { index ->
                val item = items.optJSONObject(index) ?: return@repeat
                LaundryResponseValidator.device(
                    deviceId = item.strictLong("id") ?: -1L,
                    name = item.safeString("name"),
                    floorCode = item.safeString("floorCode"),
                    stateCode = item.strictInt("state") ?: -1,
                    finishTime = item.nullableString("finishTime")
                )?.let(::add)
            }
        }
        if (devices.size != items.length()) {
            throw LaundryException("海乐生活设备数据不完整，请稍后重试")
        }
        if (devices.map { it.deviceId }.distinct().size != devices.size) {
            throw LaundryException("海乐生活设备数据重复，请稍后重试")
        }
        return page(data, items.length(), rawKeys, devices)
    }

    private fun <T> page(
        data: JSONObject,
        rawCount: Int,
        rawKeys: Set<Long>,
        items: List<T>
    ): LaundryParsedPage<T> {
        val page = data.strictInt("page") ?: -1
        val pageSize = data.strictInt("pageSize") ?: -1
        val total = data.strictInt("total") ?: -1
        if (page !in 1..LaundryPaginationPolicy.MAX_PAGES ||
            pageSize !in 1..200 ||
            total !in 0..LaundryPaginationPolicy.MAX_PAGES * LaundryPaginationPolicy.PAGE_SIZE ||
            rawCount > pageSize + 1
        ) {
            throw LaundryException("海乐生活分页数据异常，请稍后重试")
        }
        return LaundryParsedPage(page, total, rawCount, rawKeys, items)
    }

    private fun rawKeys(items: JSONArray): Set<Long> {
        val keys = buildSet {
            repeat(items.length()) { index ->
                val item = items.optJSONObject(index)
                    ?: throw LaundryException("海乐生活列表数据不完整，请稍后重试")
                val id = item.strictLong("id") ?: -1L
                if (id <= 0L || !add(id)) {
                    throw LaundryException("海乐生活列表编号异常，请稍后重试")
                }
            }
        }
        return keys
    }

    private fun requiredData(root: JSONObject): JSONObject = root.optJSONObject("data")
        ?: throw LaundryException("海乐生活响应缺少数据")

    private fun requiredItems(data: JSONObject): JSONArray = data.optJSONArray("items")
        ?: throw LaundryException("海乐生活响应缺少设备列表")

    private fun JSONObject.safeString(key: String): String =
        (opt(key) as? String).orEmpty().take(256)

    private fun JSONObject.nullableString(key: String): String? =
        (opt(key) as? String)?.take(64)?.ifBlank { null }

    private fun JSONArray?.stringValues(): List<String> {
        if (this == null || length() > 16) return emptyList()
        return buildList {
            repeat(length()) { index ->
                val value = opt(index) as? String ?: return emptyList()
                add(value.take(8))
            }
        }
    }

    private fun JSONObject.strictLong(key: String): Long? = when (val value = opt(key)) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }

    private fun JSONObject.strictInt(key: String): Int? {
        val value = strictLong(key) ?: return null
        return value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    }
}
