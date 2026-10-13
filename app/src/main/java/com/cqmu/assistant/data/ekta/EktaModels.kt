package com.cqmu.assistant.data.ekta

data class EktaDimensionScore(val name: String, val score: Double)

data class EktaAchievement(
    val totalScore: Double,
    val dimensions: List<EktaDimensionScore>,
    val totalHours: Double? = null
)

data class EktaAchievementRecord(
    val amount: Double,
    val hours: Double,
    val sourceType: Int,
    val classifyId: Long,
    val name: String,
    val relationId: Long,
    val timestampMillis: Long,
    val type: Int
)

data class EktaAchievementCategoryDetails(
    val name: String,
    val classifyId: Long,
    val totalHours: Double,
    val minimumHours: Double,
    val termMinimumHours: Double,
    val records: List<EktaAchievementRecord>
)

data class EktaAchievementDetails(
    val categories: List<EktaAchievementCategoryDetails>
) {
    fun category(name: String): EktaAchievementCategoryDetails? =
        categories.firstOrNull { it.name == name }
}

data class EktaCachedAchievementDetails(
    val details: EktaAchievementDetails,
    val updatedAtMillis: Long
)

data class EktaCredentials(val account: String, val password: String) {
    override fun toString(): String =
        "EktaCredentials(account=<redacted>, password=<redacted>)"
}

data class EktaSession(
    val ownerStudentNo: String,
    val account: String,
    val token: String
) {
    override fun toString(): String =
        "EktaSession(ownerStudentNo=<redacted>, account=<redacted>, token=<redacted>)"
}

interface EktaDataSource {
    suspend fun login(account: String, password: String): String
    suspend fun achievement(token: String): EktaAchievement
    suspend fun achievementDetails(token: String): EktaAchievementDetails =
        throw EktaException("二课明细暂不可用")
}

interface EktaSessionStorage {
    fun revision(): Long
    fun loadSession(): EktaSession?
    fun loadCredentials(): EktaCredentials?
    fun loadAchievement(ownerStudentNo: String, account: String): EktaAchievement?
    fun loadAchievementDetails(
        ownerStudentNo: String,
        account: String
    ): EktaCachedAchievementDetails? = null
    fun save(
        ownerStudentNo: String,
        account: String,
        password: String,
        token: String,
        expectedRevision: Long
    ): Boolean
    fun updateToken(
        ownerStudentNo: String,
        account: String,
        token: String,
        expectedRevision: Long
    ): Boolean
    fun saveAchievement(
        ownerStudentNo: String,
        account: String,
        achievement: EktaAchievement,
        expectedRevision: Long
    ): Boolean
    fun saveAchievementDetails(
        ownerStudentNo: String,
        account: String,
        details: EktaCachedAchievementDetails,
        expectedRevision: Long
    ): Boolean = false
    fun clearIfRevision(expectedRevision: Long): Boolean
    fun clear()
}

open class EktaException(message: String) : Exception(message)

class EktaAuthenticationException : EktaException("二课登录已失效，请重新登录")

class EktaCredentialException : EktaException("二课账号或密码错误")
