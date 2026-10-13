package com.cqmu.assistant.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CancellationException
import com.cqmu.assistant.data.laundry.LaundryLastLocation
import com.cqmu.assistant.data.laundry.LaundryLastLocationCodec
import com.cqmu.assistant.data.laundry.LaundryLocationSession
import com.cqmu.assistant.data.laundry.LaundryLocationStore
import javax.crypto.Cipher

open class EamsException(message: String) : Exception(message)
class NotLoggedInException : EamsException("登录已失效，请重新登录")

/** 登录过程中的一个环节 */
sealed interface LoginStep {
    /** 需要提交账号密码（needCaptcha 表示页面要求验证码） */
    data class Challenge(
        val execution: String,
        val needCaptcha: Boolean,
        val message: String?,
        val mfaEnabled: Boolean = false,
        val accountMaintenanceUrl: String? = null
    ) : LoginStep

    data class Success(val user: UserInfo) : LoginStep
}

/**
 * 完成静默登录恢复。CAS 的单点登录 Cookie 仍有效时，beginLogin 会直接返回
 * Success；此时会话已经恢复，不应再强制要求 execution 或重复提交密码。
 */
internal suspend fun completeSilentLogin(
    step: LoginStep?,
    username: String = "",
    password: String = "",
    submit: suspend (execution: String, username: String, password: String) -> LoginStep
): Boolean = when (step) {
    is LoginStep.Success -> true
    is LoginStep.Challenge -> {
        if (step.execution.isBlank() || step.needCaptcha || username.isBlank() || password.isBlank()) false
        else submit(step.execution, username, password) is LoginStep.Success
    }
    null -> false
}

internal fun canReuseRecoveredSession(
    observedGeneration: Long,
    currentGeneration: Long,
    lastLogoutGeneration: Long,
    sessionActive: Boolean
): Boolean = currentGeneration != observedGeneration &&
    sessionActive && lastLogoutGeneration <= observedGeneration

internal fun canStartSilentAttempt(
    observedGeneration: Long,
    lastAttemptGeneration: Long
): Boolean = observedGeneration != lastAttemptGeneration

/** 学校当前与历史认证入口；数据请求命中任一入口都表示 EAMS 会话已失效。 */
internal fun isAuthenticationRedirect(location: String): Boolean {
    val parsed = location.toHttpUrlOrNull()
    if (parsed != null && parsed.host !in setOf(
            "jiaowu.cqmu.edu.cn",
            "cas.cqmu.edu.cn",
            "sec.cqmu.edu.cn"
        )
    ) return false
    val path = parsed?.encodedPath ?: location.substringBefore('?')
    return path.startsWith("/eams/sso/login") ||
        path.startsWith("/cas/login") ||
        path.startsWith("/cas/logout") ||
        path.startsWith("/rump_frontend/login")
}

/** 空闲教室查询条件 */
data class FreeRoomQuery(
    val date: LocalDate,
    val beginPeriod: Int,
    val endPeriod: Int,
    val campusId: String = "",
    val typeId: String = "",
    val minSeats: String = "",
    val roomName: String = ""
)

/**
 * 教务系统（https://jiaowu.cqmu.edu.cn:8080/eams）网络仓库。
 * 登录走学校统一身份认证 CAS（cas.cqmu.edu.cn），密码经 RSA(PKCS1) 加密提交。
 */
class EamsRepository(context: android.content.Context) : LaundryLocationStore {

