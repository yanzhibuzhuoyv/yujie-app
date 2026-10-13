package com.cqmu.assistant.data.ekta

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

object EktaEndpointPolicy {
    private const val HOST = "ekta.cqmu.edu.cn"
    private val paths = setOf(
        "/api/app/client/v1/token",
        "/api/app/client/v1/student/achievement/detail-app",
        "/api/app/client/v1/student/achievement/by-classify-list"
    )

    fun requireAllowed(baseUrl: HttpUrl, path: String) {
        require(baseUrl.scheme == "https" && baseUrl.host == HOST && baseUrl.port == 443) {
            "拒绝访问非官方二课服务"
        }
        require(path in paths) { "拒绝访问未授权的二课接口" }
    }
}

object EktaCacheOwnershipPolicy {
    fun identityChanged(
        storedOwner: String?,
        storedAccount: String?,
        authenticatedOwner: String,
        authenticatedAccount: String
    ): Boolean = storedOwner != authenticatedOwner || storedAccount != authenticatedAccount
}

object EktaRequestFactory {
    private const val SCHOOL_CODE = "10631"

    fun loginBody(account: String, password: String): RequestBody {
        require(account.isNotBlank() && account.length <= 64) { "账号格式不正确" }
        require(password.isNotEmpty() && password.length <= 256) { "密码格式不正确" }
        val json = JSONObject()
            .put("schoolCode", SCHOOL_CODE)
            .put("code", account)
            .put("password", password)
            .put("openId", "")
            .toString()
        // 官方网页先 encodeURI(JSON)，随后再经 application/x-www-form-urlencoded 编码。
        val preEncoded = URLEncoder.encode(json, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
        return FormBody.Builder().add("params", preEncoded).build()
    }
}

object EktaJsonSafety {
    const val MAX_BYTES = 512 * 1024L
    private const val MAX_DEPTH = 16

    fun requireSafeShape(json: String) {
        require(json.toByteArray().size <= MAX_BYTES) { "二课响应过大" }
        var depth = 0
        var inString = false
        var escaped = false
        json.forEach { char ->
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
                        require(depth <= MAX_DEPTH) { "二课响应层级异常" }
                    }
                    '}', ']' -> {
                        depth -= 1
                        require(depth >= 0) { "二课响应格式异常" }
                    }
                }
            }
        }
        require(!inString && depth == 0) { "二课响应格式异常" }
    }
}

/**
 * 二课服务的部分接口会把 JSON 错标为 text/plain。只兼容这一种已验证的
 * 官方行为，并继续拒绝 HTML、缺失类型及普通文本。
 */
object EktaResponsePolicy {
    fun parseJsonPayload(contentType: MediaType?, body: String): JSONObject {
        val isJsonType = contentType?.let {
            it.type.equals("application", true) &&
                (it.subtype.equals("json", true) || it.subtype.endsWith("+json", true))
        } == true
        val isOfficialPlainText = contentType?.let {
            it.type.equals("text", true) && it.subtype.equals("plain", true)
        } == true
        if (!isJsonType && !isOfficialPlainText) {
            throw EktaException("二课服务返回了非 JSON 数据")
        }
        if (!body.trimStart().startsWith('{')) {
            throw EktaException("二课服务返回了非 JSON 数据")
        }
        EktaJsonSafety.requireSafeShape(body)
        return try {
            val tokener = JSONTokener(body)
            val root = tokener.nextValue() as? JSONObject
                ?: throw EktaException("二课响应格式异常")
            if (tokener.nextClean() != '\u0000') throw EktaException("二课响应格式异常")
            root
        } catch (error: EktaException) {
            throw error
        } catch (_: Exception) {
            throw EktaException("二课响应格式异常")
        }
    }
}

object EktaJsonParser {
    private val fixedDimensions = listOf("德育", "智育", "体育", "美育", "劳育")
    private val decimalString = Regex("(?:0|[1-9]\\d{0,6})(?:\\.\\d{1,6})?")
    private val identifierString = Regex("0|[1-9]\\d{0,18}")

    fun parseToken(root: JSONObject): String {
        requireSuccess(root, authentication = false)
        val token = root.optJSONObject("data")?.opt("access_token") as? String
            ?: throw EktaException("二课登录响应缺少令牌")
        if (token.isBlank() || token.length > 8192) throw EktaException("二课登录令牌异常")
        return token
    }

    fun parseAchievement(root: JSONObject): EktaAchievement =
        parseAchievement(root, requireTotalHours = true)

    internal fun parseCachedAchievement(root: JSONObject): EktaAchievement =
        parseAchievement(root, requireTotalHours = false)

