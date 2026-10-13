package com.cqmu.assistant.data.ekta

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in contract check against the official service. Credentials are supplied only through
 * process environment variables and are never written to source files or test reports.
 */
class EktaLiveAchievementDetailsTest {
    @Test
    fun `official service returns five ordered category details`() {
        val account = System.getenv("EKTA_LIVE_ACCOUNT").orEmpty()
        val password = System.getenv("EKTA_LIVE_PASSWORD").orEmpty()
        assumeTrue("EKTA live credentials are not configured", account.isNotBlank() && password.isNotEmpty())

        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val login = Request.Builder()
            .url("https://ekta.cqmu.edu.cn/api/app/client/v1/token")
            .post(EktaRequestFactory.loginBody(account, password))
            .header("Accept", "application/json")
            .build()
        val token = client.newCall(login).execute().use { response ->
            assertTrue(response.isSuccessful)
            EktaJsonParser.parseToken(
                EktaResponsePolicy.parseJsonPayload(response.body?.contentType(), response.body?.string().orEmpty())
            )
        }
        val request = Request.Builder()
            .url("https://ekta.cqmu.edu.cn/api/app/client/v1/student/achievement/by-classify-list")
            .header("Accept", "application/json")
            .header("Authorization", token)
            .build()
        val details = client.newCall(request).execute().use { response ->
            assertTrue(response.isSuccessful)
            EktaJsonParser.parseAchievementDetails(
                EktaResponsePolicy.parseJsonPayload(response.body?.contentType(), response.body?.string().orEmpty())
            )
        }

        assertEquals(listOf("德育", "智育", "体育", "美育", "劳育"), details.categories.map { it.name })
        details.categories.forEach { category ->
            for (index in 1 until category.records.size) {
                assertTrue(
                    "分类记录未按从新到旧排序，索引 $index",
                    category.records[index - 1].timestampMillis >= category.records[index].timestampMillis
                )
            }
        }
    }
}
