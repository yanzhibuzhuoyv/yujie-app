package com.cqmu.assistant.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilentLoginRecoveryTest {

    private val user = UserInfo(name = "演示用户", studentNo = "2024000000")

    @Test
    fun `existing CAS session is already recovered without credential submission`() = runBlocking {
        var submitted = false

        val recovered = completeSilentLogin(LoginStep.Success(user)) { _, _, _ ->
            submitted = true
            LoginStep.Success(user)
        }

        assertTrue(recovered)
        assertFalse(submitted)
    }

    @Test
    fun `fresh challenge submits saved credentials`() = runBlocking {
        val recovered = completeSilentLogin(
            step = LoginStep.Challenge(execution = "fresh", needCaptcha = false, message = null),
            username = "saved-user",
            password = "saved-password"
        ) { execution, username, password ->
            assertTrue(execution == "fresh")
            assertTrue(username == "saved-user")
            assertTrue(password == "saved-password")
            LoginStep.Success(user)
        }

        assertTrue(recovered)
    }

    @Test
    fun `captcha challenge is not submitted silently`() = runBlocking {
        var submitted = false

        val recovered = completeSilentLogin(
            step = LoginStep.Challenge(execution = "fresh", needCaptcha = true, message = null),
            username = "saved-user",
            password = "saved-password"
        ) { _, _, _ ->
            submitted = true
            LoginStep.Success(user)
        }

        assertFalse(recovered)
        assertFalse(submitted)
    }

    @Test
    fun `credential challenge without saved credentials fails closed`() = runBlocking {
        var submitted = false

        val recovered = completeSilentLogin(
            LoginStep.Challenge(execution = "fresh", needCaptcha = false, message = null)
        ) { _, _, _ ->
            submitted = true
            LoginStep.Success(user)
        }

        assertFalse(recovered)
        assertFalse(submitted)
    }

    @Test
    fun `all known authentication gateways are detected`() {
        assertTrue(isAuthenticationRedirect("https://jiaowu.cqmu.edu.cn/eams/sso/login"))
        assertTrue(isAuthenticationRedirect("https://cas.cqmu.edu.cn/cas/login?service=eams"))
        assertTrue(isAuthenticationRedirect("https://jiaowu.cqmu.edu.cn/rump_frontend/login/"))
        assertTrue(isAuthenticationRedirect("/eams/sso/login"))
        assertFalse(isAuthenticationRedirect("https://jiaowu.cqmu.edu.cn/eams/home.action"))
        assertFalse(isAuthenticationRedirect("https://example.invalid/?next=/cas/login"))
    }

    @Test
    fun `concurrent request reuses recovery only when no logout intervened`() {
        assertTrue(
            canReuseRecoveredSession(
                observedGeneration = 2,
                currentGeneration = 3,
                lastLogoutGeneration = -1,
                sessionActive = true
            )
        )
        assertFalse(
            canReuseRecoveredSession(
                observedGeneration = 2,
                currentGeneration = 4,
                lastLogoutGeneration = 3,
                sessionActive = true
            )
        )
        assertFalse(
            canReuseRecoveredSession(
                observedGeneration = 2,
                currentGeneration = 3,
                lastLogoutGeneration = 3,
                sessionActive = false
            )
        )
    }

    @Test
    fun `silent login is attempted at most once per session generation`() {
        assertTrue(canStartSilentAttempt(observedGeneration = 7, lastAttemptGeneration = 6))
        assertFalse(canStartSilentAttempt(observedGeneration = 7, lastAttemptGeneration = 7))
        assertTrue(canStartSilentAttempt(observedGeneration = 8, lastAttemptGeneration = 7))
    }
}