    private fun parseAchievement(
        root: JSONObject,
        requireTotalHours: Boolean
    ): EktaAchievement {
        requireSuccess(root, authentication = true)
        val data = root.optJSONObject("data") ?: throw EktaException("二课成绩数据缺失")
        val user = data.optJSONObject("user") ?: throw EktaException("二课成绩数据缺失")
        val total = safeNumber(user.opt("score"))
            ?: throw EktaException("二课总成绩格式异常")
        val totalHours = if (!user.has("hours")) {
            if (requireTotalHours) throw EktaException("二课总学时格式异常")
            null
        } else {
            safeNumber(user.opt("hours")) ?: throw EktaException("二课总学时格式异常")
        }
        val tags = data.optJSONArray("tags") ?: throw EktaException("二课五维成绩数据缺失")
        val values = buildMap<String, Double> {
            for (index in 0 until tags.length()) {
                val tag = tags.optJSONObject(index) ?: continue
                val name = tag.opt("name") as? String ?: continue
                if (name !in fixedDimensions || containsKey(name)) continue
                safeNumber(tag.opt("userValue"))?.let { put(name, it) }
            }
        }
        if (values.size != fixedDimensions.size) {
            throw EktaException("二课五维成绩数据不完整")
        }
        return EktaAchievement(
            totalScore = total,
            totalHours = totalHours,
            dimensions = fixedDimensions.map { name ->
                EktaDimensionScore(name, values.getValue(name))
            }
        )
    }

    fun parseAchievementDetails(
        root: JSONObject,
        nowMillis: Long = System.currentTimeMillis()
    ): EktaAchievementDetails {
        requireSuccess(root, authentication = true)
        val data = root.optJSONArray("data") ?: throw EktaException("二课分类明细数据缺失")
        if (data.length() > MAX_DETAIL_CATEGORIES) throw EktaException("二课分类明细数量异常")
        val categories = linkedMapOf<String, EktaAchievementCategoryDetails>()
        for (index in 0 until data.length()) {
            val category = data.optJSONObject(index) ?: throw EktaException("二课分类明细格式异常")
            val name = category.opt("classifyName") as? String
                ?: throw EktaException("二课分类名称异常")
            if (name !in fixedDimensions) continue
            if (categories.containsKey(name)) throw EktaException("二课分类明细重复")
            categories[name] = parseAchievementCategory(category, name, nowMillis)
        }
        if (categories.size != fixedDimensions.size) throw EktaException("二课分类明细数据不完整")
        return EktaAchievementDetails(fixedDimensions.map(categories::getValue))
    }

    private fun parseAchievementCategory(
        category: JSONObject,
        name: String,
        nowMillis: Long
    ): EktaAchievementCategoryDetails {
        val classifyId = safeIdentifier(category.opt("classifyId"))
            ?: throw EktaException("二课分类编号异常")
        val totalHours = safeNumber(category.opt("classifyHours"))
            ?: throw EktaException("二课分类总学时异常")
        val minimumHours = safeNumber(category.opt("minHours"))
            ?: throw EktaException("二课分类最低学时异常")
        val termMinimumHours = safeNumber(category.opt("termMinHours"))
            ?: throw EktaException("二课分类学期最低学时异常")
        val rawRecords = category.optJSONArray("hoursRecordList")
            ?: throw EktaException("二课得分明细缺失")
        if (rawRecords.length() > MAX_DETAIL_RECORDS_PER_CATEGORY) {
            throw EktaException("二课得分明细数量异常")
        }
        val records = (0 until rawRecords.length()).map { index ->
            val record = rawRecords.optJSONObject(index)
                ?: throw EktaException("二课得分明细格式异常")
            index to parseAchievementRecord(record, nowMillis)
        }.sortedWith(
            compareByDescending<Pair<Int, EktaAchievementRecord>> { it.second.timestampMillis }
                .thenBy { it.first }
        ).map { it.second }
        return EktaAchievementCategoryDetails(
            name = name,
            classifyId = classifyId,
            totalHours = totalHours,
            minimumHours = minimumHours,
            termMinimumHours = termMinimumHours,
            records = records
        )
    }

    private fun parseAchievementRecord(
        record: JSONObject,
        nowMillis: Long
    ): EktaAchievementRecord {
        val rawName = record.opt("name") as? String ?: throw EktaException("二课得分名称异常")
        val name = rawName.trim()
        if (name.isEmpty() || name.length > MAX_DETAIL_NAME_LENGTH || name.any(Char::isISOControl)) {
            throw EktaException("二课得分名称异常")
        }
        val timestamp = safeLong(record.opt("time"))
            ?.takeIf { it in MIN_DETAIL_TIME..(nowMillis + FUTURE_TIME_TOLERANCE) }
            ?: throw EktaException("二课得分时间异常")
        val type = when (record.opt("type")) {
            "1" -> 1
            "2" -> 2
            else -> throw EktaException("二课得分增减类型异常")
        }
        return EktaAchievementRecord(
            amount = safeNumber(record.opt("amount"))
                ?: throw EktaException("二课得分数值异常"),
            hours = safeNumber(record.opt("hours"))
                ?: throw EktaException("二课得分学时异常"),
            sourceType = safeInt(record.opt("sourceType"))
                ?.takeIf { it in 1..999 }
                ?: throw EktaException("二课得分来源异常"),
            classifyId = safeIdentifier(record.opt("classifyId"))
                ?: throw EktaException("二课得分分类编号异常"),
            name = name,
            relationId = safeIdentifier(record.opt("relationId"))
                ?: throw EktaException("二课得分关联编号异常"),
            timestampMillis = timestamp,
            type = type
        )
    }

