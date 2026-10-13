package com.cqmu.assistant.ui.login

import com.cqmu.assistant.data.LoginStep
import com.cqmu.assistant.data.UserInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class LoginAutomaticLoginTest {
    @Test
    fun `remembered credentials are submitted exactly once`() = runTest {
        var calls = 0
        val result = attemptAutomaticLogin(
            challenge = LoginStep.Challenge("execution", false, null),
            credentials = "student" to "password"
        ) { execution, username, password ->
            calls += 1
            assertEquals("execution", execution)
            assertEquals("student", username)
            assertEquals("password", password)
            LoginStep.Success(UserInfo("测试用户", "2024000000"))
        }

        assertEquals(1, calls)
        assertEquals("2024000000", (result as LoginStep.Success).user.studentNo)
    }

    @Test
    fun `captcha challenge never triggers automatic password submission`() = runTest {
        var calls = 0
        val result = attemptAutomaticLogin(
            challenge = LoginStep.Challenge("execution", true, null),
            credentials = "student" to "password"
        ) { _, _, _ ->
            calls += 1
            LoginStep.Success(UserInfo("不应调用", "0"))
        }

        assertNull(result)
        assertEquals(0, calls)
    }

    @Test
    fun `encrypted storage failure produces an automatic login warning`() {
        assertNull(automaticLoginStorageWarning(saved = true))
        assertNotNull(automaticLoginStorageWarning(saved = false))
    }
}
