package com.cqmu.assistant

/** Removes credential-like values and user identifiers before local diagnostic text is persisted. */
object PrivacySanitizer {
    private const val MAX_CRASH_CHARS = 32_768
    private const val SENSITIVE_KEYS =
        "password|passwd|pwd|token|jsessionid|execution|account|ownerStudentNo|" +
            "owner_student_no|studentNo|student_no|username"

    private val cookieHeader = Regex("(?im)(cookie\\s*[:=]\\s*)[^\\r\\n]+")
    private val authorization = Regex(
        "(?i)(authorization\\s*[:=]\\s*)(?:bearer\\s+)?[^\\s,;}]+"
    )
    private val quotedNamedSecret = Regex(
        "(?i)([\"']?(?:$SENSITIVE_KEYS)[\"']?\\s*[:=]\\s*)" +
            "([\"'])(?:\\\\.|(?!\\2).)*\\2"
    )
    private val namedSecret = Regex(
        "(?i)([\"']?(?:$SENSITIVE_KEYS)[\"']?\\s*[:=]\\s*)" +
            "([^\"'\\s,;}]+)"
    )
    private val urlQuery = Regex("(https?://[^\\s?]+)\\?[^\\s]+", RegexOption.IGNORE_CASE)
    private val longIdentifier = Regex("(?<![A-Za-z0-9])\\d{9,18}(?![A-Za-z0-9])")

    fun crashText(raw: String): String {
        val sanitized = raw
            .replace(cookieHeader, "$1<redacted>")
            .replace(authorization, "$1<redacted>")
            .replace(quotedNamedSecret, "$1<redacted>")
            .replace(namedSecret, "$1<redacted>")
            .replace(urlQuery, "$1?<redacted>")
            .replace(longIdentifier, "<redacted-id>")
        return sanitized.take(MAX_CRASH_CHARS)
    }
}
