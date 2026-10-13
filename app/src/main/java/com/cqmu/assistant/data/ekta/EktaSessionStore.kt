package com.cqmu.assistant.data.ekta

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

class EktaSessionStore(context: Context) : EktaSessionStorage {
    private val sessionRevision = AtomicLong(0)
    private val writeLock = Any()
    private val fallback = context.getSharedPreferences(FALLBACK, Context.MODE_PRIVATE)
    private val guard = context.getSharedPreferences(GUARD, Context.MODE_PRIVATE)
    private val blocked = AtomicBoolean(guard.getBoolean(LOGGED_OUT, false))
    private val secure: SharedPreferences? = runCatching {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrNull().also {
        // 加密不可用时 fail closed，确保从未降级保存过明文凭据。
        fallback.edit().clear().apply()
    }

    override fun revision(): Long = sessionRevision.get()

    override fun loadSession(): EktaSession? = runCatching {
        if (blocked.get() || guard.getBoolean(LOGGED_OUT, false)) return null
        val prefs = secure ?: return null
        val owner = prefs.getString(OWNER, null).orEmpty()
        val account = prefs.getString(ACCOUNT, null).orEmpty()
        val token = prefs.getString(TOKEN, null).orEmpty()
        if (owner.isBlank() || account.isBlank()) null else EktaSession(owner, account, token)
    }.getOrNull()

    override fun loadCredentials(): EktaCredentials? = runCatching {
        if (blocked.get() || guard.getBoolean(LOGGED_OUT, false)) return null
        val prefs = secure ?: return null
        val account = prefs.getString(ACCOUNT, null).orEmpty()
        val password = prefs.getString(PASSWORD, null).orEmpty()
        if (account.isBlank() || password.isEmpty()) null else EktaCredentials(account, password)
    }.getOrNull()

    override fun loadAchievement(
        ownerStudentNo: String,
        account: String
    ): EktaAchievement? = runCatching {
        if (blocked.get() || guard.getBoolean(LOGGED_OUT, false)) return null
        val prefs = secure ?: return null
        if (prefs.getString(OWNER, null) != ownerStudentNo ||
            prefs.getString(ACCOUNT, null) != account
        ) return null
        val raw = prefs.getString(ACHIEVEMENT, null) ?: return null
        EktaAchievementSnapshotCodec.decode(raw)
    }.getOrNull()

    override fun loadAchievementDetails(
        ownerStudentNo: String,
        account: String
    ): EktaCachedAchievementDetails? = runCatching {
        if (blocked.get() || guard.getBoolean(LOGGED_OUT, false)) return null
        val prefs = secure ?: return null
        if (prefs.getString(OWNER, null) != ownerStudentNo ||
            prefs.getString(ACCOUNT, null) != account
        ) return null
        val raw = prefs.getString(ACHIEVEMENT_DETAILS, null) ?: return null
        EktaAchievementDetailsSnapshotCodec.decode(raw)
    }.getOrNull()

    override fun save(
        ownerStudentNo: String,
        account: String,
        password: String,
        token: String,
        expectedRevision: Long
    ): Boolean = synchronized(writeLock) {
        if (sessionRevision.get() != expectedRevision) return@synchronized false
        val prefs = secure ?: return@synchronized false
        val identityChanged = EktaCacheOwnershipPolicy.identityChanged(
            prefs.getString(OWNER, null),
            prefs.getString(ACCOUNT, null),
            ownerStudentNo,
            account
        )
        val stored = runCatching {
            val editor = prefs.edit()
                .putString(OWNER, ownerStudentNo)
                .putString(ACCOUNT, account)
                .putString(PASSWORD, password)
                .putString(TOKEN, token)
            if (identityChanged) {
                editor.remove(ACHIEVEMENT)
                editor.remove(ACHIEVEMENT_DETAILS)
            }
            editor.commit()
        }.getOrDefault(false)
        if (!stored) return@synchronized false
        val unblocked = guard.edit().putBoolean(LOGGED_OUT, false).commit()
        if (unblocked) blocked.set(false)
        unblocked
    }

    override fun updateToken(
        ownerStudentNo: String,
        account: String,
        token: String,
        expectedRevision: Long
    ): Boolean = synchronized(writeLock) {
        if (sessionRevision.get() != expectedRevision) return@synchronized false
        if (blocked.get() || guard.getBoolean(LOGGED_OUT, false)) return@synchronized false
        val prefs = secure ?: return@synchronized false
        if (prefs.getString(OWNER, null) != ownerStudentNo ||
            prefs.getString(ACCOUNT, null) != account
        ) return@synchronized false
        runCatching {
            prefs.edit()
                .putString(OWNER, ownerStudentNo)
                .putString(ACCOUNT, account)
                .putString(TOKEN, token)
                .commit()
        }.getOrDefault(false)
    }

    override fun saveAchievement(
        ownerStudentNo: String,
        account: String,
        achievement: EktaAchievement,
        expectedRevision: Long
    ): Boolean = synchronized(writeLock) {
        if (sessionRevision.get() != expectedRevision) return@synchronized false
        if (blocked.get() || guard.getBoolean(LOGGED_OUT, false)) return@synchronized false
        val prefs = secure ?: return@synchronized false
        if (prefs.getString(OWNER, null) != ownerStudentNo ||
            prefs.getString(ACCOUNT, null) != account
        ) return@synchronized false
        val raw = EktaAchievementSnapshotCodec.encode(achievement)
        runCatching {
            prefs.edit().putString(ACHIEVEMENT, raw).commit()
        }.getOrDefault(false)
    }

    override fun saveAchievementDetails(
        ownerStudentNo: String,
        account: String,
        details: EktaCachedAchievementDetails,
        expectedRevision: Long
    ): Boolean = synchronized(writeLock) {
        if (sessionRevision.get() != expectedRevision) return@synchronized false
        if (blocked.get() || guard.getBoolean(LOGGED_OUT, false)) return@synchronized false
        val prefs = secure ?: return@synchronized false
        if (prefs.getString(OWNER, null) != ownerStudentNo ||
            prefs.getString(ACCOUNT, null) != account
        ) return@synchronized false
        val raw = EktaAchievementDetailsSnapshotCodec.encode(details)
        runCatching {
            prefs.edit().putString(ACHIEVEMENT_DETAILS, raw).commit()
        }.getOrDefault(false)
    }

    override fun clear() {
        synchronized(writeLock) {
            clearLocked()
        }
    }

    override fun clearIfRevision(expectedRevision: Long): Boolean = synchronized(writeLock) {
        if (sessionRevision.get() != expectedRevision) return@synchronized false
        clearLocked()
        true
    }

    private fun clearLocked() {
        sessionRevision.incrementAndGet()
        blocked.set(true)
        guard.edit().putBoolean(LOGGED_OUT, true).commit()
        runCatching { secure?.edit()?.clear()?.commit() }
        fallback.edit().clear().commit()
    }

    private companion object {
        const val FILE = "cqmu_ekta_secure"
        const val FALLBACK = "cqmu_ekta_insecure_fallback"
        const val GUARD = "cqmu_ekta_security_guard"
        const val LOGGED_OUT = "logged_out"
        const val OWNER = "owner_student_no"
        const val ACCOUNT = "account"
        const val PASSWORD = "password"
        const val TOKEN = "access_token"
        const val ACHIEVEMENT = "achievement_snapshot"
        const val ACHIEVEMENT_DETAILS = "achievement_details_snapshot"
    }
}
