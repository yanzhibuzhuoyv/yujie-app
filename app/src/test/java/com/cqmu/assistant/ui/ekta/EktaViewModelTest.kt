package com.cqmu.assistant.ui.ekta

import com.cqmu.assistant.data.ekta.EktaAchievement
import com.cqmu.assistant.data.ekta.EktaAchievementCategoryDetails
import com.cqmu.assistant.data.ekta.EktaAchievementDetails
import com.cqmu.assistant.data.ekta.EktaAchievementRecord
import com.cqmu.assistant.data.ekta.EktaAuthenticationException
import com.cqmu.assistant.data.ekta.EktaCachedAchievementDetails
import com.cqmu.assistant.data.ekta.EktaCredentials
import com.cqmu.assistant.data.ekta.EktaCredentialException
import com.cqmu.assistant.data.ekta.EktaDataSource
import com.cqmu.assistant.data.ekta.EktaDimensionScore
import com.cqmu.assistant.data.ekta.EktaException
import com.cqmu.assistant.data.ekta.EktaSession
import com.cqmu.assistant.data.ekta.EktaSessionStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EktaViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `expired token triggers at most one silent login and refresh`() = runTest(dispatcher) {
        val store = MemoryStore(
            session = EktaSession("owner", "student", "expired"),
            credentials = EktaCredentials("student", "password")
        )
        var loginCalls = 0
        val source = object : EktaDataSource {
            override suspend fun login(account: String, password: String): String {
                loginCalls += 1
                return "fresh"
            }
            override suspend fun achievement(token: String): EktaAchievement {
                if (token == "expired") throw EktaAuthenticationException()
                return achievement(8.0)
            }
        }

        val vm = EktaViewModel(source, store, "owner")
        advanceUntilIdle()

        assertEquals(1, loginCalls)
        assertEquals(8.0, vm.state.value.achievement?.totalScore ?: -1.0, 0.0)
        assertFalse(vm.state.value.showLogin)
        assertEquals("fresh", store.session?.token)
    }

    @Test
    fun `stored credentials belonging to another eams owner are cleared`() = runTest(dispatcher) {
        val store = MemoryStore(
            session = EktaSession("old-owner", "student", "token"),
            credentials = EktaCredentials("student", "password")
        )
        val source = object : EktaDataSource {
            override suspend fun login(account: String, password: String) = "unused"
            override suspend fun achievement(token: String) = achievement(1.0)
        }

        val vm = EktaViewModel(source, store, "new-owner")
        advanceUntilIdle()

        assertEquals(1, store.clearCalls)
        assertTrue(vm.state.value.showLogin)
        assertNull(vm.state.value.achievement)
    }

    @Test
    fun `invalid stored password is attempted once then cleared`() = runTest(dispatcher) {
        val store = MemoryStore(
            session = EktaSession("owner", "student", "expired"),
            credentials = EktaCredentials("student", "wrong")
        )
        var loginCalls = 0
        val source = object : EktaDataSource {
            override suspend fun login(account: String, password: String): String {
                loginCalls += 1
                throw EktaCredentialException()
            }
            override suspend fun achievement(token: String): EktaAchievement {
                throw EktaAuthenticationException()
            }
        }

        val vm = EktaViewModel(source, store, "owner")
        advanceUntilIdle()

        assertEquals(1, loginCalls)
        assertEquals(1, store.clearCalls)
        assertTrue(vm.state.value.showLogin)
        assertNull(store.credentials)
    }

    @Test
    fun `temporary login business error keeps credentials but shows login`() = runTest(dispatcher) {
        val store = MemoryStore(
            session = EktaSession("owner", "student", "expired"),
            credentials = EktaCredentials("student", "password")
        )
        val source = object : EktaDataSource {
            override suspend fun login(account: String, password: String): String {
                throw EktaException("二课服务维护中")
            }
            override suspend fun achievement(token: String): EktaAchievement {
                throw EktaAuthenticationException()
            }
        }

        val vm = EktaViewModel(source, store, "owner")
        advanceUntilIdle()

        assertEquals(0, store.clearCalls)
        assertTrue(vm.state.value.showLogin)
        assertEquals("student", store.credentials?.account)
    }

    @Test
    fun `stale manual login cannot overwrite a newer login`() = runTest(dispatcher) {
        val store = MemoryStore()
        val source = object : EktaDataSource {
            override suspend fun login(account: String, password: String): String {
                if (account == "slow") withContext(NonCancellable) { delay(10_000) }
                return "token-$account"
            }
            override suspend fun achievement(token: String): EktaAchievement =
                achievement(if (token.endsWith("fast")) 2.0 else 1.0)
        }
        val vm = EktaViewModel(source, store, "owner")
        advanceUntilIdle()

        vm.login("slow", "one")
        runCurrent()
        vm.login("fast", "two")
        advanceUntilIdle()

        assertEquals(2.0, vm.state.value.achievement?.totalScore ?: -1.0, 0.0)
        assertEquals("fast", store.session?.account)
    }

    @Test
    fun `external clear rejects a late login write`() = runTest(dispatcher) {
        val store = MemoryStore()
        val source = object : EktaDataSource {
            override suspend fun login(account: String, password: String): String {
                withContext(NonCancellable) { delay(10_000) }
                return "late-token"
            }
            override suspend fun achievement(token: String) = achievement(3.0)
        }
        val vm = EktaViewModel(source, store, "owner")
        advanceUntilIdle()

        vm.login("student", "password")
        runCurrent()
        store.clear()
        advanceUntilIdle()

        assertNull(store.session)
        assertNull(store.credentials)
    }

    @Test
    fun `storage failure returns to login with an explicit error`() = runTest(dispatcher) {
        val store = MemoryStore(failWrites = true)
        val source = object : EktaDataSource {
            override suspend fun login(account: String, password: String) = "token"
            override suspend fun achievement(token: String) = achievement(3.0)
        }
        val vm = EktaViewModel(source, store, "owner")
        advanceUntilIdle()

        vm.login("student", "password")
        advanceUntilIdle()

        assertFalse(vm.state.value.busy)
        assertTrue(vm.state.value.showLogin)
        assertTrue(vm.state.value.message.orEmpty().contains("加密存储"))
    }

    @Test
    fun `refresh failure keeps last achievement and marks fallback data`() = runTest(dispatcher) {
        val store = MemoryStore(
            session = EktaSession("owner", "student", "token"),
            credentials = EktaCredentials("student", "password")
        )
        var failRefresh = false
        val source = object : EktaDataSource {
            override suspend fun login(account: String, password: String) = "token"
            override suspend fun achievement(token: String): EktaAchievement {
                if (failRefresh) throw EktaException("服务暂时不可用")
                return achievement(6.0)
            }
        }
        val vm = EktaViewModel(source, store, "owner")
        advanceUntilIdle()

        failRefresh = true
        vm.refresh()
        advanceUntilIdle()

        assertEquals(6.0, vm.state.value.achievement?.totalScore ?: -1.0, 0.0)
        assertTrue(vm.state.value.usingFallback)
        assertTrue(vm.state.value.message.orEmpty().contains("暂时不可用"))
    }

    @Test
    fun `restore failure uses encrypted achievement snapshot`() = runTest(dispatcher) {
        val store = MemoryStore(
            session = EktaSession("owner", "student", "token"),
            credentials = EktaCredentials("student", "password"),
            cachedAchievement = achievement(4.0)
        )
        val source = object : EktaDataSource {
            override suspend fun login(account: String, password: String) = "token"
            override suspend fun achievement(token: String): EktaAchievement {
                throw EktaException("网络更新失败")
            }
        }

        val vm = EktaViewModel(source, store, "owner")
        advanceUntilIdle()

        assertEquals(4.0, vm.state.value.achievement?.totalScore ?: -1.0, 0.0)
        assertTrue(vm.state.value.usingFallback)
        assertFalse(vm.state.value.showLogin)
    }

    @Test
    fun `late authentication failure cannot clear a newer external session`() = runTest(dispatcher) {
        val store = MemoryStore(
            session = EktaSession("owner", "old", "old-token"),
            credentials = EktaCredentials("old", "old-password")
        )
        val source = object : EktaDataSource {
            override suspend fun login(account: String, password: String) = "unused"
            override suspend fun achievement(token: String): EktaAchievement {
                withContext(NonCancellable) { delay(10_000) }
                throw EktaAuthenticationException()
            }
        }
        EktaViewModel(source, store, "owner")
        runCurrent()

        store.clear()
        val current = store.revision()
        store.save("owner", "new", "new-password", "new-token", current)
        advanceUntilIdle()

        assertEquals("new-token", store.session?.token)
        assertEquals("new", store.credentials?.account)
    }

    @Test
    fun `opening a dimension lazily loads its records without changing summary`() = runTest(dispatcher) {
        val store = MemoryStore(
            session = EktaSession("owner", "student", "token"),
            credentials = EktaCredentials("student", "password")
        )
        var detailCalls = 0
        val source = object : EktaDataSource {
            override suspend fun login(account: String, password: String) = "token"
            override suspend fun achievement(token: String) = achievement(6.0)
            override suspend fun achievementDetails(token: String): EktaAchievementDetails {
                detailCalls += 1
                return details("德育", 2_000L, 1_000L)
            }
        }
        val vm = EktaViewModel(source, store, "owner")
        advanceUntilIdle()

        assertEquals(0, detailCalls)
        vm.openDimension("德育")
        advanceUntilIdle()

        assertEquals(1, detailCalls)
        assertEquals("德育", vm.state.value.selectedDimension)
        assertEquals(listOf(2_000L, 1_000L), vm.state.value.detail?.records?.map { it.timestampMillis })
        assertEquals(6.0, vm.state.value.achievement?.totalScore ?: -1.0, 0.0)
        assertFalse(vm.state.value.detailUsingFallback)
        assertTrue(store.cachedDetails != null)
    }

    @Test
    fun `detail refresh failure keeps encrypted cached records and marks fallback`() = runTest(dispatcher) {
        val cached = EktaCachedAchievementDetails(details("德育", 1_000L), 50_000L)
        val store = MemoryStore(
            session = EktaSession("owner", "student", "token"),
            credentials = EktaCredentials("student", "password"),
            cachedDetails = cached
        )
        val source = object : EktaDataSource {
            override suspend fun login(account: String, password: String) = "token"
            override suspend fun achievement(token: String) = achievement(6.0)
            override suspend fun achievementDetails(token: String): EktaAchievementDetails {
                throw EktaException("明细更新失败")
            }
        }
        val vm = EktaViewModel(source, store, "owner")
        advanceUntilIdle()

        vm.openDimension("德育")
        advanceUntilIdle()

        assertEquals(listOf(1_000L), vm.state.value.detail?.records?.map { it.timestampMillis })
        assertEquals(50_000L, vm.state.value.detailUpdatedAtMillis)
        assertTrue(vm.state.value.detailUsingFallback)
        assertTrue(vm.state.value.detailMessage.orEmpty().contains("明细更新失败"))
        assertFalse(vm.state.value.usingFallback)
        assertEquals(6.0, vm.state.value.achievement?.totalScore ?: -1.0, 0.0)
    }

    @Test
    fun `expired detail token triggers one silent login and one retry`() = runTest(dispatcher) {
        val store = MemoryStore(
            session = EktaSession("owner", "student", "expired"),
            credentials = EktaCredentials("student", "password")
        )
        var loginCalls = 0
        var detailCalls = 0
        val source = object : EktaDataSource {
            override suspend fun login(account: String, password: String): String {
                loginCalls += 1
                return "fresh"
            }
            override suspend fun achievement(token: String) = achievement(6.0)
            override suspend fun achievementDetails(token: String): EktaAchievementDetails {
                detailCalls += 1
                if (token == "expired") throw EktaAuthenticationException()
                return details("德育", 2_000L)
            }
        }
        val vm = EktaViewModel(source, store, "owner")
        advanceUntilIdle()

        vm.openDimension("德育")
        advanceUntilIdle()

        assertEquals(1, loginCalls)
        assertEquals(2, detailCalls)
        assertEquals("fresh", store.session?.token)
        assertEquals(2_000L, vm.state.value.detail?.records?.single()?.timestampMillis)
    }

    @Test
    fun `closing detail preserves summary data`() = runTest(dispatcher) {
        val store = MemoryStore(
            session = EktaSession("owner", "student", "token"),
            credentials = EktaCredentials("student", "password")
        )
        val source = object : EktaDataSource {
            override suspend fun login(account: String, password: String) = "token"
            override suspend fun achievement(token: String) = achievement(6.0)
            override suspend fun achievementDetails(token: String) = details("德育", 2_000L)
        }
        val vm = EktaViewModel(source, store, "owner")
        advanceUntilIdle()

        vm.openDimension("德育")
        advanceUntilIdle()
        vm.closeDimension()

        assertNull(vm.state.value.selectedDimension)
        assertNull(vm.state.value.detail)
        assertEquals(6.0, vm.state.value.achievement?.totalScore ?: -1.0, 0.0)
    }

    @Test
    fun `detail cache write failure keeps live records and reports it`() = runTest(dispatcher) {
        val store = MemoryStore(
            session = EktaSession("owner", "student", "token"),
            credentials = EktaCredentials("student", "password"),
            failWrites = true
        )
        val source = object : EktaDataSource {
            override suspend fun login(account: String, password: String) = "token"
            override suspend fun achievement(token: String) = achievement(6.0)
            override suspend fun achievementDetails(token: String) = details("德育", 2_000L)
        }
        val vm = EktaViewModel(source, store, "owner")
        advanceUntilIdle()

        vm.openDimension("德育")
        advanceUntilIdle()

        assertEquals(2_000L, vm.state.value.detail?.records?.single()?.timestampMillis)
        assertTrue(vm.state.value.detailMessage.orEmpty().contains("加密缓存"))
        assertFalse(vm.state.value.detailUsingFallback)
    }

    private fun achievement(score: Double) = EktaAchievement(
        score,
        listOf(EktaDimensionScore("德育", 1.0))
    )

    private fun details(name: String, vararg timestamps: Long) = EktaAchievementDetails(
        listOf(
            EktaAchievementCategoryDetails(
                name = name,
                classifyId = 1,
                totalHours = 3.0,
                minimumHours = 1.0,
                termMinimumHours = 0.5,
                records = timestamps.mapIndexed { index, timestamp ->
                    EktaAchievementRecord(
                        amount = 1.0,
                        hours = 1.0,
                        sourceType = 1,
                        classifyId = 1,
                        name = "记录$index",
                        relationId = index.toLong(),
                        timestampMillis = timestamp,
                        type = 1
                    )
                }
            )
        )
    )

    private class MemoryStore(
        var session: EktaSession? = null,
        var credentials: EktaCredentials? = null,
        var cachedAchievement: EktaAchievement? = null,
        var cachedDetails: EktaCachedAchievementDetails? = null,
        private val failWrites: Boolean = false
    ) : EktaSessionStorage {
        private var currentRevision = 0L
        var clearCalls = 0
        override fun revision(): Long = currentRevision
        override fun loadSession(): EktaSession? = session
        override fun loadCredentials(): EktaCredentials? = credentials
        override fun loadAchievement(
            ownerStudentNo: String,
            account: String
        ): EktaAchievement? = cachedAchievement?.takeIf {
            session?.ownerStudentNo == ownerStudentNo && session?.account == account
        }
        override fun loadAchievementDetails(
            ownerStudentNo: String,
            account: String
        ): EktaCachedAchievementDetails? = cachedDetails?.takeIf {
            session?.ownerStudentNo == ownerStudentNo && session?.account == account
        }
        override fun save(
            ownerStudentNo: String,
            account: String,
            password: String,
            token: String,
            expectedRevision: Long
        ): Boolean {
            if (expectedRevision != currentRevision) return false
            if (failWrites) return false
            if (session?.ownerStudentNo != ownerStudentNo || session?.account != account) {
                cachedAchievement = null
                cachedDetails = null
            }
            session = EktaSession(ownerStudentNo, account, token)
            credentials = EktaCredentials(account, password)
            return true
        }
        override fun updateToken(
            ownerStudentNo: String,
            account: String,
            token: String,
            expectedRevision: Long
        ): Boolean {
            if (expectedRevision != currentRevision) return false
            if (failWrites) return false
            session = EktaSession(ownerStudentNo, account, token)
            return true
        }
        override fun saveAchievement(
            ownerStudentNo: String,
            account: String,
            achievement: EktaAchievement,
            expectedRevision: Long
        ): Boolean {
            if (expectedRevision != currentRevision || failWrites) return false
            if (session?.ownerStudentNo != ownerStudentNo || session?.account != account) return false
            cachedAchievement = achievement
            return true
        }
        override fun saveAchievementDetails(
            ownerStudentNo: String,
            account: String,
            details: EktaCachedAchievementDetails,
            expectedRevision: Long
        ): Boolean {
            if (expectedRevision != currentRevision || failWrites) return false
            if (session?.ownerStudentNo != ownerStudentNo || session?.account != account) return false
            cachedDetails = details
            return true
        }
        override fun clear() {
            currentRevision += 1
            clearCalls += 1
            session = null
            credentials = null
            cachedAchievement = null
            cachedDetails = null
        }
        override fun clearIfRevision(expectedRevision: Long): Boolean {
            if (expectedRevision != currentRevision) return false
            clear()
            return true
        }
    }
}