    private val store = SessionStore(context)
    private val cookieJar = PersistentCookieJar(store)
    private val freeRoomCacheFile = File(context.filesDir, "free_room_cache.json")
    private val crashReportFile = File(context.filesDir, "crash_latest.txt")
    private val debugLogging = context.applicationInfo.flags and
        android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val reloginMutex = Mutex()
    private val sessionGeneration = AtomicLong(0L)
    private val lastLogoutGeneration = AtomicLong(-1L)
    private val lastSilentAttemptGeneration = AtomicLong(-1L)
    private val sessionActive = AtomicBoolean(store.studentNo.isNotBlank())
    private val sessionLock = Any()
    private var cachedPublicKey: PublicKey? = null
    @Volatile private var knownSemesters: List<Semester> = emptyList()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .eventListener(object : okhttp3.EventListener() {
            override fun callStart(call: okhttp3.Call) {
                if (debugLogging) android.util.Log.i("CQMU_NET", "START ${call.request().url.host}${call.request().url.encodedPath.take(40)}")
            }
            override fun connectStart(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) {
                if (debugLogging) android.util.Log.i("CQMU_NET", "CONNECT $inetSocketAddress proxy=$proxy")
            }
            override fun connectFailed(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy, protocol: okhttp3.Protocol?, ioe: java.io.IOException) {
                if (debugLogging) android.util.Log.i("CQMU_NET", "CONNECT-FAIL $inetSocketAddress ${ioe.javaClass.simpleName}")
            }
            override fun responseHeadersEnd(call: okhttp3.Call, response: okhttp3.Response) {
                if (debugLogging) android.util.Log.i("CQMU_NET", "RESPONSE ${response.code} ${call.request().url.host}${call.request().url.encodedPath.take(40)}")
            }
            override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) {
                if (debugLogging) android.util.Log.i("CQMU_NET", "FAILED ${call.request().url.host}${call.request().url.encodedPath.take(30)} ${ioe.javaClass.simpleName}")
            }
        })
        .addInterceptor(::portFallback)
        .addInterceptor(::defaultHeaders)
        // 必须是网络层拦截器：OkHttp 的重定向发生在应用拦截器之内，
        // 只有在网络层先改写 Location 为 https，才能避免 Android 明文限制
        .addNetworkInterceptor(::forceHttpsRedirect)
        .addNetworkInterceptor(::authRedirectGate)
        .build()

    // ===================== 基础设施 =====================

    /**
     * 端口兜底：部分运营商/网络会拦 8080 出口。jiaowu 的 443 与 8080 是同一套服务，
     * 8080 连接失败（超时/重置等 IOException）时同一请求自动改走 443 重试一次。
     */
    private fun portFallback(chain: Interceptor.Chain): Response {
        val req = chain.request()
        return try {
            chain.proceed(req)
        } catch (e: IOException) {
            if (req.url.host == "jiaowu.cqmu.edu.cn" && req.url.port == 8080) {
                val alt = req.newBuilder()
                    .url(req.url.newBuilder().port(443).build())
                    .build()
                try {
                    chain.proceed(alt)
                } catch (_: IOException) {
                    throw e
                }
            } else {
                throw e
            }
        }
    }

    private fun defaultHeaders(chain: Interceptor.Chain): Response {
        val req = chain.request().newBuilder()
            .header("User-Agent", UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()
        return chain.proceed(req)
    }

    /** 教务/SSO 的 302 常给出 http:// Location，统一改写为 https 以通过 Android 明文限制 */
    private fun forceHttpsRedirect(chain: Interceptor.Chain): Response {
        val res = chain.proceed(chain.request())
        val loc = res.header("Location") ?: return res
        if (loc.startsWith("http://")) {
            return res.newBuilder().header("Location", "https://" + loc.substring(7)).build()
        }
        return res
    }

    /**
     * 会话失效时 EAMS 会 302 到 SSO/CAS，跟随会让服务端下发新的未登录
     * JSESSIONID，把本地仍有效的会话 Cookie 覆盖掉（“查一次空教室后课表
     * 也要求重新登录”的根因）。这里把认证重定向改写为 401 原地返回：
     * Cookie 不被污染，上层据此抛 NotLoggedInException 并可静默重登录。
     * 登录流程自身的请求带 FOLLOW_AUTH 头，仍然正常跟随。
     */
    private fun authRedirectGate(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val res = chain.proceed(req)
        if (!res.isRedirect) return res
        val loc = res.header("Location").orEmpty()
        val authRedirect = isAuthenticationRedirect(loc)
        if (authRedirect && req.header(FOLLOW_AUTH) == null) {
            return res.newBuilder().code(401).message("Session expired").build()
        }
        return res
    }

    private inner class PersistentCookieJar(private val store: SessionStore) : CookieJar {
        private val map = ConcurrentHashMap<String, Cookie>()

        init {
            // 静默恢复；解析失败视为无会话，走重新登录
            runCatching { restore() }
        }

        private fun keyOf(c: Cookie) = "${c.domain}|${c.path}|${c.name}"

        private fun restore() {
            val obj = JSONObject(store.loadCookies().let { m ->
                JSONObject().also { o -> m.forEach { (k, v) -> o.put(k, v) } }.toString()
            })
            for (key in obj.keys()) {
                val o = obj.optJSONObject(key) ?: continue
                val cookie = runCatching {
                    val persistent = o.optBoolean("persistent", false)
                    val expiresAt = o.optLong("expiresAt", Long.MAX_VALUE)
                    if (persistent && expiresAt <= System.currentTimeMillis()) return@runCatching null
                    val builder = Cookie.Builder()
                        .name(o.optString("name"))
                        .value(o.optString("value"))
                        .path(o.optString("path"))
                    val domain = o.optString("domain")
                    if (o.optBoolean("hostOnly", false)) builder.hostOnlyDomain(domain)
                    else builder.domain(domain)
                    if (persistent) builder.expiresAt(expiresAt)
                    if (o.optBoolean("secure", false)) builder.secure()
                    if (o.optBoolean("httpOnly", false)) builder.httpOnly()
                    builder.build()
                }.getOrNull() ?: continue
                map[key] = cookie
            }
        }

        @Synchronized
        fun persist() {
            val obj = JSONObject()
            map.forEach { (k, c) ->
                obj.put(k, JSONObject().put("name", c.name).put("value", c.value)
                    .put("domain", c.domain).put("path", c.path)
                    .put("expiresAt", c.expiresAt).put("persistent", c.persistent)
                    .put("secure", c.secure).put("httpOnly", c.httpOnly)
                    .put("hostOnly", c.hostOnly))
            }
            store.saveCookies(JSONObject(obj.toString()).let { o ->
                buildMap {
                    for (key in o.keys()) put(key, o.optJSONObject(key)?.toString() ?: "")
                }
            })
        }

        @Synchronized
        fun clearAll() {
            map.clear()
            store.saveCookies(emptyMap())
        }

        @Synchronized
        fun clearHost(host: String) {
            map.keys.filter { it.contains(host) }.forEach { map.remove(it) }
            persist()
        }

        /** 手动写入 semester.id 会话偏好 */
        @Synchronized
        fun setSemesterCookie(semesterId: Long) {
            val cookie = Cookie.Builder()
                .name("semester.id")
                .value(semesterId.toString())
                .hostOnlyDomain("jiaowu.cqmu.edu.cn")
                .path("/eams/")
                .secure()
                .build()
            map.entries.removeIf {
                it.value.name == cookie.name && it.value.domain == cookie.domain
            }
            map[keyOf(cookie)] = cookie
            persist()
        }

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (debugLogging) cookies.forEach { c ->
                android.util.Log.i("CQMU_COOKIE",
                    "SAVE ${c.name} dom=${c.domain} path=${c.path} from=${url.host}${url.encodedPath.take(30)}")
            }
            cookies.forEach { cookie ->
                if (cookie.name == "semester.id") {
                    map.entries.removeIf {
                        it.value.name == cookie.name && it.value.domain == cookie.domain
                    }
                }
                val key = keyOf(cookie)
                if (cookie.expiresAt <= System.currentTimeMillis()) map.remove(key)
                else map[key] = cookie
            }
            persist()
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            val expired = map.filterValues { it.expiresAt <= now }.keys
            expired.forEach { map.remove(it) }
            if (expired.isNotEmpty()) persist()
            return map.values.filter { it.matches(url) }.also { list ->
                if (debugLogging) android.util.Log.i("CQMU_COOKIE",
                    "LOAD ${url.host}${url.encodedPath.take(34)} -> ${list.joinToString { c -> c.name }}")
            }
        }
    }

    /**
     * 会话自愈：请求遇到登录失效时，若本机记住了密码则静默重登录一次并重试原请求。
     * 没有记住密码或重登录需要验证码时，原样抛出让 UI 提示重新登录。
     */
    private suspend fun <T> withSessionRecovery(block: suspend () -> T): T {
        val observedGeneration = sessionGeneration.get()
        return try {
            block()
        } catch (e: NotLoggedInException) {
            if (silentRelogin(observedGeneration)) block() else throw e
        }
    }

    /** 每个请求只可读写创建它时的会话代次；退出后的迟到响应会被丢弃。 */
    private inner class GenerationCookieJar(
        private val delegate: CookieJar,
        private val expectedGeneration: Long
    ) : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(sessionLock) {
            if (sessionGeneration.get() == expectedGeneration) delegate.loadForRequest(url)
            else emptyList()
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(sessionLock) {
                if (sessionGeneration.get() == expectedGeneration) {
                    delegate.saveFromResponse(url, cookies)
                }
            }
        }
    }

    private fun clientForGeneration(generation: Long): OkHttpClient =
        client.newBuilder()
            .cookieJar(GenerationCookieJar(cookieJar, generation))
            .build()

    private fun clientForCurrentGeneration(): OkHttpClient =
        clientForGeneration(sessionGeneration.get())

    /** 为单次空教室调用覆盖 semester.id，不修改全局 CookieJar。 */
    private class SemesterScopedCookieJar(
        private val delegate: CookieJar,
        private val semesterId: Long
    ) : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val shared = delegate.loadForRequest(url).filterNot { it.name == "semester.id" }
            if (url.host != "jiaowu.cqmu.edu.cn" || !url.encodedPath.startsWith("/eams/")) {
                return shared
            }
            val scoped = Cookie.Builder()
                .name("semester.id")
                .value(semesterId.toString())
                .hostOnlyDomain("jiaowu.cqmu.edu.cn")
                .path("/eams/")
                .secure()
                .build()
            return shared + scoped
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            delegate.saveFromResponse(url, cookies.filterNot { it.name == "semester.id" })
        }
    }

    private fun clientForSemester(
        semesterId: Long,
        generation: Long = sessionGeneration.get()
    ): OkHttpClient =
        client.newBuilder().also { builder ->
            val generationJar = GenerationCookieJar(cookieJar, generation)
            builder.cookieJar(SemesterScopedCookieJar(generationJar, semesterId))
        }
            .build()

    private fun semesterIdForQueryDate(date: LocalDate): Long {
        val knownStarts = knownSemesters.mapNotNull { semester ->
            store.cachedWeek1Monday(semester.id)
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?.let { semester.id to it }
        }.toMap()
        return SemesterContext.idForDate(date, knownStarts)
    }

    /** 同一时刻只允许一次重登录；其他失败请求复用已经恢复的新会话。 */
    private suspend fun silentRelogin(observedGeneration: Long): Boolean = reloginMutex.withLock {
        val currentGeneration = sessionGeneration.get()
        if (currentGeneration != observedGeneration) {
            return@withLock canReuseRecoveredSession(
                observedGeneration,
                currentGeneration,
                lastLogoutGeneration.get(),
                sessionActive.get()
            )
        }
        if (!canStartSilentAttempt(observedGeneration, lastSilentAttemptGeneration.get())) {
            return@withLock false
        }
        lastSilentAttemptGeneration.set(observedGeneration)
        try {
            val step = beginLogin(observedGeneration)
            val restored = when (step) {
                is LoginStep.Success -> true
                is LoginStep.Challenge -> {
                    val credentials = rememberedCredentials()
                    if (step.execution.isBlank() || step.needCaptcha ||
                        credentials == null
                    ) {
                        false
                    } else {
                        val (username, password) = credentials
                        when (submitLogin(
                            step.execution,
                            username,
                            password,
                            null,
                            step.mfaEnabled,
                            observedGeneration
                        )) {
                            is LoginStep.Success -> true
                            is LoginStep.Challenge -> {
                                saveRememberedCredentials("", null, remember = false)
                                false
                            }
                        }
                    }
                }
            }
            restored && sessionActive.get()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        const val EAMS = "https://jiaowu.cqmu.edu.cn:8080/eams"
        const val FOLLOW_AUTH = "X-App-Follow-Auth-Redirect"
        const val CAS = "https://cas.cqmu.edu.cn/cas"
        const val MAX_FREE_ROOM_PAGES = 10
        const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

        /** 空闲教室查询的教室类型选项（取自 free.action 页面下拉） */
        val ROOM_TYPES = listOf(
            RoomTypeOption("", "全部类型"),
            RoomTypeOption("5", "普通教室"),
            RoomTypeOption("3", "多媒体教室"),
            RoomTypeOption("15", "智慧教室"),
            RoomTypeOption("96", "计算机实验室"),
            RoomTypeOption("1", "语言实验室"),
            RoomTypeOption("100", "录播教室"),
            RoomTypeOption("87", "示教室"),
            RoomTypeOption("38", "实验室"),
            RoomTypeOption("121", "临床技能实验室"),
            RoomTypeOption("31", "教学医院教室"),
            RoomTypeOption("39", "电子阅览室"),
            RoomTypeOption("2", "体育训练场")
        )

        val CAMPUSES = listOf(
            CampusOption("", "全部校区"),
            CampusOption("3", "缙云校区"),
            CampusOption("2", "袁家岗校区"),
            CampusOption("4", "校外教学点")
        )

        /** 单日最大小节数（教务限制 1-18） */
        const val MAX_PERIOD = 18

        /** 默认查看的学期数上限（防止假期时扫描过多学期） */
        const val SEMESTER_SCAN_LIMIT = 6
    }

    val roomTypes: List<RoomTypeOption> get() = ROOM_TYPES
    val campuses: List<CampusOption> get() = CAMPUSES

    // ===================== 登录 =====================

    /** 打开登录链：教务 login.action → SSO → CAS 登录页；若教务会话仍有效则直接返回 Success */
    suspend fun beginLogin(): LoginStep = beginLogin(sessionGeneration.get())

    private suspend fun beginLogin(expectedGeneration: Long): LoginStep = withContext(Dispatchers.IO) {
        synchronized(sessionLock) {
            requireGeneration(expectedGeneration)
            cookieJar.clearHost("jiaowu.cqmu.edu.cn")
        }
        val req = Request.Builder().url("$EAMS/login.action").get()
            .header(FOLLOW_AUTH, "1").build()
        clientForGeneration(expectedGeneration).newCall(req).execute().use { res ->
            requireGeneration(expectedGeneration)
            val body = res.body?.string().orEmpty()
            val onJiaowu = res.request.url.host == "jiaowu.cqmu.edu.cn"
            val user = if (onJiaowu) EamsParser.parseUserInfo(body) else null
            if (user != null) return@withContext loginSuccess(user, expectedGeneration)
            val execution = EamsParser.parseExecution(body)
                ?: return@withContext LoginStep.Challenge("", false, "登录页加载失败（HTTP ${res.code}），请稍后重试")
            LoginStep.Challenge(
                execution = execution,
                needCaptcha = EamsParser.parseCaptchaRequired(body),
                message = null,
                mfaEnabled = EamsLoginPolicy.isMfaEnabled(body)
            )
        }
    }

    /** 提交账号密码；失败时返回新的 Challenge（含新 execution，可能开始要求验证码） */
    suspend fun submitLogin(
        execution: String,
        username: String,
        password: String,
        captcha: String?,
        mfaEnabled: Boolean
    ): LoginStep = submitLogin(
        execution,
        username,
        password,
        captcha,
        mfaEnabled,
        sessionGeneration.get()
    )

    private suspend fun submitLogin(
        execution: String,
        username: String,
        password: String,
        captcha: String?,
        initiallyMfaEnabled: Boolean,
        expectedGeneration: Long
    ): LoginStep = withContext(Dispatchers.IO) {
        val loginClient = clientForGeneration(expectedGeneration)
        // CAS 的 execution 是一次性且短时效的：提交前现取新鲜的，避免
        // 「打开登录页后停留较久 → execution 过期 → 点登录无任何反应」
        val freshPage = runCatching {
            val pageReq = Request.Builder().url("$EAMS/login.action").get()
                .header(FOLLOW_AUTH, "1").build()
            val page = loginClient.newCall(pageReq).execute().use { response ->
                if (!response.isSuccessful || response.request.url.host != "cas.cqmu.edu.cn") {
                    throw EamsException("登录页刷新失败（HTTP ${response.code}），请稍后重试")
                }
                response.body?.string().orEmpty()
            }
            requireGeneration(expectedGeneration)
            if (EamsParser.parseExecution(page) == null) {
                throw EamsException("登录页刷新失败，请稍后重试")
            }
            page
        }.getOrNull()
        val freshExec = freshPage?.let(EamsParser::parseExecution)
        val execToUse = freshExec ?: execution
        if (debugLogging) {
            android.util.Log.i("CQMU_LOGIN", "execution lengths: caller=${execution.length}, fresh=${freshExec?.length ?: 0}")
        }

        val pk = cachedPublicKey ?: fetchPublicKey(loginClient, expectedGeneration)
            .also { cachedPublicKey = it }
        val encoded = "__RSA__" + rsaEncrypt(pk, password)
        val mfaEnabled = EamsLoginPolicy.resolveMfaEnabled(freshPage, initiallyMfaEnabled)
        val mfaState = if (mfaEnabled) {
            fetchMfaState(loginClient, username, encoded, expectedGeneration)
        } else {
            ""
        }
        val form = FormBody.Builder()
            .add("username", username)
            .add("password", encoded)
            .add("captcha", captcha.orEmpty())
            .add("currentMenu", "1")
            .add("failN", "-1")
            .add("mfaState", mfaState)
            .add("rememberMe", "false")
            .add("execution", execToUse)
            .add("_eventId", "submit")
            .add("geolocation", "")
            .add("fpVisitorId", "")
            .add("trustAgent", "")
            .build()
        val req = Request.Builder().url("$CAS/login").post(form)
            .header(FOLLOW_AUTH, "1").build()
        loginClient.newCall(req).execute().use { res ->
            requireGeneration(expectedGeneration)
            val body = res.body?.string().orEmpty()
            val onJiaowu = res.request.url.host == "jiaowu.cqmu.edu.cn"
            val user = if (onJiaowu) EamsParser.parseUserInfo(body) else null
            if (user != null) return@withContext loginSuccess(user, expectedGeneration)
            EamsLoginPolicy.accountMaintenanceAction(res.request.url.toString())?.let { action ->
                return@withContext LoginStep.Challenge(
                    execution = execToUse,
                    needCaptcha = false,
                    message = "学校统一身份认证要求先完善安全信息或更新密码，请打开教务在线完成后再用新密码登录",
                    mfaEnabled = mfaEnabled,
                    accountMaintenanceUrl = action
                )
            }
            val newExec = EamsParser.parseExecution(body)
                ?: return@withContext LoginStep.Challenge("", false, "登录失败（HTTP ${res.code}），请稍后重试")
            LoginStep.Challenge(
                newExec,
                EamsParser.parseCaptchaRequired(body),
                extractLoginError(body),
                mfaEnabled = EamsLoginPolicy.isMfaEnabled(body)
            )
        }
    }

    private fun loginSuccess(user: UserInfo, expectedGeneration: Long): LoginStep.Success =
        synchronized(sessionLock) {
            requireGeneration(expectedGeneration)
            val identityChanged = store.activateAuthenticatedUser(user.name, user.studentNo)
            if (identityChanged) clearStandaloneUserCaches()
            // 恢复学期上下文 Cookie（刚登录时缺失，dataQuery 等接口依赖它）
            if (store.selectedSemesterId > 0L) cookieJar.setSemesterCookie(store.selectedSemesterId)
            cookieJar.persist()
            sessionActive.set(true)
            sessionGeneration.incrementAndGet()
            LoginStep.Success(user)
        }

    private fun requireGeneration(expectedGeneration: Long) {
        if (sessionGeneration.get() != expectedGeneration) {
            throw CancellationException("stale eams session")
        }
    }

    private inline fun <T> commitForGeneration(
        expectedGeneration: Long,
        block: () -> T
    ): T = synchronized(sessionLock) {
        requireGeneration(expectedGeneration)
        block()
    }

    private fun extractLoginError(page: String): String? {
        // CAS 的错误文案在 Vue 状态里，尽量从页面常见位置提取；取不到就返回 null 走通用提示
        Regex("""loginError\s*=\s*\{[^}]*?"?message"?\s*:\s*"([^"]{2,80})"""")
            .find(page)?.groupValues?.get(1)?.let { return it }
        listOf("账号或密码错误", "密码错误", "用户不存在", "验证码错误", "账号被锁定", "认证失败")
            .firstOrNull { page.contains(it) }?.let { return it }
        return null
    }

    private fun fetchPublicKey(loginClient: OkHttpClient, expectedGeneration: Long): PublicKey {
        val req = Request.Builder().url("$CAS/jwt/publicKey").get().build()
        loginClient.newCall(req).execute().use { res ->
            requireGeneration(expectedGeneration)
            if (!res.isSuccessful) throw EamsException("获取登录公钥失败（HTTP ${res.code}）")
            val pem = res.body?.string().orEmpty()
            val b64 = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace(Regex("\\s"), "")
            val der = Base64.decode(b64, Base64.DEFAULT)
            return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
        }
    }

    private fun fetchMfaState(
        loginClient: OkHttpClient,
        username: String,
        encodedPassword: String,
        expectedGeneration: Long
    ): String {
        val form = FormBody.Builder()
            .add("username", username)
            .add("password", encodedPassword)
            .add("fpVisitorId", "")
            .build()
        val request = Request.Builder().url("$CAS/mfa/detect").post(form).build()
        loginClient.newCall(request).execute().use { response ->
            requireGeneration(expectedGeneration)
            if (!response.isSuccessful) {
                throw EamsException("统一身份认证预检失败（HTTP ${response.code}），请稍后重试")
            }
            val detection = EamsLoginPolicy.parseMfaDetection(response.body?.string().orEmpty())
            if (detection.required) {
                throw EamsException("该账号需要二次认证，请先在学校统一身份认证网页完成验证后再重试")
            }
            return detection.state
        }
    }

    private fun rsaEncrypt(pk: PublicKey, text: String): String {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, pk)
        return Base64.encodeToString(cipher.doFinal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    /** 登录页的验证码图片（跟随 CAS 会话） */
    suspend fun fetchCaptchaImage(): ByteArray = withContext(Dispatchers.IO) {
        val url = "$CAS/captcha.jpg?r=${System.currentTimeMillis()}"
        val req = Request.Builder().url(url).get().build()
        clientForCurrentGeneration().newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw EamsException("验证码获取失败（HTTP ${res.code}）")
            res.body?.bytes() ?: ByteArray(0)
        }
    }

    /** 校验现有会话是否有效（App 启动时） */
    suspend fun checkSession(): UserInfo? = withContext(Dispatchers.IO) {
        val expectedGeneration = sessionGeneration.get()
        val req = Request.Builder().url("$EAMS/home.action").get().build()
        clientForGeneration(expectedGeneration).newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            requireGeneration(expectedGeneration)
            val onJiaowu = res.request.url.host == "jiaowu.cqmu.edu.cn"
            if (!onJiaowu) return@withContext null
            val user = EamsParser.parseUserInfo(body) ?: return@withContext null
            commitForGeneration(expectedGeneration) {
                val identityChanged = store.activateAuthenticatedUser(user.name, user.studentNo)
                if (identityChanged) clearStandaloneUserCaches()
            }
            user
        }
    }

    /** 显式退出只需立即销毁 App 本地认证材料；不等待远端网络响应。 */
    fun clearLocalSession() {
        synchronized(sessionLock) {
            val logoutGeneration = sessionGeneration.incrementAndGet()
            lastLogoutGeneration.set(logoutGeneration)
            sessionActive.set(false)
            client.dispatcher.cancelAll()
            cookieJar.clearAll()
            store.clearForLogout()
            clearStandaloneUserCaches()
        }
    }

    private fun clearStandaloneUserCaches() {
        runCatching { freeRoomCacheFile.delete() }
        runCatching { File(freeRoomCacheFile.parentFile, "free_room_cache.tmp").delete() }
        runCatching { crashReportFile.delete() }
    }

    suspend fun logout() = withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
        clearLocalSession()
    }

    // ===================== 学期 =====================

    suspend fun semesters(): List<Semester> = withSessionRecovery {
        // 空响应 = 缺少学期上下文（semester.id Cookie 缺失/被置空，常见于刚登录
        // 或访问过空教室页之后）：拉课表页取服务器默认学期号，写 Cookie 后重试
        val first = semestersOnce()
        if (first != null) return@withSessionRecovery first

        // 上下文自愈链包含阻塞网络调用，完整切到 IO；取消必须继续向上传播。
        withContext(Dispatchers.IO) { recoverSemesterContext() }
    }

    private suspend fun recoverSemesterContext(): List<Semester> {
        val expectedGeneration = sessionGeneration.get()
        val html1 = try {
            getEams(
                "$EAMS/courseTableForStd.action",
                "$EAMS/home.action",
                expectedGeneration = expectedGeneration
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ""
        }
        val defId = Regex("value.\"(\\d+)\"").find(html1)?.groupValues?.get(1)?.toLongOrNull()
        if (debugLogging) android.util.Log.d("CQMU_RECOVERY", "semester-default=$defId")
        if (defId != null) commitForGeneration(expectedGeneration) {
            cookieJar.setSemesterCookie(defId)
        }
        semestersOnce(expectedGeneration = expectedGeneration)?.let { return it }

        val projectHtml = try {
            val form = FormBody.Builder()
                .add("tagId", "semesterBarAppSemester")
                .add("dataType", "projectId")
                .build()
            val req = Request.Builder().url("$EAMS/dataQuery.action").post(form)
                .header("Referer", "$EAMS/courseTableForStd.action").build()
            clientForGeneration(expectedGeneration).newCall(req).execute().use { res ->
                if (res.code == 401) throw NotLoggedInException()
                res.body?.string().orEmpty().also { requireGeneration(expectedGeneration) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ""
        }
        val projectId = Regex("value.\"(\\d+)\"").find(projectHtml)?.groupValues?.get(1)
        if (debugLogging) android.util.Log.d("CQMU_RECOVERY", "project-present=${projectId != null}")
        if (projectId != null) {
            commitForGeneration(expectedGeneration) { store.selectedProjectId = projectId }
            semestersOnce(projectId, expectedGeneration)?.let { return it }
        }

        val guess = guessSemesterIdForNow()
        if (debugLogging) android.util.Log.d("CQMU_RECOVERY", "semester-fallback=$guess")
        commitForGeneration(expectedGeneration) { cookieJar.setSemesterCookie(guess) }
        return semestersOnce(expectedGeneration = expectedGeneration)
            ?: throw EamsException("学期列表获取失败，请下拉重试")
    }

    /** 按当前日期推算学期号兜底。 */
    private fun guessSemesterIdForNow(): Long = SemesterContext.idForDate(LocalDate.now())

    private suspend fun semestersOnce(
        projectId: String = "",
        expectedGeneration: Long = sessionGeneration.get()
    ): List<Semester>? =
        withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("tagId", "semesterBarAppSemester")
            .add("dataType", "semesterCalendar")
            .add("value", "")
            .add("empty", "false")
            .add("semester.id", store.selectedSemesterId.takeIf { it > 0L }?.toString() ?: "")
            .add("project.id", projectId.ifBlank { store.selectedProjectId })
            .build()
        val req = Request.Builder().url("$EAMS/dataQuery.action").post(form)
            .header("Referer", "$EAMS/courseTableForStd.action").build()
        val text = clientForGeneration(expectedGeneration).newCall(req).execute().use { res ->
            if (res.code == 401) throw NotLoggedInException()
            if (!res.isSuccessful) throw EamsException("学期列表获取失败（HTTP ${res.code}）")
            res.body?.string().orEmpty().also { requireGeneration(expectedGeneration) }
        }
        val list = EamsParser.parseSemesters(text)
        if (debugLogging) android.util.Log.d("CQMU_RECOVERY", "semester-count=${list.size}")
        if (list.isNotEmpty()) {
            commitForGeneration(expectedGeneration) {
                knownSemesters = list
                // 维护 semester.id 会话 Cookie：教务在响应里回显当前学期
                Regex("semesterId.\"(\\d+)\"").find(text)?.groupValues?.get(1)
                    ?.toLongOrNull()?.let { cookieJar.setSemesterCookie(it) }
            }
        }
        list.ifEmpty { null }
    }

    /** 学期第 1 周周一（yyyy-MM-dd），带缓存 */
    suspend fun week1Monday(semesterId: Long): LocalDate? = withSessionRecovery {
        week1MondayOnce(semesterId)
    }

    private suspend fun week1MondayOnce(semesterId: Long): LocalDate? = withContext(Dispatchers.IO) {
        val expectedGeneration = sessionGeneration.get()
        store.cachedWeek1Monday(semesterId)?.let { return@withContext runCatching { LocalDate.parse(it) }.getOrNull() }
        val form = FormBody.Builder()
            .add("ignoreHead", "1")
            .add("setting.kind", "calendar")
            .add("semester.id", semesterId.toString())
            .build()
        val req = Request.Builder().url("$EAMS/courseTableForStd!calendarCourseTable.action").post(form)
            .header("Referer", "$EAMS/courseTableForStd.action").build()
        val html = clientForGeneration(expectedGeneration).newCall(req).execute().use { res ->
            if (res.code == 401) throw NotLoggedInException()
            if (!res.isSuccessful) throw EamsException("校历获取失败（HTTP ${res.code}）")
            res.body?.string().orEmpty().also { requireGeneration(expectedGeneration) }
        }
        val monday = EamsParser.parseWeek1Monday(html) ?: return@withContext null
        commitForGeneration(expectedGeneration) { store.cacheWeek1Monday(semesterId, monday) }
        runCatching { LocalDate.parse(monday) }.getOrNull()
    }

    /** 推断应展示的学期；用户手动选择过则始终优先用户选择。 */
    suspend fun resolveDefaultSemester(): Semester {
        val list = semesters()
        store.selectedSemesterId.takeIf { it != 0L }
            ?.let { chosen -> list.firstOrNull { it.id == chosen }?.let { return it } }

        val sorted = list.sortedByDescending { it.id }.take(SEMESTER_SCAN_LIMIT)
        val today = LocalDate.now()
        val dated = sorted.mapNotNull { s ->
            val w1 = week1Monday(s.id) ?: return@mapNotNull null
            s to w1
        }
        SemesterSelectionPolicy.choose(dated, today)?.let { return it }
        return sorted.firstOrNull() ?: list.first()
    }

    // ===================== 个人课表 =====================

    private suspend fun ensureStdIds(): String = withSessionRecovery {
        ensureStdIdsOnce()
    }

    private suspend fun ensureStdIdsOnce(): String = withContext(Dispatchers.IO) {
        val expectedGeneration = sessionGeneration.get()
        val req = Request.Builder().url("$EAMS/courseTableForStd.action").get()
            .header("Referer", "$EAMS/home.action").build()
        val html = clientForGeneration(expectedGeneration).newCall(req).execute().use { res ->
            if (res.code == 401) throw NotLoggedInException()
            if (!res.isSuccessful) throw EamsException("课表页获取失败（HTTP ${res.code}）")
            res.body?.string().orEmpty().also { requireGeneration(expectedGeneration) }
        }
        EamsParser.parseStdIds(html)
            ?: throw NotLoggedInException()
    }

    /** 只读本地缓存的课表（不联网），无有效缓存返回 null */
    suspend fun cachedTimetable(semesterId: Long): Timetable? = withContext(Dispatchers.IO) {
        store.cachedTimetableHtml(semesterId)?.let { html ->
            EamsParser.parseTimetable(html, semesterId)
        }
    }

    /** 只读缓存的周锚点（不联网） */
    fun cachedWeek1Monday(semesterId: Long): LocalDate? =
        store.cachedWeek1Monday(semesterId)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** 记录上次查看的课表学期，供下次进入零网络直接展示 */
    fun rememberLastSemester(sem: Semester) {
        runCatching {
            store.lastTimetableSemester = JSONObject()
                .put("id", sem.id)
                .put("schoolYear", sem.schoolYear)
                .put("term", sem.term)
                .toString()
        }
    }

    /** 上次查看的课表（零网络），无缓存返回 null */
    suspend fun cachedLastTimetable(): Pair<Semester, Timetable>? = withContext(Dispatchers.IO) {
        val json = store.lastTimetableSemester ?: return@withContext null
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return@withContext null
        val sem = Semester(o.optLong("id"), o.optString("schoolYear"), o.optString("term"))
        if (sem.id <= 0L) return@withContext null
        val html = store.cachedTimetableHtml(sem.id) ?: return@withContext null
        val tt = EamsParser.parseTimetable(html, sem.id)
        sem to tt
    }

    /**
     * 是否需要按周对齐：锚点是「最近一个周日（含今天）」。
     * 上次同步早于该锚点 = 跨过了一个周日 → 需要重新拉一次课表，防止调课；
     * 没跨过周日就复用缓存，不产生任何请求。策略见 [SyncPolicy]。
     */
    fun needsWeeklySync(semesterId: Long): Boolean =
        SyncPolicy.needsWeeklySync(store.lastSyncEpochDay(semesterId))

    /** 联网拉取课表并更新缓存与同步日期（成功才记同步日）；登录失效自动自愈一次 */
    suspend fun fetchTimetable(semesterId: Long): Timetable = withSessionRecovery {
        fetchTimetableOnce(semesterId)
    }

    private suspend fun fetchTimetableOnce(semesterId: Long): Timetable = withContext(Dispatchers.IO) {
        val ids = ensureStdIds()
        val expectedGeneration = sessionGeneration.get()
        val url = "$EAMS/courseTableForStd!courseTable.action".toHttpUrl().newBuilder()
            .addQueryParameter("ignoreHead", "1")
            .addQueryParameter("setting.kind", "std")
            .addQueryParameter("startWeek", "")
            .addQueryParameter("endWeek", "")
            .addQueryParameter("semester.id", semesterId.toString())
            .addQueryParameter("ids", ids)
            .build()
        val req = Request.Builder().url(url).get()
            .header("Referer", "$EAMS/courseTableForStd.action").build()
        val html = clientForGeneration(expectedGeneration).newCall(req).execute().use { res ->
            if (res.code == 401) throw NotLoggedInException()
            if (!res.isSuccessful) throw EamsException("课表获取失败（HTTP ${res.code}）")
            res.body?.string().orEmpty().also { requireGeneration(expectedGeneration) }
        }
        if (html.contains("name=\"execution\"") && !html.contains("unitCount")) {
            throw NotLoggedInException()
        }
        val timetable = EamsParser.parseTimetable(html, semesterId)
        commitForGeneration(expectedGeneration) {
            store.cacheTimetableHtml(semesterId, html)
            store.setLastSync(semesterId, LocalDate.now().toEpochDay())
        }
        timetable
    }

    // ===================== 成绩 / 绩点 =====================

    private fun getEams(
        url: String,
        referer: String,
        followAuth: Boolean = false,
        expectedGeneration: Long = sessionGeneration.get()
    ): String {
        val b = Request.Builder().url(url).get().header("Referer", referer)
        if (followAuth) b.header(FOLLOW_AUTH, "1")
        val req = b.build()
        return clientForGeneration(expectedGeneration).newCall(req).execute().use { res ->
            if (res.code == 401) throw NotLoggedInException()
            if (!res.isSuccessful) throw EamsException("请求失败（HTTP ${res.code}）")
            res.body?.string().orEmpty().also { requireGeneration(expectedGeneration) }
        }
    }

    private fun checkLoginWall(html: String, contentMarker: String) {
        if (html.contains("name=\"execution\"") && !html.contains(contentMarker)) {
            throw NotLoggedInException()
        }
    }

    /**
     * 联网拉取「所有学期成绩 + GPA 汇总」（学校口径：必修+限选+公选），
     * 成功后缓存原始页并记录同步日（周锚点）。
     */
    suspend fun fetchGradeBook(): GradeBook = withSessionRecovery {
        fetchGradeBookOnce()
    }

    private suspend fun fetchGradeBookOnce(): GradeBook = withContext(Dispatchers.IO) {
        val expectedGeneration = sessionGeneration.get()
        val html = getEams(
            "$EAMS/teach/grade/course/person!historyCourseGrade.action?projectType=MAJOR",
            "$EAMS/teach/grade/course/person.action",
            expectedGeneration = expectedGeneration
        )
        checkLoginWall(html, "gridtable")
        val parsed = EamsParser.parseGradeHistory(html)
        commitForGeneration(expectedGeneration) {
            store.cacheGradeHtml(html)
            store.setLastSync("grade", LocalDate.now().toEpochDay())
        }
        parsed
    }

    /** 只读本地缓存的成绩册 */
    suspend fun cachedGradeBook(): GradeBook? = withContext(Dispatchers.IO) {
        store.cachedGradeHtml()?.let { html ->
            val book = EamsParser.parseGradeHistory(html)
            if (book.courses.isNotEmpty()) book else null
        }
    }

    /** 成绩按周对齐（调档/补录成绩变化低频，跨周日刷新一次即可） */
    fun needsGradeSync(): Boolean =
        SyncPolicy.needsWeeklySync(store.lastSyncEpochDay("grade"))

    // ===================== 考试 =====================

    /** 考试批次列表：优先本地缓存，否则拉考试页并缓存 */
    suspend fun examBatches(): List<ExamBatch> = withSessionRecovery {
        examBatchesOnce()
    }

    private suspend fun examBatchesOnce(): List<ExamBatch> = withContext(Dispatchers.IO) {
        store.cachedExamBatches?.let { json ->
            runCatching {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    ExamBatch(o.getLong("id"), o.optString("name"), o.optBoolean("selected"))
                }
            }.getOrNull()
        }?.takeIf { it.isNotEmpty() } ?: run {
            val expectedGeneration = sessionGeneration.get()
            val html = getEams(
                "$EAMS/stdExamTable.action",
                "$EAMS/home.action",
                expectedGeneration = expectedGeneration
            )
            checkLoginWall(html, "stdExamTable")
            val batches = EamsParser.parseExamBatches(html)
            if (batches.isNotEmpty()) {
                val arr = JSONArray()
                batches.forEach { b ->
                    arr.put(JSONObject().put("id", b.id).put("name", b.name).put("selected", b.selected))
                }
                commitForGeneration(expectedGeneration) {
                    store.cachedExamBatches = arr.toString()
                }
            }
            batches
        }
    }

    /** 联网拉取某批次考试安排，缓存原始页并记录同步日（每日 5 点锚点） */
    suspend fun fetchExamTable(batchId: Long): List<ExamItem> = withSessionRecovery {
        fetchExamTableOnce(batchId)
    }

    private suspend fun fetchExamTableOnce(batchId: Long): List<ExamItem> = withContext(Dispatchers.IO) {
        val expectedGeneration = sessionGeneration.get()
        val html = getEams(
            "$EAMS/stdExamTable!examTable.action?examBatch.id=$batchId",
            "$EAMS/stdExamTable.action",
            expectedGeneration = expectedGeneration
        )
        checkLoginWall(html, "gridtable")
        val parsed = EamsParser.parseExamTable(html)
        commitForGeneration(expectedGeneration) {
            store.cacheExamHtml(batchId, html)
            store.setLastSync("exam_$batchId", LocalDate.now().toEpochDay())
        }
        parsed
    }

    /** 只读本地缓存的考试安排 */
    suspend fun cachedExamTable(batchId: Long): List<ExamItem>? = withContext(Dispatchers.IO) {
        store.cachedExamHtml(batchId)?.let { html ->
            val items = EamsParser.parseExamTable(html)
            // 空列表也是合法结果（该批次没有安排），但空页可能是登录墙，无法区分时按空处理
            items
        }
    }

    /** 考试按每日 5 点对齐（临考安排可能调整） */
    fun needsExamSync(batchId: Long): Boolean =
        SyncPolicy.needsDailySync(store.lastSyncEpochDay("exam_$batchId"))

    // ===================== 空闲教室 =====================

    /**
     * 教室借用 → 查看空闲教室。返回该「日期 + 小节区间」内完全空闲的教室，自动翻页取全。
     */
    suspend fun fetchFreeRooms(query: FreeRoomQuery): List<FreeRoom> = withSessionRecovery {
        // 必须切 IO 线程：fetchFreeRoomsRaw 是阻塞网络调用（主线程会抛
        // NetworkOnMainThreadException，真机已实锤）
        withContext(Dispatchers.IO) {
            val expectedGeneration = sessionGeneration.get()
            val requestClient = clientForSemester(
                semesterIdForQueryDate(query.date),
                expectedGeneration
            )
            val rooms = fetchFreeRoomsRaw(query, requestClient)
            commitForGeneration(expectedGeneration) { writeFreeRoomCache(query, rooms) }
            rooms
        }
    }

    /** 单请求失败（网络抖动/超时）自动重试一次 */
    private fun fetchWithRetry(req: Request, what: String, requestClient: OkHttpClient = client): String {
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                return requestClient.newCall(req).execute().use { res ->
                    if (res.code == 401) throw NotLoggedInException()
                    if (!res.isSuccessful) throw EamsException("$what 失败（HTTP ${res.code}）")
                    res.body?.string().orEmpty()
                }
            } catch (e: NotLoggedInException) {
                throw e
            } catch (e: EamsException) {
                throw e
            } catch (e: java.io.IOException) {
                lastError = e
                if (attempt == 0) {
                    try { Thread.sleep(800) } catch (_: InterruptedException) {}
                }
            }
        }
        throw EamsException("$what 失败：${lastError?.message ?: "网络异常"}（请检查校园网/VPN）")
    }

    private fun fetchFreeRoomsRaw(query: FreeRoomQuery, requestClient: OkHttpClient): List<FreeRoom> {
        var pagination = FreeRoomPaginationState()
        var page = 1
        while (page <= MAX_FREE_ROOM_PAGES && !pagination.isComplete) {
            val formBuilder = FormBody.Builder()
            FreeRoomSearchParams.forPage(query, page).forEach { (name, value) ->
                formBuilder.add(name, value)
            }
            val form = formBuilder.build()
            val req = Request.Builder().url("$EAMS/classroom/apply/free!search.action").post(form)
                .header("Referer", "$EAMS/classroom/apply/free.action").build()
            val html = fetchWithRetry(req, "空闲教室查询", requestClient)
            if (html.contains("name=\"execution\"") && !html.contains("gridtable")) {
                throw NotLoggedInException()
            }
            val result = EamsParser.parseFreeRooms(html)
            pagination = pagination.accept(result)
            page++
        }
        return pagination.resultOrThrow(MAX_FREE_ROOM_PAGES)
    }

    // ---- 每日凌晨 5 点对齐（与课表同款策略）----

    /**
     * 只读查询条件完全一致的空教室缓存。默认仅返回最近一次凌晨 5:00
     * 锚点后的新鲜数据；[allowStale] 仅用于联网失败时的明确标注降级。
     */
    suspend fun cachedFreeRooms(
        query: FreeRoomQuery,
        allowStale: Boolean = false
    ): List<FreeRoom>? = withContext(Dispatchers.IO) {
        val entry = readFreeRoomCache() ?: return@withContext null
        if (entry.first != query) return@withContext null
        if (!allowStale && !isFreshSince5AM(entry.second)) return@withContext null
        entry.third
    }

    fun isFreshSince5AM(fetchedEpochMillis: Long): Boolean =
        SyncPolicy.freshSince5AM(fetchedEpochMillis)

    /** 缓存条目：(查询条件, 取回时间毫秒, 教室列表) */
    private fun readFreeRoomCache(): Triple<FreeRoomQuery, Long, List<FreeRoom>>? = runCatching {
        if (!freeRoomCacheFile.exists()) return null
        val obj = JSONObject(freeRoomCacheFile.readText())
        val owner = obj.optString("owner")
        if (!CacheOwnershipPolicy.canRead(owner, store.studentNo)) return null
        val q = obj.getJSONObject("query")
        val query = FreeRoomQuery(
            date = LocalDate.parse(q.getString("date")),
            beginPeriod = q.getInt("begin"),
            endPeriod = q.getInt("end"),
            campusId = q.optString("campus"),
            typeId = q.optString("type"),
            minSeats = q.optString("seats"),
            roomName = q.optString("name")
        )
        val fetched = obj.getLong("fetchedAt")
        val rooms = obj.getJSONArray("rooms").let { arr ->
            (0 until arr.length()).map { i ->
                val r = arr.getJSONObject(i)
                FreeRoom(
                    name = r.optString("name"),
                    building = r.optString("building"),
                    campus = r.optString("campus"),
                    facility = r.optString("facility"),
                    capacity = r.optInt("capacity"),
                    notes = r.optString("notes")
                )
            }
        }
        Triple(query, fetched, rooms)
    }.getOrNull()

    private fun writeFreeRoomCache(query: FreeRoomQuery, rooms: List<FreeRoom>) {
        runCatching {
            val q = JSONObject()
                .put("date", query.date.toString())
                .put("begin", query.beginPeriod)
                .put("end", query.endPeriod)
                .put("campus", query.campusId)
                .put("type", query.typeId)
                .put("seats", query.minSeats)
                .put("name", query.roomName)
            val arr = JSONArray()
            rooms.forEach { r ->
                arr.put(JSONObject()
                    .put("name", r.name)
                    .put("building", r.building)
                    .put("campus", r.campus)
                    .put("facility", r.facility)
                    .put("capacity", r.capacity)
                    .put("notes", r.notes))
            }
            val obj = JSONObject()
                .put("owner", store.studentNo)
                .put("query", q)
                .put("fetchedAt", System.currentTimeMillis())
                .put("rooms", arr)
            val tmp = File(freeRoomCacheFile.parentFile, "free_room_cache.tmp")
            tmp.writeText(obj.toString())
            if (!tmp.renameTo(freeRoomCacheFile)) {
                freeRoomCacheFile.writeText(obj.toString())
                tmp.delete()
            }
        }
    }

    // ---- 单间教室当天占用（供展开查看）----

    data class BlockRange(val begin: Int, val end: Int)

    /** 把 1-18 小节按大节切成 9 块 */
    val freeDayBlocks: List<BlockRange> =
        listOf(1, 3, 5, 7, 9, 11, 13, 15, 17).map { BlockRange(it, it + 1) }

    /**
     * 反推某间教室当天的占用：对每个大节块，用「教室名过滤 + 该块小节区间」查一次空闲列表，
     * 出现在结果里 = 该块空闲，不在 = 被排课/排考/借用占用。
     * 3 并发小请求（结果只有一页），结果按块返回。
     */
    suspend fun roomDayOccupancy(baseQuery: FreeRoomQuery, roomName: String): List<Pair<BlockRange, Boolean>> =
        withContext(Dispatchers.IO) {
            val requestClient = clientForSemester(semesterIdForQueryDate(baseQuery.date))
            coroutineScope {
                val gate = Semaphore(3)
                freeDayBlocks.map { block ->
                    async {
                        gate.withPermit {
                            val free = runCatching {
                                isRoomFreeInBlock(baseQuery, roomName, block, requestClient)
                            }
                                .getOrNull()
                            block to (free == false) // null（查询失败）按占用未知处理，UI 显示为失败态
                        }
                    }
                }.awaitAll()
            }
        }

    /** true = 该块空闲；false = 有占用；仅在接口成功时返回 */
    private fun isRoomFreeInBlock(
        query: FreeRoomQuery,
        roomName: String,
        block: BlockRange,
        requestClient: OkHttpClient
    ): Boolean {
        val roomQuery = query.copy(
            beginPeriod = block.begin,
            endPeriod = block.end,
            minSeats = "",
            roomName = roomName
        )
        val formBuilder = FormBody.Builder()
        FreeRoomSearchParams.forPage(roomQuery, pageNo = 1, pageSize = 50)
            .forEach { (name, value) -> formBuilder.add(name, value) }
        val form = formBuilder.build()
        val req = Request.Builder().url("$EAMS/classroom/apply/free!search.action").post(form)
            .header("Referer", "$EAMS/classroom/apply/free.action").build()
        val html = fetchWithRetry(req, "教室占用查询", requestClient)
        if (html.contains("name=\"execution\"") && !html.contains("gridtable")) {
            throw NotLoggedInException()
        }
        return EamsParser.parseFreeRooms(html).rooms.any { it.name == roomName }
    }

    fun savedUser(): UserInfo? {
        val name = store.userName
        val no = store.studentNo
        return if (name.isNotBlank() && no.isNotBlank()) UserInfo(name, no) else null
    }

    // ---------- 首页板块顺序 ----------

    fun homeTabOrder(): List<Int> = HomeTabOrderPolicy.decode(store.homeTabOrder)

    fun saveHomeTabOrder(order: List<Int>) {
        store.homeTabOrder = HomeTabOrderPolicy.encode(order)
    }

    override fun currentSession(): LaundryLocationSession? =
        store.currentLaundryLocationSession()

    override fun lastLocation(session: LaundryLocationSession): LaundryLastLocation? {
        val raw = store.readLastLaundryLocation(session) ?: return null
        val decoded = LaundryLastLocationCodec.decode(raw, session.owner)
        if (decoded == null) store.writeLastLaundryLocation(session, null)
        return decoded
    }

    override fun saveLastLocation(
        session: LaundryLocationSession,
        location: LaundryLastLocation?
    ) {
        val encoded = location?.let { LaundryLastLocationCodec.encode(session.owner, it) }
        store.writeLastLaundryLocation(session, encoded)
    }

    fun rememberedCredentials(): Pair<String, String>? {
        if (!store.rememberPassword) return null
        val u = store.savedUsername
        val p = store.savedPassword
        return if (u.isNotBlank() && p.isNotBlank()) u to p else null
    }

    fun saveRememberedCredentials(
        username: String,
        password: String?,
        remember: Boolean
    ): Boolean {
        val canRemember = remember && store.canStoreCredentials
        if (canRemember && password != null) {
            if (store.saveCredentials(username, password)) return true
            store.clearCredentials()
        } else {
            store.clearCredentials()
        }
        return !remember
    }

    fun setSemesterCookie(semesterId: Long) {
        cookieJar.setSemesterCookie(semesterId)
        store.selectedSemesterId = semesterId
    }
}
