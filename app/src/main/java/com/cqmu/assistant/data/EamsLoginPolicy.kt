package com.cqmu.assistant.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

data class MfaDetection(
    val state: String,
    val required: Boolean
)

/** 统一身份认证登录页及 MFA 预检响应的严格解析边界。 */
object EamsLoginPolicy {
    private val enabledMfa = Regex(
        """var\s+mfaEnabled\s*=\s*(?:true|["']true["']\s*==\s*["']true["'])\s*;""",
        RegexOption.IGNORE_CASE
    )

    fun isMfaEnabled(loginPage: String): Boolean = enabledMfa.containsMatchIn(loginPage)

    fun resolveMfaEnabled(refreshedPage: String?, initiallyEnabled: Boolean): Boolean =
        initiallyEnabled || refreshedPage?.let(::isMfaEnabled) == true

    fun accountMaintenanceAction(finalUrl: String): String? {
        val url = finalUrl.toHttpUrlOrNull() ?: return null
        if (url.host != ACCOUNT_CENTER_HOST) return null
        if (url.encodedPath !in ACCOUNT_MAINTENANCE_PATHS) return null
        return OFFICIAL_LOGIN_URL
    }

    fun parseMfaDetection(response: String): MfaDetection {
        val root = runCatching { JSONObject(response) }
            .getOrElse { throw invalidMfaResponse() }
        val code = when (val raw = root.opt("code")) {
            is Number -> raw.toInt().toString()
            is String -> raw.trim()
            else -> null
        }
        if (code != "0") throw invalidMfaResponse()

        val data = root.optJSONObject("data") ?: throw invalidMfaResponse()
        val state = data.opt("state") as? String ?: throw invalidMfaResponse()
        val required = data.opt("need") as? Boolean ?: throw invalidMfaResponse()
        if (state.isBlank() || state.length > MAX_MFA_STATE_LENGTH) throw invalidMfaResponse()
        return MfaDetection(state = state, required = required)
    }

    private fun invalidMfaResponse() =
        EamsException("统一身份认证预检响应无效，请稍后重试")

    private const val MAX_MFA_STATE_LENGTH = 4096
    private const val ACCOUNT_CENTER_HOST = "authx-service.paas.cqmu.edu.cn"
    private val ACCOUNT_MAINTENANCE_PATHS = setOf("/user-completed", "/user-completed-pwd")
    const val OFFICIAL_LOGIN_URL = "https://jiaowu.cqmu.edu.cn/eams/login.action"
}
