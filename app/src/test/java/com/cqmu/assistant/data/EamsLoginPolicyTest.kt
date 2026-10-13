package com.cqmu.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EamsLoginPolicyTest {

    @Test
    fun `mfa preflight is enabled only by explicit true flag`() {
        assertTrue(EamsLoginPolicy.isMfaEnabled("var mfaEnabled = \"true\" == \"true\";"))
        assertTrue(EamsLoginPolicy.isMfaEnabled("var mfaEnabled = true;"))
        assertFalse(EamsLoginPolicy.isMfaEnabled("var mfaEnabled = \"false\" == \"true\";"))
        assertFalse(EamsLoginPolicy.isMfaEnabled("<html>no flag</html>"))
    }

    @Test
    fun `failed login page refresh keeps the known mfa requirement`() {
        assertTrue(EamsLoginPolicy.resolveMfaEnabled(refreshedPage = null, initiallyEnabled = true))
        assertFalse(EamsLoginPolicy.resolveMfaEnabled(refreshedPage = null, initiallyEnabled = false))
        assertTrue(
            EamsLoginPolicy.resolveMfaEnabled(
                refreshedPage = "var mfaEnabled = false;",
                initiallyEnabled = true
            )
        )
        assertTrue(
            EamsLoginPolicy.resolveMfaEnabled(
                refreshedPage = "<html>temporary maintenance page</html>",
                initiallyEnabled = true
            )
        )
    }

    @Test
    fun `successful mfa preflight returns opaque state and requirement`() {
        assertEquals(
            MfaDetection(state = "synthetic-state", required = false),
            EamsLoginPolicy.parseMfaDetection(
                """{"code":0,"data":{"state":"synthetic-state","need":false}}"""
            )
        )
        assertEquals(
            MfaDetection(state = "synthetic-required", required = true),
            EamsLoginPolicy.parseMfaDetection(
                """{"code":"0","data":{"state":"synthetic-required","need":true}}"""
            )
        )
    }

    @Test
    fun `mfa preflight rejects malformed or unsuccessful responses`() {
        listOf(
            "not-json",
            """{"code":1,"data":{"state":"synthetic","need":false}}""",
            """{"code":0,"data":{"state":"","need":false}}""",
            """{"code":0,"data":{"state":"synthetic"}}""",
            """{"code":0,"data":{"state":"synthetic","need":"false"}}"""
        ).forEach { response ->
            assertThrows(EamsException::class.java) {
                EamsLoginPolicy.parseMfaDetection(response)
            }
        }
    }

    @Test
    fun `only official account maintenance redirects offer the official login action`() {
        listOf(
            "https://authx-service.paas.cqmu.edu.cn/user-completed-pwd",
            "https://authx-service.paas.cqmu.edu.cn/user-completed?ticket=synthetic"
        ).forEach { redirect ->
            assertEquals(
                "https://jiaowu.cqmu.edu.cn/eams/login.action",
                EamsLoginPolicy.accountMaintenanceAction(redirect)
            )
        }

        listOf(
            "https://authx-service.paas.cqmu.edu.cn/login",
            "https://authx-service.paas.cqmu.edu.cn.evil.invalid/user-completed-pwd",
            "https://example.invalid/?next=/user-completed-pwd",
            "not-a-url"
        ).forEach { redirect ->
            assertEquals(null, EamsLoginPolicy.accountMaintenanceAction(redirect))
        }
    }
}
