package com.cqmu.assistant.data.ekta

import android.content.Context
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

class EktaRepository(context: Context) : EktaDataSource, EktaSessionStorage {
    private val store = EktaSessionStore(context)
    private val baseUrl = "https://ekta.cqmu.edu.cn/api/app/client/v1/".toHttpUrl()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun login(account: String, password: String): String {
        val path = "/api/app/client/v1/token"
        EktaEndpointPolicy.requireAllowed(baseUrl, path)
        val request = Request.Builder()
            .url(baseUrl.resolve("token") ?: throw EktaException("二课登录地址无效"))
            .post(EktaRequestFactory.loginBody(account.trim(), password))
            .header("Accept", "application/json")
            .build()
        return EktaJsonParser.parseToken(executeJson(request))
    }

    override suspend fun achievement(token: String): EktaAchievement {
        if (token.isBlank() || token.length > 8192) throw EktaAuthenticationException()
        val path = "/api/app/client/v1/student/achievement/detail-app"
        EktaEndpointPolicy.requireAllowed(baseUrl, path)
        val request = Request.Builder()
            .url(baseUrl.resolve("student/achievement/detail-app")
                ?: throw EktaException("二课成绩地址无效"))
            .get()
            .header("Accept", "application/json")
            .header("Authorization", token)
            .build()
        return EktaJsonParser.parseAchievement(executeJson(request))
    }

    override suspend fun achievementDetails(token: String): EktaAchievementDetails {
        if (token.isBlank() || token.length > 8192) throw EktaAuthenticationException()
        val path = "/api/app/client/v1/student/achievement/by-classify-list"
        EktaEndpointPolicy.requireAllowed(baseUrl, path)
        val request = Request.Builder()
            .url(baseUrl.resolve("student/achievement/by-classify-list")
                ?: throw EktaException("二课明细地址无效"))
            .get()
            .header("Accept", "application/json")
            .header("Authorization", token)
            .build()
        return EktaJsonParser.parseAchievementDetails(executeJson(request))
    }

    private suspend fun executeJson(request: Request): JSONObject {
        return client.newCall(request).awaitEktaJson()
    }

    override fun revision(): Long = store.revision()
    override fun loadSession(): EktaSession? = store.loadSession()
    override fun loadCredentials(): EktaCredentials? = store.loadCredentials()
    override fun loadAchievement(
        ownerStudentNo: String,
        account: String
    ): EktaAchievement? = store.loadAchievement(ownerStudentNo, account)
    override fun loadAchievementDetails(
        ownerStudentNo: String,
        account: String
    ): EktaCachedAchievementDetails? = store.loadAchievementDetails(ownerStudentNo, account)
    override fun save(
        ownerStudentNo: String,
        account: String,
        password: String,
        token: String,
        expectedRevision: Long
    ): Boolean = store.save(ownerStudentNo, account, password, token, expectedRevision)
    override fun updateToken(
        ownerStudentNo: String,
        account: String,
        token: String,
        expectedRevision: Long
    ): Boolean = store.updateToken(ownerStudentNo, account, token, expectedRevision)
    override fun saveAchievement(
        ownerStudentNo: String,
        account: String,
        achievement: EktaAchievement,
        expectedRevision: Long
    ): Boolean = store.saveAchievement(
        ownerStudentNo,
        account,
        achievement,
        expectedRevision
    )
    override fun saveAchievementDetails(
        ownerStudentNo: String,
        account: String,
        details: EktaCachedAchievementDetails,
        expectedRevision: Long
    ): Boolean = store.saveAchievementDetails(
        ownerStudentNo,
        account,
        details,
        expectedRevision
    )
    override fun clearIfRevision(expectedRevision: Long): Boolean =
        store.clearIfRevision(expectedRevision)
    override fun clear() = store.clear()

}

internal suspend fun Call.awaitEktaJson(): JSONObject =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val parsed = try {
                    readEktaJsonResponse(response)
                } catch (failure: Exception) {
                    continuation.resumeWithException(failure)
                    return
                }
                continuation.resume(parsed)
            }
        })
    }

private fun readEktaJsonResponse(response: Response): JSONObject = response.use { value ->
    if (value.isRedirect) throw EktaException("二课服务返回了未允许的跳转")
    if (value.code == 401 || value.code == 403) throw EktaAuthenticationException()
    if (!value.isSuccessful) throw EktaException("二课服务暂时不可用（HTTP ${value.code}）")
    val body = value.body ?: throw EktaException("二课响应为空")
    val source = body.source()
    source.request(EktaJsonSafety.MAX_BYTES + 1)
    val available = source.buffer.size
    if (available > EktaJsonSafety.MAX_BYTES) throw EktaException("二课响应过大")
    val bytes = source.readByteArray(available)
    val text = bytes.toString(Charsets.UTF_8)
    EktaResponsePolicy.parseJsonPayload(body.contentType(), text)
}
