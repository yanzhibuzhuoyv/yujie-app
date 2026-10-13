package com.cqmu.assistant.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.concurrent.atomic.AtomicBoolean
import com.cqmu.assistant.data.laundry.LaundryLocationSession
import org.json.JSONObject

/**
 * 本地会话与缓存存储。
 * - Cookie（含 JSESSIONID）持久化，重启后可复用会话
 * - 「记住密码」时账号密码存入 EncryptedSharedPreferences
 * - 课表原始 HTML / 学期首周一日期按学期缓存，减少对教务系统的请求
 */
class SessionStore(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("cqmu_session", Context.MODE_PRIVATE)
    private val logoutBlocked = AtomicBoolean(prefs.getBoolean(KEY_LOGGED_OUT, false))
    private var authenticatedSessionRevision = 0L

    init {
        // 旧的自动喜好评分不再使用；升级时仅删除统计，不推断用户的手动顺序。
        prefs.edit()
            .remove(KEY_USAGE)
            .remove(KEY_LAUNDRY_AREA_USAGE)
            .apply()
    }

    private val secure: SharedPreferences? by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val encrypted = EncryptedSharedPreferences.create(
                context,
                "cqmu_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            // 一次性迁移旧版普通 SharedPreferences 里的 Cookie，随后删除明文副本。
            prefs.getString(KEY_COOKIES, null)?.let { legacy ->
                if (!encrypted.contains(KEY_COOKIES)) {
                    encrypted.edit().putString(KEY_COOKIES, legacy).apply()
                }
                prefs.edit().remove(KEY_COOKIES).apply()
            }
            context.getSharedPreferences(KEY_INSECURE_FALLBACK, Context.MODE_PRIVATE)
                .edit().clear().apply()
            encrypted
        } catch (_: Exception) {
            // Fail closed：加密不可用时不保存密码或会话 Cookie，绝不降级为明文。
            context.getSharedPreferences(KEY_INSECURE_FALLBACK, Context.MODE_PRIVATE)
                .edit().clear().apply()
            prefs.edit().remove(KEY_COOKIES).apply()
            null
        }
    }

    val canStoreCredentials: Boolean get() = secure != null

    private fun secureString(key: String): String? {
        if (logoutBlocked.get() || prefs.getBoolean(KEY_LOGGED_OUT, false)) return null
        return runCatching { secure?.getString(key, null) }.getOrNull()
    }

    private fun putSecureString(key: String, value: String) {
        runCatching { secure?.edit()?.putString(key, value)?.apply() }
    }

    // ---------- 登录凭据 ----------

    var savedUsername: String
        get() = secureString(KEY_USERNAME) ?: ""
        set(v) = putSecureString(KEY_USERNAME, v)

    var savedPassword: String
        get() = secureString(KEY_PASSWORD) ?: ""
        set(v) = putSecureString(KEY_PASSWORD, v)

    var rememberPassword: Boolean
        get() = prefs.getBoolean(KEY_REMEMBER, false)
        set(v) = prefs.edit().putBoolean(KEY_REMEMBER, v).apply()

    fun clearCredentials() {
        runCatching {
            secure?.edit()?.remove(KEY_USERNAME)?.remove(KEY_PASSWORD)?.commit()
        }
        prefs.edit().putBoolean(KEY_REMEMBER, false).commit()
    }

    @Synchronized
    fun saveCredentials(username: String, password: String): Boolean {
        val encrypted = secure ?: return false
        val saved = runCatching {
            encrypted.edit()
                .putString(KEY_USERNAME, username)
                .putString(KEY_PASSWORD, password)
                .commit()
        }.getOrDefault(false)
        if (!saved) return false
        val activated = prefs.edit()
            .putBoolean(KEY_REMEMBER, true)
            .putBoolean(KEY_LOGGED_OUT, false)
            .commit()
        if (activated) logoutBlocked.set(false)
        return activated
    }

    /** 显式退出的持久化墓碑先落盘；即使加密区清除失败，重启也不会恢复旧凭据。 */
    @Synchronized
    fun clearForLogout() {
        authenticatedSessionRevision += 1L
        logoutBlocked.set(true)
        prefs.edit().clear().putBoolean(KEY_LOGGED_OUT, true).commit()
        runCatching { secure?.edit()?.clear()?.commit() }
        context.getSharedPreferences(KEY_INSECURE_FALLBACK, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    // ---------- Cookie ----------

    /** key = "domain|path|name" */
    @Synchronized
    fun loadCookies(): MutableMap<String, String> {
        val map = mutableMapOf<String, String>()
        val obj = JSONObject(secureString(KEY_COOKIES) ?: "{}")
        for (key in obj.keys()) map[key] = obj.optString(key)
        return map
    }

    @Synchronized
    fun saveCookies(map: Map<String, String>) {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        putSecureString(KEY_COOKIES, obj.toString())
        prefs.edit().remove(KEY_COOKIES).apply()
    }

    // ---------- 用户与选择状态 ----------

    var userName: String
        get() = prefs.getString(KEY_USER_NAME, "") ?: ""
        set(v) = prefs.edit().putString(KEY_USER_NAME, v).apply()

    var studentNo: String
        get() = prefs.getString(KEY_STU_NO, "") ?: ""
        set(v) = prefs.edit().putString(KEY_STU_NO, v).apply()

    @Synchronized
    fun authenticatedStudentNo(): String? {
        if (logoutBlocked.get() || prefs.getBoolean(KEY_LOGGED_OUT, false)) return null
        return studentNo.takeIf { it.isNotBlank() }
    }

    /**
     * 激活已认证身份。账号发生变化时先原子清空所有普通区用户数据，避免上一账号的
     * 课表、成绩、考试、筛选或喜好缓存被新账号读取；加密区的新 Cookie 随后另行写入。
     */
    @Synchronized
    fun activateAuthenticatedUser(name: String, studentNo: String): Boolean {
        require(studentNo.isNotBlank()) { "学号不能为空" }
        val wasLoggedOut = logoutBlocked.get() || prefs.getBoolean(KEY_LOGGED_OUT, false)
        val previousStudentNo = this.studentNo
        val changed = CacheOwnershipPolicy.identityChanged(previousStudentNo, studentNo)
        if (changed) {
            val credentialsCleared = secure?.let { encrypted ->
                runCatching {
                    encrypted.edit()
                        .remove(KEY_USERNAME)
                        .remove(KEY_PASSWORD)
                        .commit()
                }.getOrDefault(false)
            } ?: true
            require(credentialsCleared) { "无法清除上一账号凭据" }
        }
        val editor = if (changed) prefs.edit().clear() else prefs.edit()
        val committed = editor
            .putString(KEY_USER_NAME, name)
            .putString(KEY_STU_NO, studentNo)
            .putBoolean(KEY_LOGGED_OUT, false)
            .commit()
        require(committed) { "无法保存登录身份" }
        if (CacheOwnershipPolicy.requiresNewSession(previousStudentNo, studentNo, wasLoggedOut)) {
            authenticatedSessionRevision += 1L
        }
        logoutBlocked.set(false)
        return changed
    }

    var selectedSemesterId: Long
        get() = prefs.getLong(KEY_SEMESTER, 0L)
        set(v) = prefs.edit().putLong(KEY_SEMESTER, v).apply()

    // ---------- 课表缓存 ----------

    fun cachedTimetableHtml(semesterId: Long): String? =
        prefs.getString(timetableKey(semesterId), null)

    fun cacheTimetableHtml(semesterId: Long, html: String) {
        prefs.edit().putString(timetableKey(semesterId), html).apply()
    }

    /** EAMS 项目上下文（多项目系统，学期列表依赖它） */
    var selectedProjectId: String
        get() = prefs.getString(KEY_PROJECT, "") ?: ""
        set(v) = prefs.edit().putString(KEY_PROJECT, v).apply()

    /** 上次查看的课表学期（JSON：{"id":282,"schoolYear":"2026-2027","term":"1"}） */
    var lastTimetableSemester: String?
        get() = prefs.getString(KEY_LAST_SEMESTER, null)
        set(v) = prefs.edit().putString(KEY_LAST_SEMESTER, v).apply()

    // ---------- 每周对齐（调课同步）----------

    /** 该学期最后一次从教务同步课表的日期（epoch day），0 = 从未同步 */
    fun lastSyncEpochDay(semesterId: Long): Long = lastSyncEpochDay("tt_$semesterId")

    fun setLastSync(semesterId: Long, epochDay: Long) = setLastSync("tt_$semesterId", epochDay)

    /** 任意数据的同步日期（键如 "grade"、"exam_1181"） */
    fun lastSyncEpochDay(key: String): Long =
        prefs.getLong("sync_$key", 0L)

    fun setLastSync(key: String, epochDay: Long) {
        prefs.edit().putLong("sync_$key", epochDay).apply()
    }

    // ---------- 成绩 / 考试原始页缓存 ----------

    fun cachedGradeHtml(): String? = prefs.getString("html_grade", null)

    fun cacheGradeHtml(html: String) {
        prefs.edit().putString("html_grade", html).apply()
    }

    fun cachedExamHtml(batchId: Long): String? =
        prefs.getString("html_exam_$batchId", null)

    fun cacheExamHtml(batchId: Long, html: String) {
        prefs.edit().putString("html_exam_$batchId", html).apply()
    }

    /** 考试批次列表（JSON 字符串），避免每次打开都拉一遍考试页 */
    var cachedExamBatches: String?
        get() = prefs.getString("exam_batches", null)
        set(v) = prefs.edit().putString("exam_batches", v).apply()

    // ---------- 学期第 1 周周一日期缓存 ----------

    fun cachedWeek1Monday(semesterId: Long): String? =
        prefs.getString(week1Key(semesterId), null)

    fun cacheWeek1Monday(semesterId: Long, monday: String) {
        prefs.edit().putString(week1Key(semesterId), monday).apply()
    }

    fun clearAll() {
        prefs.edit().clear().commit()
    }

    // ---------- 首页板块顺序 ----------

    var homeTabOrder: String?
        get() = prefs.getString(KEY_HOME_TAB_ORDER, null)
        set(v) = prefs.edit().putString(KEY_HOME_TAB_ORDER, v).apply()

    @Synchronized
    fun currentLaundryLocationSession(): LaundryLocationSession? {
        val owner = authenticatedStudentNo() ?: return null
        return LaundryLocationSession(owner, authenticatedSessionRevision)
    }

    @Synchronized
    fun readLastLaundryLocation(session: LaundryLocationSession): String? {
        if (session != currentLaundryLocationSession()) return null
        return prefs.getString(KEY_LAST_LAUNDRY_LOCATION, null)
    }

    @Synchronized
    fun writeLastLaundryLocation(session: LaundryLocationSession, value: String?): Boolean {
        if (session != currentLaundryLocationSession()) return false
        val editor = prefs.edit()
        if (value == null) editor.remove(KEY_LAST_LAUNDRY_LOCATION)
        else editor.putString(KEY_LAST_LAUNDRY_LOCATION, value)
        return editor.commit()
    }

    private fun timetableKey(id: Long) = "tt_html_$id"
    private fun week1Key(id: Long) = "week1_$id"

    private companion object {
        const val KEY_INSECURE_FALLBACK = "cqmu_secure_fallback"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_REMEMBER = "remember_password"
        const val KEY_LOGGED_OUT = "explicitly_logged_out"
        const val KEY_COOKIES = "cookies"
        const val KEY_USER_NAME = "user_name"
        const val KEY_STU_NO = "student_no"
        const val KEY_SEMESTER = "selected_semester_id"
        const val KEY_USAGE = "usage_stats"
        const val KEY_LAUNDRY_AREA_USAGE = "laundry_area_usage_stats"
        const val KEY_HOME_TAB_ORDER = "home_tab_order_v1"
        const val KEY_LAST_LAUNDRY_LOCATION = "last_laundry_location_v1"
        const val KEY_LAST_SEMESTER = "last_timetable_semester"
        const val KEY_PROJECT = "selected_project_id"
    }
}
