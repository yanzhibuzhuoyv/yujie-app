package com.cqmu.assistant.data.ekta

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EktaPolicyTest {
    @Test
    fun `achievement cache is invalidated whenever owner or account changes`() {
        assertFalse(EktaCacheOwnershipPolicy.identityChanged("owner", "account", "owner", "account"))
        assertTrue(EktaCacheOwnershipPolicy.identityChanged("old", "account", "new", "account"))
        assertTrue(EktaCacheOwnershipPolicy.identityChanged("owner", "old", "owner", "new"))
        assertTrue(EktaCacheOwnershipPolicy.identityChanged(null, null, "owner", "account"))
    }

    @Test
    fun `response policy accepts official text plain only when payload is json object`() {
        EktaResponsePolicy.parseJsonPayload(
            "text/plain;charset=utf-8".toMediaType(),
            """{"code":0,"data":{}}"""
        )
        EktaResponsePolicy.parseJsonPayload(
            "application/json".toMediaType(),
            """{"code":0}"""
        )

        assertTrue(
            runCatching {
                EktaResponsePolicy.parseJsonPayload(
                    "text/plain".toMediaType(),
                    "<html>login</html>"
                )
            }.isFailure
        )
        assertTrue(
            runCatching {
                EktaResponsePolicy.parseJsonPayload(
                    "text/html".toMediaType(),
                    """{"code":0}"""
                )
            }.isFailure
        )
        assertTrue(
            runCatching {
                EktaResponsePolicy.parseJsonPayload(
                    "text/plain".toMediaType(),
                    """{"code":0}<html>error</html>"""
                )
            }.isFailure
        )
    }

    @Test
    fun `endpoint policy permits only official read endpoints`() {
        val official = "https://ekta.cqmu.edu.cn/api/app/client/v1/".toHttpUrl()

        EktaEndpointPolicy.requireAllowed(official, "/api/app/client/v1/token")
        EktaEndpointPolicy.requireAllowed(
            official,
            "/api/app/client/v1/student/achievement/detail-app"
        )

        assertTrue(
            runCatching {
                EktaEndpointPolicy.requireAllowed(
                    "https://example.com/api/app/client/v1/".toHttpUrl(),
                    "/api/app/client/v1/token"
                )
            }.isFailure
        )
        assertTrue(
            runCatching {
                EktaEndpointPolicy.requireAllowed(
                    official,
                    "/api/app/client/v1/activity/register"
                )
            }.isFailure
        )
    }

    @Test
    fun `achievement parser keeps total and fixed five dimensions only`() {
        val parsed = EktaJsonParser.parseAchievement(
            JSONObject(
                """{
                  "code":0,
                  "data":{
                    "user":{"score":7.5,"hours":120},
                    "tags":[
                      {"name":"德育","userValue":10},
                      {"name":"智育","userValue":20},
                      {"name":"体育","userValue":30},
                      {"name":"美育","userValue":40},
                      {"name":"劳育","userValue":50},
                      {"name":"工作履历","userValue":999}
                    ]
                  }
                }""".trimIndent()
            )
        )

        assertEquals(7.5, parsed.totalScore, 0.0)
        assertEquals(120.0, parsed.totalHours ?: -1.0, 0.0)
        assertEquals(
            listOf("德育", "智育", "体育", "美育", "劳育"),
            parsed.dimensions.map { it.name }
        )
        assertEquals(listOf(10.0, 20.0, 30.0, 40.0, 50.0), parsed.dimensions.map { it.score })
        assertFalse(parsed.toString().contains("工作履历"))
    }

    @Test
    fun `achievement parser accepts bounded decimal strings returned by official api`() {
        val parsed = EktaJsonParser.parseAchievement(
            JSONObject(
                """{"code":0,"data":{"user":{"score":"7.5","hours":"120.5"},"tags":[
                  {"name":"德育","userValue":"10"},
                  {"name":"智育","userValue":"20.25"},
                  {"name":"体育","userValue":"0.0"},
                  {"name":"美育","userValue":"40"},
                  {"name":"劳育","userValue":"50.5"}
                ]}}"""
            )
        )

        assertEquals(120.5, parsed.totalHours ?: -1.0, 0.0)
        assertEquals(listOf(10.0, 20.25, 0.0, 40.0, 50.5), parsed.dimensions.map { it.score })
    }

    @Test
    fun `live achievement requires safe total hours`() {
        listOf(
            null,
            JSONObject.NULL,
            " 120",
            "1.2e2",
            -1,
            true,
            "1000000.1",
            "1.1234567"
        ).forEach { value ->
            val root = achievementPayload(1)
            if (value == null) root.getJSONObject("data").getJSONObject("user").remove("hours")
            else root.getJSONObject("data").getJSONObject("user").put("hours", value)

            assertTrue(
                "应拒绝总学时 $value",
                runCatching { EktaJsonParser.parseAchievement(root) }.isFailure
            )
        }
    }

    @Test
    fun `achievement snapshot preserves hours and reads legacy snapshot without hours`() {
        val achievement = EktaAchievement(
            totalScore = 7.5,
            totalHours = 120.0,
            dimensions = listOf(
                EktaDimensionScore("德育", 10.0),
                EktaDimensionScore("智育", 20.0),
                EktaDimensionScore("体育", 30.0),
                EktaDimensionScore("美育", 40.0),
                EktaDimensionScore("劳育", 50.0)
            )
        )

        val encoded = EktaAchievementSnapshotCodec.encode(achievement)
        val decoded = EktaAchievementSnapshotCodec.decode(encoded)
        val legacyRoot = JSONObject(encoded)
        legacyRoot.getJSONObject("data").getJSONObject("user").remove("hours")
        val legacy = EktaAchievementSnapshotCodec.decode(legacyRoot.toString())
        val corruptRoot = JSONObject(encoded)
        corruptRoot.getJSONObject("data").getJSONObject("user").put("hours", JSONObject.NULL)

        assertEquals(achievement, decoded)
        assertEquals(7.5, legacy.totalScore, 0.0)
        assertNull(legacy.totalHours)
        assertEquals(achievement.dimensions, legacy.dimensions)
        assertTrue(
            runCatching { EktaAchievementSnapshotCodec.decode(corruptRoot.toString()) }.isFailure
        )
    }

    @Test
    fun `achievement parser rejects unsafe decimal string variants`() {
        listOf(" 1", "1e2", "-1", "01", "1000000.1", "1.1234567").forEach { value ->
            assertTrue(
                "应拒绝 $value",
                runCatching { EktaJsonParser.parseAchievement(achievementPayload(value)) }.isFailure
            )
        }
    }

    @Test
    fun `login parser requires bounded token and never retains password`() {
        val token = EktaJsonParser.parseToken(
            JSONObject("""{"code":0,"data":{"access_token":"safe-token"}}""")
        )

        assertEquals("safe-token", token)
        assertTrue(
            runCatching {
                EktaJsonParser.parseToken(
                    JSONObject("""{"code":0,"data":{"access_token":"${"x".repeat(9000)}"}}""")
                )
            }.isFailure
        )
    }

    @Test
    fun `achievement parser rejects missing or mistyped fixed dimensions`() {
        val missingLabor = JSONObject(
            """{"code":0,"data":{"user":{"score":1,"hours":20},"tags":[
              {"name":"德育","userValue":1},{"name":"智育","userValue":1},
              {"name":"体育","userValue":1},{"name":"美育","userValue":1}
            ]}}"""
        )
        val malformedStringScore = JSONObject(
            """{"code":0,"data":{"user":{"score":1,"hours":20},"tags":[
              {"name":"德育","userValue":"1 point"},{"name":"智育","userValue":1},
              {"name":"体育","userValue":1},{"name":"美育","userValue":1},
              {"name":"劳育","userValue":1}
            ]}}"""
        )

        assertTrue(runCatching { EktaJsonParser.parseAchievement(missingLabor) }.isFailure)
        assertTrue(runCatching { EktaJsonParser.parseAchievement(malformedStringScore) }.isFailure)
    }

    @Test
    fun `unknown login business error is not classified as a bad password`() {
        val failure = runCatching {
            EktaJsonParser.parseToken(JSONObject("""{"code":503,"data":{}}"""))
        }.exceptionOrNull()

        assertTrue(failure is EktaException)
        assertFalse(failure is EktaCredentialException)
    }

    @Test
    fun `official login payload is encoded before form encoding`() {
        val body = EktaRequestFactory.loginBody("2024000000", "a&b c")
        val rendered = bodyToString(body)

        assertTrue(rendered.startsWith("params=%257B"))
        assertTrue(rendered.contains("%2526"))
        assertFalse(rendered.contains("a&b c"))
        assertFalse(rendered.contains("real-user-account"))
    }

    @Test
    fun `secret models redact password and token from string output`() {
        val credentials = EktaCredentials("sensitive-account", "plain-secret")
        val session = EktaSession("sensitive-owner", "sensitive-account", "private-token")

        assertFalse(credentials.toString().contains("plain-secret"))
        assertFalse(credentials.toString().contains("sensitive-account"))
        assertFalse(session.toString().contains("private-token"))
        assertFalse(session.toString().contains("sensitive-owner"))
        assertFalse(session.toString().contains("sensitive-account"))
    }

    private fun bodyToString(body: okhttp3.RequestBody): String {
        val buffer = okio.Buffer()
        body.writeTo(buffer)
        return buffer.readUtf8()
    }

    private fun achievementPayload(value: Any): JSONObject {
        val tags = JSONArray()
        listOf("德育", "智育", "体育", "美育", "劳育").forEach { name ->
            tags.put(JSONObject().put("name", name).put("userValue", value))
        }
        return JSONObject()
            .put("code", 0)
            .put(
                "data",
                JSONObject()
                    .put("user", JSONObject().put("score", 1).put("hours", 20))
                    .put("tags", tags)
            )
    }
}
