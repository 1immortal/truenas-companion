package app.truenascompanion.data.api

import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.data.net.HttpClients
import app.truenascompanion.util.UrlUtils
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** A reusable session token obtained with `auth.generate_token` after an interactive sign-in. */
data class IssuedToken(val token: String, val expiresAt: Long) {
    val isExpired: Boolean get() = System.currentTimeMillis() >= expiresAt
}

sealed interface Credentials {
    data class Password(val username: String, val password: String) : Credentials
    data class Token(val token: String) : Credentials
}

sealed interface LoginStep {
    /** Signed in. [token] is a fresh reusable session token (null if the server refused to issue one). */
    class Success(val api: WebSocketTrueNasApi, val token: IssuedToken?) : LoginStep

    /** Password accepted, TrueNAS wants a 2FA code. The WebSocket stays open in [pending] until the code is submitted. */
    class OtpRequired(val pending: PendingOtp, val username: String) : LoginStep
}

/**
 * Second step of a password sign-in. The OTP must be sent on the same WebSocket connection that received
 * `OTP_REQUIRED`, so this object owns that connection until [submit] succeeds or [cancel] is called.
 */
class PendingOtp internal constructor(
    private val rpc: JsonRpcClient,
    private val ttlSeconds: Long,
    val username: String,
) {
    val isAlive: Boolean get() = rpc.isOpen

    /**
     * Returns [LoginStep.Success], or [LoginStep.OtpRequired] (same pending object) when the code was wrong and may be retried.
     * Throws [TrueNasException.OtpLockout] after too many wrong codes (`AUTH_ERR`).
     */
    suspend fun submit(code: String): LoginStep {
        val data = buildJsonObject {
            put("mechanism", "OTP_TOKEN")
            put("otp_token", code.trim())
        }
        val res = try {
            // 25.04+: convenience wrapper documented for continuing an OTP_REQUIRED attempt.
            rpc.call("auth.login_ex_continue", JsonArray(listOf(data)))
        } catch (e: Throwable) {
            if (!e.isMethodMissing()) throw e
            rpc.call("auth.login_ex", JsonArray(listOf(data)))
        }
        return when (res.responseType()) {
            "SUCCESS" -> WebSocketAuth.success(rpc, ttlSeconds)
            "OTP_REQUIRED" -> LoginStep.OtpRequired(this, username)
            "AUTH_ERR" -> { rpc.close(); throw TrueNasException.OtpLockout() }
            else -> { rpc.close(); throw WebSocketAuth.failure(res) }
        }
    }

    fun cancel() = rpc.close()
}

/** Username/password (+2FA) and session-token sign-in over the JSON-RPC WebSocket API (TrueNAS 25.04+). */
object WebSocketAuth {

    suspend fun login(server: ServerConfig, credentials: Credentials, ttlSeconds: Long): LoginStep {
        val (client, tm) = HttpClients.create(server)
        val rpc = JsonRpcClient(client, UrlUtils.webSocketUrl(server.url), tm)
        try {
            rpc.open()
        } catch (e: TrueNasException.EndpointNotFound) {
            throw TrueNasException.AuthFailed(
                "Password sign-in needs TrueNAS 25.04 or newer (WebSocket API at /api/current). Use an API key on older releases."
            )
        }
        try {
            return when (credentials) {
                is Credentials.Password -> {
                    val res = rpc.call("auth.login_ex", JsonArray(listOf(buildJsonObject {
                        put("mechanism", "PASSWORD_PLAIN")
                        put("username", credentials.username)
                        put("password", credentials.password)
                    })))
                    when (res.responseType()) {
                        "SUCCESS" -> success(rpc, ttlSeconds)
                        "OTP_REQUIRED" -> {
                            val user = res.obj()?.str("username") ?: credentials.username
                            LoginStep.OtpRequired(PendingOtp(rpc, ttlSeconds, user), user)
                        }
                        "AUTH_ERR" -> throw TrueNasException.PasswordRejected()
                        else -> throw failure(res)
                    }
                }
                is Credentials.Token -> {
                    val ok = try {
                        val res = rpc.call("auth.login_ex", JsonArray(listOf(buildJsonObject {
                            put("mechanism", "TOKEN_PLAIN")
                            put("token", credentials.token)
                        })))
                        res.responseType() == "SUCCESS"
                    } catch (e: Throwable) {
                        if (!e.isMethodMissing()) throw e
                        rpc.call("auth.login_with_token", JsonArray(listOf(JsonPrimitive(credentials.token)))).prim()?.booleanOrNull == true
                    }
                    if (!ok) throw TrueNasException.TokenRejected()
                    success(rpc, ttlSeconds)
                }
            }
        } catch (e: Throwable) {
            rpc.close()
            throw e
        }
    }

    /** Wraps the authenticated connection and asks for a fresh reusable token (sliding expiry). */
    internal suspend fun success(rpc: JsonRpcClient, ttlSeconds: Long): LoginStep.Success {
        val api = WebSocketTrueNasApi(rpc)
        val issuedAt = System.currentTimeMillis()
        val token = api.generateToken(ttlSeconds)?.let { IssuedToken(it, issuedAt + ttlSeconds * 1000) }
        return LoginStep.Success(api, token)
    }

    internal fun failure(res: JsonElement): TrueNasException = when (res.responseType()) {
        "EXPIRED" -> TrueNasException.AuthFailed("This account's password has expired. Change it in the TrueNAS web UI, then sign in again.")
        "REDIRECT" -> {
            val urls = res.obj()?.get("urls").arr()?.mapNotNull { it.prim()?.contentOrNull }.orEmpty()
            TrueNasException.AuthFailed(
                "TrueNAS asks to sign in on another server" + if (urls.isNotEmpty()) ": ${urls.joinToString()}" else "."
            )
        }
        "AUTH_ERR" -> TrueNasException.PasswordRejected()
        else -> TrueNasException.AuthFailed("Unexpected sign-in response from TrueNAS: ${res.responseType() ?: res.toString().take(80)}")
    }
}

private fun JsonElement.responseType(): String? = (this as? JsonObject)?.str("response_type")
