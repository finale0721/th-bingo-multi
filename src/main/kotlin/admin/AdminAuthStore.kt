package org.tfcc.bingo.admin

import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.kotlin.logger
import org.tfcc.bingo.Dispatcher
import org.tfcc.bingo.PushMessage
import org.tfcc.bingo.Supervisor
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object AdminAuthStore {
    private const val DEFAULT_USERNAME = "admin"
    private const val DEFAULT_PASSWORD = "admin123456"
    private const val TOKEN_TTL_MS = 12 * 60 * 60 * 1000L

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val dataDir = File("data/admin")
    private val accountFile = File(dataDir, "account.json")
    private val sessions = ConcurrentHashMap<String, Session>()

    init {
        ensureStorage()
    }

    fun ensureStorage() {
        ensureDataDir()
        if (!accountFile.exists()) {
            val config = AdminAccountConfig(
                username = DEFAULT_USERNAME,
                password = DEFAULT_PASSWORD,
                updatedAt = System.currentTimeMillis(),
            )
            writeConfig(config)
            logger.warn("Created default admin account file at ${accountFile.absolutePath}")
        }
    }

    fun login(username: String, password: String): AdminLoginResponse? {
        cleanupExpiredSessions()
        val config = readConfig()
        if (config.username != username || config.password != password) {
            return null
        }
        linkedSetOf(config.username)
        val expiresAt = System.currentTimeMillis() + TOKEN_TTL_MS
        val token = UUID.randomUUID().toString().replace("-", "")
        sessions[token] = Session(username = config.username, expiresAt = expiresAt)
        return AdminLoginResponse(token = token, username = config.username, expiresAt = expiresAt)
    }

    fun isAuthorized(authorizationHeader: String?): Boolean {
        cleanupExpiredSessions()
        val token = extractBearerToken(authorizationHeader) ?: return false
        val session = sessions[token] ?: return false
        return session.expiresAt > System.currentTimeMillis()
    }

    val accountFilePath: String
        get() = accountFile.absolutePath

    private fun extractBearerToken(authorizationHeader: String?): String? {
        if (authorizationHeader.isNullOrBlank()) {
            return null
        }
        if (!authorizationHeader.startsWith("Bearer ")) {
            return null
        }
        return authorizationHeader.removePrefix("Bearer ").trim().ifBlank { null }
    }

    private fun cleanupExpiredSessions() {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { it.value.expiresAt <= now }
    }

    @Synchronized
    private fun readConfig(): AdminAccountConfig {
        ensureStorage()
        return try {
            json.decodeFromString<AdminAccountConfig>(accountFile.readText(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            logger.error("Failed to read admin account config, recreating default config", e)
            val config = AdminAccountConfig(
                username = DEFAULT_USERNAME,
                password = DEFAULT_PASSWORD,
                updatedAt = System.currentTimeMillis(),
            )
            writeConfig(config)
            config
        }
    }

    @Synchronized
    private fun writeConfig(config: AdminAccountConfig) {
        ensureDataDir()
        accountFile.writeText(json.encodeToString(config), StandardCharsets.UTF_8)
    }

    private fun ensureDataDir() {
        if (!dataDir.exists()) {
            dataDir.mkdirs()
        }
    }

    private fun kickCurrentGameSession(username: String) {
        val channel = Supervisor.getChannel(username) ?: return
        try {
            val payload = Dispatcher.json.encodeToString(PushMessage("push_kick", null))
            channel.writeAndFlush(TextWebSocketFrame(payload))
            channel.close()
        } catch (e: Exception) {
            logger.warn("Failed to kick current game session for admin user $username", e)
        }
    }

    private data class Session(
        val username: String,
        val expiresAt: Long,
    )
}