    private fun requireSuccess(root: JSONObject, authentication: Boolean) {
        val code = (root.opt("code") as? Number)?.toInt()
            ?: throw EktaException("二课响应状态异常")
        if (code == 0) return
        if (authentication && code == 10001) throw EktaAuthenticationException()
        if (!authentication) throw EktaException("二课登录失败，请重新输入账号密码")
        throw EktaException("二课数据获取失败")
    }

    private fun safeNumber(value: Any?): Double? {
        val number = when (value) {
            is Number -> value.toDouble()
            is String -> value
                .takeIf { it.length <= 16 && decimalString.matches(it) }
                ?.toDoubleOrNull()
            else -> null
        } ?: return null
        return number.takeIf { it.isFinite() && it >= 0.0 && it <= 1_000_000.0 }
    }

    private fun safeIdentifier(value: Any?): Long? = when (value) {
        is String -> value.takeIf { identifierString.matches(it) }?.toLongOrNull()
        is Number -> safeLong(value)?.takeIf { it >= 0 }
        else -> null
    }

    private fun safeInt(value: Any?): Int? {
        val number = safeLong(value) ?: return null
        return number.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    }

    private fun safeLong(value: Any?): Long? {
        if (value !is Number) return null
        val long = value.toLong()
        return long.takeIf { value.toDouble().isFinite() && value.toDouble() == long.toDouble() }
    }

    private const val MAX_DETAIL_CATEGORIES = 64
    private const val MAX_DETAIL_RECORDS_PER_CATEGORY = 2_000
    private const val MAX_DETAIL_NAME_LENGTH = 200
    private const val MIN_DETAIL_TIME = 946_684_800_000L
    private const val FUTURE_TIME_TOLERANCE = 7 * 24 * 60 * 60 * 1_000L
}

object EktaAchievementSnapshotCodec {
    fun encode(achievement: EktaAchievement): String {
        val tags = JSONArray()
        achievement.dimensions.forEach { score ->
            tags.put(JSONObject().put("name", score.name).put("userValue", score.score))
        }
        val user = JSONObject().put("score", achievement.totalScore)
        achievement.totalHours?.let { user.put("hours", it) }
        return JSONObject()
            .put("code", 0)
            .put("data", JSONObject().put("user", user).put("tags", tags))
            .toString()
    }

    fun decode(raw: String): EktaAchievement {
        EktaJsonSafety.requireSafeShape(raw)
        return EktaJsonParser.parseCachedAchievement(JSONObject(raw))
    }
}

object EktaAchievementDetailsSnapshotCodec {
    fun encode(cached: EktaCachedAchievementDetails): String {
        val data = JSONArray()
        cached.details.categories.forEach { category ->
            val records = JSONArray()
            category.records.forEach { record ->
                records.put(
                    JSONObject()
                        .put("amount", record.amount)
                        .put("hours", record.hours)
                        .put("sourceType", record.sourceType)
                        .put("classifyId", record.classifyId.toString())
                        .put("name", record.name)
                        .put("relationId", record.relationId)
                        .put("time", record.timestampMillis)
                        .put("type", record.type.toString())
                )
            }
            data.put(
                JSONObject()
                    .put("classifyName", category.name)
                    .put("classifyId", category.classifyId)
                    .put("classifyHours", category.totalHours)
                    .put("minHours", category.minimumHours)
                    .put("termMinHours", category.termMinimumHours)
                    .put("hoursRecordList", records)
            )
        }
        val payload = JSONObject().put("code", 0).put("data", data)
        return JSONObject()
            .put("updatedAt", cached.updatedAtMillis)
            .put("payload", payload)
            .toString()
    }

    fun decode(
        raw: String,
        nowMillis: Long = System.currentTimeMillis()
    ): EktaCachedAchievementDetails {
        EktaJsonSafety.requireSafeShape(raw)
        val root = JSONObject(raw)
        val updatedAt = root.opt("updatedAt") as? Number
        val updatedAtMillis = updatedAt?.toLong()
            ?.takeIf { updatedAt.toDouble() == it.toDouble() && it in 946_684_800_000L..(nowMillis + 86_400_000L) }
            ?: throw EktaException("二课明细缓存时间异常")
        val payload = root.optJSONObject("payload") ?: throw EktaException("二课明细缓存格式异常")
        return EktaCachedAchievementDetails(
            details = EktaJsonParser.parseAchievementDetails(payload, nowMillis),
            updatedAtMillis = updatedAtMillis
        )
    }
}
