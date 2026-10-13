package com.cqmu.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacySanitizerTest {
    @Test
    fun `crash report removes secrets identifiers and url queries`() {
        val raw = """
            password=demo-secret Cookie: session=session-value; JSESSIONID=cookie-secret
            "password":"json-secret" Authorization: Bearer bearer-secret
            password="two words" account:'user name' owner_student_no="owner value"
            token="escaped \"quote\" value"
            token = opaque-value execution=flow-value
            account=letter-account ownerStudentNo:owner-id username=user-name
            account 2000000001
            https://example.invalid/path?account=2000000001&token=opaque-value
            java.lang.IllegalStateException: useful stack message
        """.trimIndent()

        val result = PrivacySanitizer.crashText(raw)

        listOf(
            "demo-secret", "session-value", "cookie-secret", "json-secret",
            "bearer-secret", "opaque-value", "flow-value", "letter-account", "owner-id",
            "user-name", "two words", "user name", "owner value", "escaped", "quote",
            "2000000001"
        )
            .forEach { sensitive -> assertFalse(result.contains(sensitive)) }
        assertTrue(result.contains("useful stack message"))
        assertTrue(result.contains("<redacted>"))
    }

    @Test
    fun `crash report has a bounded size`() {
        assertTrue(PrivacySanitizer.crashText("x".repeat(100_000)).length <= 32_768)
    }
}
