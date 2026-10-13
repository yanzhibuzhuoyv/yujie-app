package com.cqmu.assistant.data.laundry

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

class LaundryException(message: String, cause: Throwable? = null) : IOException(message, cause)

interface LaundryDataSource {
    suspend fun fetchPoints(category: LaundryCategory): List<LaundryPoint>
    suspend fun fetchDevices(positionId: Long, category: LaundryCategory): List<LaundryDevice>
}

class LaundryRepository private constructor(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl
) : LaundryDataSource {
    override suspend fun fetchPoints(category: LaundryCategory): List<LaundryPoint> =
        withContext(Dispatchers.IO) {
            val points = fetchAllPages(keyOf = LaundryPoint::positionId) { page ->
                val payload = JSONObject()
                    .put("lng", CAMPUS_LONGITUDE)
                    .put("lat", CAMPUS_LATITUDE)
                    .put("categoryCode", category.code)
                    .put("page", page)
                    .put("pageSize", LaundryPaginationPolicy.PAGE_SIZE)
                LaundryJsonParser.parsePointPage(post(NEAR_POSITION_PATH, payload))
            }
            if (points.isEmpty()) {
                throw LaundryException("海乐生活暂未返回缙云校区的${category.displayName}数据")
            }
            if (points.map { it.positionId }.distinct().size != points.size) {
                throw LaundryException("海乐生活洗衣点数据重复，请稍后重试")
            }
            points.sortedWith(
                compareBy<LaundryPoint> { it.location.area.ordinal }
                    .thenBy { it.location.building ?: Int.MAX_VALUE }
                    .thenBy { it.location.floor ?: Int.MAX_VALUE }
                    .thenBy { it.name }
            )
        }

    override suspend fun fetchDevices(
        positionId: Long,
        category: LaundryCategory
    ): List<LaundryDevice> = withContext(Dispatchers.IO) {
        if (positionId <= 0L) throw LaundryException("洗衣点编号无效")
        fetchAllPages(keyOf = LaundryDevice::deviceId) { page ->
            val payload = JSONObject()
                .put("positionId", positionId)
                .put("categoryCode", category.code)
                .put("page", page)
                .put("floorCode", "")
                .put("pageSize", LaundryPaginationPolicy.PAGE_SIZE)
            LaundryJsonParser.parseDevicePage(post(DEVICE_DETAIL_PATH, payload))
        }
    }

    private suspend fun <T> fetchAllPages(
        keyOf: (T) -> Long,
        loader: suspend (Int) -> LaundryParsedPage<T>
    ): List<T> {
        val accumulator = LaundryPageAccumulator(keyOf)
        for (page in 1..LaundryPaginationPolicy.MAX_PAGES) {
            val parsed = loader(page)
            if (accumulator.accept(page, parsed)) return accumulator.requireResult()
        }
        return accumulator.requireResult()
    }

    private suspend fun post(path: String, payload: JSONObject): JSONObject {
        LaundryEndpointPolicy.requireAllowed(baseUrl, path)
        val url = baseUrl.resolve(path) ?: throw LaundryException("洗衣房服务地址无效")
        LaundryEndpointPolicy.requireAllowedUrl(url)
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("appType", "2")
            .header("appVersion", "1.8.9")
            .header("Origin", OFFICIAL_H5_ORIGIN)
            .header("Referer", "$OFFICIAL_H5_ORIGIN/")
            .header("User-Agent", MOBILE_USER_AGENT)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val raw = execute(request)
        return try {
            LaundryJsonParser.parseRoot(raw)
        } catch (error: LaundryException) {
            throw error
        } catch (error: Exception) {
            throw LaundryException("海乐生活响应格式已变化，请稍后更新应用", error)
        }
    }

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWith(
                        Result.failure(
                            LaundryException("无法连接海乐生活，请检查网络后重试", e)
                        )
                    )
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    Result.success(response.use(::readResponse))
                } catch (error: LaundryException) {
                    Result.failure(error)
                } catch (error: IOException) {
                    Result.failure(LaundryException("无法读取海乐生活响应，请稍后重试", error))
                } catch (error: Exception) {
                    Result.failure(LaundryException("海乐生活响应校验失败，请稍后重试", error))
                }
                if (continuation.isActive) continuation.resumeWith(result)
            }
        })
    }

    private fun readResponse(response: Response): String {
        LaundryEndpointPolicy.requireAllowedUrl(response.request.url)
        if (!response.isSuccessful) {
            throw LaundryException("海乐生活服务暂不可用（HTTP ${response.code}）")
        }
        val body = response.body ?: throw LaundryException("海乐生活返回了空响应")
        val contentType = body.contentType()
        if (contentType?.type != "application" || contentType.subtype != "json") {
            throw LaundryException("海乐生活返回了非 JSON 响应")
        }
        val length = body.contentLength()
        if (length > MAX_RESPONSE_BYTES) throw LaundryException("海乐生活响应过大")
        val source = body.source()
        source.request(MAX_RESPONSE_BYTES + 1L)
        if (source.buffer.size > MAX_RESPONSE_BYTES) {
            throw LaundryException("海乐生活响应过大")
        }
        return source.buffer.clone().readString(Charsets.UTF_8)
    }

    companion object {
        private const val NEAR_POSITION_PATH = "/position/nearPosition"
        private const val DEVICE_DETAIL_PATH = "/position/deviceDetailPage"
        private const val OFFICIAL_H5_ORIGIN = "https://h5.haier-ioc.com"
        private const val CAMPUS_LONGITUDE = 106.303879
        private const val CAMPUS_LATITUDE = 29.624480
        private const val MAX_RESPONSE_BYTES = 2_000_000
        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/128.0 Mobile Safari/537.36"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val OFFICIAL_BASE_URL = "https://${LaundryEndpointPolicy.OFFICIAL_HOST}/".toHttpUrl()

        fun create(): LaundryRepository = LaundryRepository(
            client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(true)
                .build(),
            baseUrl = OFFICIAL_BASE_URL
        )
    }
}
