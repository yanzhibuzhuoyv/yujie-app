package com.cqmu.assistant.data.ekta

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EktaAchievementDetailsTest {
    @Test
    fun `endpoint policy permits only exact achievement detail path`() {
        val base = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("ekta.cqmu.edu.cn")
            .addPathSegments("api/app/client/v1/")
            .build()

        EktaEndpointPolicy.requireAllowed(
            base,
            "/api/app/client/v1/student/achievement/by-classify-list"
        )
        assertTrue(
            runCatching {
                EktaEndpointPolicy.requireAllowed(
                    base,
                    "/api/app/client/v1/student/achievement/by-classify-list/德育"
                )
            }.isFailure
        )
    }

    @Test
    fun `detail parser keeps fixed five and sorts records newest first stably`() {
        val root = payload(
            record("旧记录", 1.0, 1_700_000_000_000),
            record("同刻第一条", 2.0, 1_720_000_000_000),
            record("同刻第二条", 3.0, 1_720_000_000_000)
        )

        val parsed = EktaJsonParser.parseAchievementDetails(
            root,
            nowMillis = 1_800_000_000_000
        )

        assertEquals(listOf("德育", "智育", "体育", "美育", "劳育"), parsed.categories.map { it.name })
        assertEquals(
            listOf("同刻第一条", "同刻第二条", "旧记录"),
            parsed.categories.first().records.map { it.name }
        )
        assertEquals(96.0, parsed.categories.first().totalHours, 0.0)
        assertEquals(1_720_000_000_000, parsed.categories.first().records.first().timestampMillis)
    }

    @Test
    fun `detail parser rejects missing duplicate and malformed records`() {
        val missing = payload().also {
            it.getJSONArray("data").remove(4)
        }
        val duplicate = payload().also {
            it.getJSONArray("data").put(category("德育", JSONArray()))
        }
        val invalidType = payload(record("错误类型", 1.0, 1_720_000_000_000).put("type", "3"))
        val invalidTime = payload(record("错误时间", 1.0, 1_720_000_000_000).put("time", 1))
        val invalidName = payload(record(" ", 1.0, 1_720_000_000_000))

        listOf(missing, duplicate, invalidType, invalidTime, invalidName).forEach { root ->
            assertTrue(
                runCatching {
                    EktaJsonParser.parseAchievementDetails(root, 1_800_000_000_000)
                }.isFailure
            )
        }
    }

    @Test
    fun `detail snapshot preserves records update time and rejects incomplete cache`() {
        val details = EktaJsonParser.parseAchievementDetails(
            payload(record("测试活动", 2.5, 1_720_000_000_000)),
            nowMillis = 1_800_000_000_000
        )
        val cached = EktaCachedAchievementDetails(
            details = details,
            updatedAtMillis = 1_800_000_000_000
        )

        val encoded = EktaAchievementDetailsSnapshotCodec.encode(cached)
        val decoded = EktaAchievementDetailsSnapshotCodec.decode(
            encoded,
            nowMillis = 1_800_000_000_000
        )
        val incomplete = JSONObject(encoded).also {
            it.getJSONObject("payload").getJSONArray("data").remove(0)
        }

        assertEquals(cached, decoded)
        assertTrue(
            runCatching {
                EktaAchievementDetailsSnapshotCodec.decode(
                    incomplete.toString(),
                    nowMillis = 1_800_000_000_000
                )
            }.isFailure
        )
    }

    private fun payload(vararg moralRecords: JSONObject): JSONObject {
        val data = JSONArray()
        val names = listOf("德育", "智育", "体育", "美育", "劳育")
        names.forEachIndexed { index, name ->
            val records = if (index == 0) JSONArray().also { array ->
                moralRecords.forEach(array::put)
            } else JSONArray()
            data.put(category(name, records))
        }
        data.put(category("工作履历", JSONArray()))
        return JSONObject().put("code", 0).put("data", data)
    }

    private fun category(name: String, records: JSONArray): JSONObject = JSONObject()
        .put("classifyName", name)
        .put("classifyId", 100)
        .put("classifyHours", "96")
        .put("minHours", 40)
        .put("termMinHours", 0)
        .put("hoursRecordList", records)

    private fun record(name: String, hours: Double, time: Long): JSONObject = JSONObject()
        .put("amount", hours)
        .put("hours", hours)
        .put("sourceType", 1)
        .put("classifyId", "100")
        .put("name", name)
        .put("relationId", 200)
        .put("time", time)
        .put("type", "1")
}
