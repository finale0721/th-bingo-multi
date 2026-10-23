package org.tfcc.bingo.admin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.kotlin.logger
import org.tfcc.bingo.Dispatcher
import org.tfcc.bingo.Spell
import org.tfcc.bingo.message.HandlerException
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Timer
import kotlin.concurrent.schedule
import kotlin.concurrent.timerTask

@Serializable
data class GameInfo(
    val code: String,
    val name: String,
)

@Serializable
data class CustomPoolMetadata(
    val md5: String,
    @SerialName("uploader_name")
    val uploaderName: String,
    @SerialName("file_name")
    val fileName: String,
    val note: String,
    @SerialName("row_count")
    val rowCount: Int,
    @SerialName("game_count")
    val gameCount: Int,
    val games: List<GameInfo> = emptyList(),
    @SerialName("created_at")
    val createdAt: Long,
    @SerialName("expires_at")
    val expiresAt: Long,
)

@Serializable
private data class CustomPoolIndex(
    val pools: List<CustomPoolMetadata> = emptyList(),
)

object CustomPoolStore {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val dataDir = File("data/custom-pools")
    private val indexFile = File(dataDir, "index.json")
    private const val TTL_MS = 30L * 24 * 60 * 60 * 1000

    init {
        Timer().schedule(timerTask {
            Dispatcher.pool.submit { cleanupExpired() }
        }, 60_000, 3_600_000)
    }

    fun ensureStorage() {
        if (!dataDir.exists()) {
            dataDir.mkdirs()
        }
        if (!indexFile.exists()) {
            writeIndex(CustomPoolIndex())
        }
    }

    fun savePool(
        uploaderName: String,
        fileName: String,
        note: String,
        xlsxBase64: String,
        spellsJson: String,
        gamesJson: String,
    ): CustomPoolMetadata {
        val xlsxBytes = Base64.getDecoder().decode(xlsxBase64)
        val md5 = MessageDigest.getInstance("MD5").digest(xlsxBytes).toHexString()

        val index = readIndex()
        if (index.pools.any { it.md5 == md5 && it.expiresAt > System.currentTimeMillis() }) {
            throw HandlerException("卡池已存在（MD5相同）")
        }

        val spells = Dispatcher.json.decodeFromString<Array<Spell>>(spellsJson)
        val games = Dispatcher.json.decodeFromString<List<GameInfo>>(gamesJson)

        val poolDir = File(dataDir, md5)
        try {
            poolDir.mkdirs()
            File(poolDir, "content.xlsx").writeBytes(xlsxBytes)
            File(poolDir, "spells.json").writeText(spellsJson, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            poolDir.deleteRecursively()
            throw HandlerException("卡池保存失败")
        }

        val now = System.currentTimeMillis()
        val metadata = CustomPoolMetadata(
            md5 = md5,
            uploaderName = uploaderName,
            fileName = fileName,
            note = note,
            rowCount = spells.size,
            gameCount = games.size,
            games = games,
            createdAt = now,
            expiresAt = now + TTL_MS,
        )

        writeIndex(index.copy(pools = index.pools + metadata))
        logger.info("Custom pool saved: md5=$md5, file=$fileName, rows=${spells.size}")
        return metadata
    }

    fun getPoolMetadata(md5: String): CustomPoolMetadata? {
        val index = readIndex()
        val pool = index.pools.find { it.md5 == md5 } ?: return null
        if (pool.expiresAt <= System.currentTimeMillis()) return null
        return pool
    }

    fun getPoolSpells(md5: String): Array<Spell>? {
        val spellsFile = File(File(dataDir, md5), "spells.json")
        if (!spellsFile.exists()) return null
        return try {
            Dispatcher.json.decodeFromString(spellsFile.readText(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            logger.error("Failed to read spells for pool $md5", e)
            null
        }
    }

    fun getPoolSpellsJson(md5: String): String? {
        val spellsFile = File(File(dataDir, md5), "spells.json")
        if (!spellsFile.exists()) return null
        return spellsFile.readText(StandardCharsets.UTF_8)
    }

    fun listPools(): List<CustomPoolMetadata> {
        val now = System.currentTimeMillis()
        return readIndex().pools
            .filter { it.expiresAt > now }
            .sortedByDescending { it.createdAt }
    }

    fun deletePool(md5: String, playerName: String) {
        val index = readIndex()
        val pool = index.pools.find { it.md5 == md5 }
            ?: throw HandlerException("卡池不存在")
        if (pool.uploaderName != playerName) {
            throw HandlerException("只能删除自己上传的卡池")
        }
        val poolDir = File(dataDir, md5)
        if (poolDir.exists()) {
            poolDir.deleteRecursively()
        }
        writeIndex(index.copy(pools = index.pools.filter { it.md5 != md5 }))
        logger.info("Custom pool deleted: md5=$md5")
    }

    fun cleanupExpired() {
        try {
            val now = System.currentTimeMillis()
            val index = readIndex()
            val (expired, valid) = index.pools.partition { it.expiresAt <= now }
            if (expired.isEmpty()) return
            for (pool in expired) {
                val poolDir = File(dataDir, pool.md5)
                if (poolDir.exists()) {
                    poolDir.deleteRecursively()
                }
            }
            writeIndex(CustomPoolIndex(valid))
            logger.info("Cleaned up ${expired.size} expired custom pools")
        } catch (e: Exception) {
            logger.error("Failed to cleanup expired custom pools", e)
        }
    }

    private fun readIndex(): CustomPoolIndex {
        ensureStorage()
        return try {
            json.decodeFromString(indexFile.readText(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            logger.error("Failed to read custom pool index, resetting", e)
            val empty = CustomPoolIndex()
            writeIndex(empty)
            empty
        }
    }

    @Synchronized
    private fun writeIndex(index: CustomPoolIndex) {
        indexFile.writeText(json.encodeToString(index), StandardCharsets.UTF_8)
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    private val logger = logger()
}
