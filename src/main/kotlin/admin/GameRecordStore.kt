package org.tfcc.bingo.admin

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.kotlin.logger
import org.tfcc.bingo.Room
import org.tfcc.bingo.SpellStatus
import org.tfcc.bingo.Store
import org.tfcc.bingo.message.GameLog
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

object GameRecordStore {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val dataDir = File("data/game-records")
    private val monthsDir = File(dataDir, "months")
    private val legacyRecordsDir = File(dataDir, "records")
    private val legacyIndexFile = File(dataDir, "index.json")
    private val zoneId = ZoneId.systemDefault()
    private val monthFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
    private val monthPattern = Regex("""\d{4}-\d{2}""")

    init {
        ensureStorage()
    }

    fun ensureStorage() {
        if (!dataDir.exists()) {
            dataDir.mkdirs()
        }
        if (!monthsDir.exists()) {
            monthsDir.mkdirs()
        }
    }

    internal fun hasScorableProgress(room: Room): Boolean {
        room.linkData?.let { linkData ->
            if (linkData.currentStepA > 0 || linkData.currentStepB > 0) {
                return true
            }
            if (linkData.scoreA != 0.0 || linkData.scoreB != 0.0) {
                return true
            }
            if (linkData.linkIdxA.size > 1 || linkData.linkIdxB.size > 1) {
                return true
            }
        }

        room.spellStatus?.let { spellStatus ->
            return spellStatus.any { it == SpellStatus.LEFT_GET || it == SpellStatus.RIGHT_GET }
        }

        val serializedLog = room.gameLogger?.getSerializedLog()
        return serializedLog?.score?.any { it > 0 } == true
    }

    fun saveFinishedGame(room: Room, winner: Int) {
        if (!hasScorableProgress(room)) {
            return
        }
        val gameLog = room.gameLogger?.getSerializedLog()
        val record = buildRecord(
            room = room,
            saveReason = "game_finished",
            roundWinner = winner.takeIf { it >= 0 },
            gameLog = gameLog,
        )
        saveRecord(record)
    }

    fun saveCleanupSnapshotIfNeeded(room: Room) {
        if (!room.locked) {
            return
        }
        if (!hasScorableProgress(room)) {
            return
        }
        val gameLog = if (room.started) room.gameLogger?.getSerializedLog() else null
        val record = buildRecord(
            room = room,
            saveReason = "room_cleanup",
            roundWinner = null,
            gameLog = gameLog,
        )
        saveRecord(record)
    }

    @Synchronized
    fun listRecords(keyword: String?, from: Long?, to: Long?, saveReason: String?, limit: Int): AdminMatchListResponse {
        val filtered = filterSummaries(keyword, from, to, saveReason)
        return AdminMatchListResponse(
            total = filtered.size,
            items = filtered.take(limit.coerceIn(1, 1000)),
        )
    }

    @Synchronized
    fun getRecord(id: String): AdminGameRecord? {
        return getRecordInternal(id)
    }

    @Synchronized
    fun getRecords(ids: List<String>): List<AdminGameRecord> {
        val normalizedIds = ids.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(500)
        if (normalizedIds.isEmpty()) {
            return emptyList()
        }
        val summaryById = readAllSummaries().associateBy { it.id }
        return normalizedIds.mapNotNull { id ->
            getRecordInternal(id, summaryById[id]?.storageMonth)
        }
    }

    @Synchronized
    fun buildUserOverview(keyword: String?, from: Long?, to: Long?, saveReason: String?): AdminUserOverviewResponse {
        val filtered = filterSummaries(keyword, from, to, saveReason)
        val totalRecords = filtered.size
        val replayableRecords = filtered.count { it.hasGameLog }
        val finishedMatches = filtered.count { it.saveReason == "game_finished" }
        val uniquePlayers = linkedSetOf<String>()
        val saveReasonCounter = linkedMapOf<String, Int>()
        val gameTypeCounter = linkedMapOf<String, Int>()
        val monthCounter = linkedMapOf<String, Int>()
        val dailyCounters = linkedMapOf<String, DailyAccumulator>()
        val userCounters = linkedMapOf<String, UserAccumulator>()

        filtered.forEach { item ->
            val normalizedPlayers = item.players
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != Store.ROBOT_NAME }
                .distinct()
            val dayKey = formatDayKey(item.savedAt)
            val monthKey = item.storageMonth ?: resolveMonthKey(item.savedAt)

            uniquePlayers.addAll(normalizedPlayers)
            saveReasonCounter[item.saveReason] = (saveReasonCounter[item.saveReason] ?: 0) + 1
            gameTypeCounter[item.gameTypeName] = (gameTypeCounter[item.gameTypeName] ?: 0) + 1
            monthCounter[monthKey] = (monthCounter[monthKey] ?: 0) + 1

            val dailyAccumulator = dailyCounters.getOrPut(dayKey) { DailyAccumulator() }
            dailyAccumulator.recordCount += 1
            dailyAccumulator.players.addAll(normalizedPlayers)

            normalizedPlayers.forEach { player ->
                val userAccumulator = userCounters.getOrPut(player) { UserAccumulator() }
                userAccumulator.matchCount += 1
                if (item.hasGameLog) {
                    userAccumulator.replayableCount += 1
                }
                if (item.saveReason == "game_finished") {
                    userAccumulator.finishedMatchCount += 1
                }
                if (item.durationMs > 0) {
                    userAccumulator.totalDurationMs += item.durationMs
                    userAccumulator.durationSamples += 1
                }
                userAccumulator.activeDays.add(dayKey)
                userAccumulator.latestActiveAt = maxOf(userAccumulator.latestActiveAt, item.savedAt)
            }

            determineMatchWinnerName(item)?.let { winnerName ->
                val userAccumulator = userCounters.getOrPut(winnerName) { UserAccumulator() }
                userAccumulator.winCount += 1
                userAccumulator.latestActiveAt = maxOf(userAccumulator.latestActiveAt, item.savedAt)
            }
        }

        val averageDurationMs = filtered.map { it.durationMs }.filter { it > 0 }.average().toLong()
        val activeDays = dailyCounters.size
        val averageRecordsPerDay = if (activeDays > 0) totalRecords.toDouble() / activeDays else 0.0
        val averageMatchesPerUser = if (uniquePlayers.isNotEmpty()) totalRecords.toDouble() / uniquePlayers.size else 0.0

        val topActiveUsers = userCounters.entries
            .map { (playerName, value) ->
                AdminUserActivitySummary(
                    playerName = playerName,
                    matchCount = value.matchCount,
                    replayableCount = value.replayableCount,
                    finishedMatchCount = value.finishedMatchCount,
                    winCount = value.winCount,
                    winRate = if (value.finishedMatchCount > 0) {
                        value.winCount.toDouble() / value.finishedMatchCount
                    } else {
                        0.0
                    },
                    activeDays = value.activeDays.size,
                    latestActiveAt = value.latestActiveAt,
                    averageDurationMs = if (value.durationSamples > 0) {
                        value.totalDurationMs / value.durationSamples
                    } else {
                        0L
                    },
                )
            }
            .sortedWith(
                compareByDescending<AdminUserActivitySummary> { it.matchCount }
                    .thenByDescending { it.activeDays }
                    .thenByDescending { it.latestActiveAt },
            )
            .take(12)

        val dailyActivity = dailyCounters.entries
            .map { (date, value) ->
                AdminDailyActivity(
                    date = date,
                    recordCount = value.recordCount,
                    uniquePlayers = value.players.size,
                )
            }
            .sortedBy { it.date }
            .takeLast(30)

        return AdminUserOverviewResponse(
            totalRecords = totalRecords,
            replayableRecords = replayableRecords,
            finishedMatches = finishedMatches,
            uniquePlayers = uniquePlayers.size,
            activeDays = activeDays,
            averageDurationMs = averageDurationMs,
            averageRecordsPerDay = averageRecordsPerDay,
            averageMatchesPerUser = averageMatchesPerUser,
            topActiveUsers = topActiveUsers,
            dailyActivity = dailyActivity,
            monthDistribution = monthCounter.toNamedCounts(sortDescendingByLabel = true),
            saveReasonDistribution = saveReasonCounter.toNamedCounts(),
            gameTypeDistribution = gameTypeCounter.toNamedCounts(),
        )
    }

    @Synchronized
    private fun saveRecord(record: AdminGameRecord) {
        ensureStorage()
        try {
            val storageMonth = record.storageMonth ?: resolveMonthKey(record.savedAt)
            ensureMonthStorage(storageMonth)
            val recordFile = File(monthRecordsDir(storageMonth), "${record.id}.json")
            recordFile.writeText(json.encodeToString(record), StandardCharsets.UTF_8)

            val nextIndex = readMonthIndex(storageMonth)
                .filter { it.id != record.id }
                .toMutableList()
            nextIndex.add(0, record.toSummary())
            monthIndexFile(storageMonth).writeText(json.encodeToString(nextIndex), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            logger.error("Failed to save game record ${record.id}", e)
        }
    }

    private fun buildRecord(room: Room, saveReason: String, roundWinner: Int?, gameLog: GameLog?): AdminGameRecord {
        val savedAt = System.currentTimeMillis()
        val storageMonth = resolveMonthKey(savedAt)
        val players = buildPlayers(room, gameLog)
        val roundWinnerName = roundWinner?.let { players.getOrNull(it) }
            ?.takeIf { it.isNotBlank() && it != Store.ROBOT_NAME }
        val actionCount = gameLog?.actions?.size ?: 0
        val startedAt = gameLog?.gameStartTimestamp?.takeIf { it > 0 } ?: room.startMs.takeIf { it > 0 }
        val durationMs = gameLog?.actions?.lastOrNull()?.timestamp
            ?: startedAt?.let { savedAt - it }
            ?: 0L
        val score = gameLog?.score ?: listOf(0, 0)
        val gameType = gameLog?.roomConfig?.type ?: room.roomConfig.type
        val isCustomGame = gameLog?.isCustomGame ?: room.isCustomGame
        val boardSize = gameLog?.roomConfig?.boardSize ?: room.roomConfig.boardSize

        return AdminGameRecord(
            id = createRecordId(storageMonth),
            storageMonth = storageMonth,
            roomId = room.roomId,
            savedAt = savedAt,
            saveReason = saveReason,
            roundWinner = roundWinner,
            roundWinnerName = roundWinnerName,
            players = players,
            gameType = gameType,
            gameTypeName = getGameTypeName(gameType),
            score = score,
            actionCount = actionCount,
            durationMs = durationMs,
            startedAt = startedAt,
            isCustomGame = isCustomGame,
            hasGameLog = gameLog != null,
            boardSize = boardSize,
            gameLog = gameLog,
        )
    }

    private fun buildPlayers(room: Room, gameLog: GameLog?): List<String> {
        val playersFromLog = gameLog?.players?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        if (playersFromLog.size >= 2) {
            return playersFromLog.take(2)
        }
        return room.players.map { it?.name ?: "" }
    }

    private fun createRecordId(storageMonth: String): String {
        return "${storageMonth}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().replace("-", "").take(12)}"
    }

    private fun getGameTypeName(type: Int): String {
        return when (type) {
            1 -> "标准赛"
            2 -> "BP赛"
            3 -> "Link赛"
            else -> "未知模式"
        }
    }

    private fun resolveMonthKey(timestamp: Long): String {
        return YearMonth.from(Instant.ofEpochMilli(timestamp).atZone(zoneId)).format(monthFormatter)
    }

    private fun formatDayKey(timestamp: Long): String {
        return Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate().toString()
    }

    private fun ensureMonthStorage(storageMonth: String) {
        if (!monthPattern.matches(storageMonth)) {
            throw IllegalArgumentException("Invalid storage month: $storageMonth")
        }
        val monthDir = File(monthsDir, storageMonth)
        if (!monthDir.exists()) {
            monthDir.mkdirs()
        }
        val recordsDir = File(monthDir, "records")
        if (!recordsDir.exists()) {
            recordsDir.mkdirs()
        }
        val indexFile = File(monthDir, "index.json")
        if (!indexFile.exists()) {
            indexFile.writeText("[]", StandardCharsets.UTF_8)
        }
    }

    private fun monthRecordsDir(storageMonth: String): File = File(File(monthsDir, storageMonth), "records")

    private fun monthIndexFile(storageMonth: String): File = File(File(monthsDir, storageMonth), "index.json")

    private fun listMonthKeys(): List<String> {
        return monthsDir.listFiles()
            ?.filter { it.isDirectory && monthPattern.matches(it.name) }
            ?.map { it.name }
            ?.sortedDescending()
            ?: emptyList()
    }

    private fun readMonthIndex(storageMonth: String): List<AdminGameRecordSummary> {
        ensureMonthStorage(storageMonth)
        val indexFile = monthIndexFile(storageMonth)
        return try {
            json.decodeFromString<List<AdminGameRecordSummary>>(indexFile.readText(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            logger.error("Failed to read monthly game record index for $storageMonth, rebuilding index", e)
            rebuildMonthIndex(storageMonth)
        }
    }

    private fun rebuildMonthIndex(storageMonth: String): List<AdminGameRecordSummary> {
        ensureMonthStorage(storageMonth)
        val items = monthRecordsDir(storageMonth).listFiles()
            ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            ?.mapNotNull { file ->
                try {
                    json.decodeFromString<AdminGameRecord>(file.readText(StandardCharsets.UTF_8)).toSummary()
                } catch (e: Exception) {
                    logger.error("Failed to rebuild monthly index from ${file.absolutePath}", e)
                    null
                }
            }
            ?.sortedByDescending { it.savedAt }
            ?: emptyList()
        monthIndexFile(storageMonth).writeText(json.encodeToString(items), StandardCharsets.UTF_8)
        return items
    }

    private fun readLegacyIndex(): List<AdminGameRecordSummary> {
        if (!legacyRecordsDir.exists()) {
            return emptyList()
        }
        if (!legacyIndexFile.exists()) {
            return rebuildLegacyIndex()
        }
        return try {
            json.decodeFromString<List<AdminGameRecordSummary>>(legacyIndexFile.readText(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            logger.error("Failed to read legacy game record index, rebuilding index", e)
            rebuildLegacyIndex()
        }
    }

    private fun rebuildLegacyIndex(): List<AdminGameRecordSummary> {
        if (!legacyRecordsDir.exists()) {
            return emptyList()
        }
        val items = legacyRecordsDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            ?.mapNotNull { file ->
                try {
                    json.decodeFromString<AdminGameRecord>(file.readText(StandardCharsets.UTF_8)).toSummary()
                } catch (e: Exception) {
                    logger.error("Failed to rebuild legacy index from ${file.absolutePath}", e)
                    null
                }
            }
            ?.sortedByDescending { it.savedAt }
            ?: emptyList()
        legacyIndexFile.writeText(json.encodeToString(items), StandardCharsets.UTF_8)
        return items
    }

    private fun readAllSummaries(): List<AdminGameRecordSummary> {
        return (listMonthKeys().flatMap { readMonthIndex(it) } + readLegacyIndex())
            .distinctBy { it.id }
            .sortedByDescending { it.savedAt }
    }

    private fun filterSummaries(keyword: String?, from: Long?, to: Long?, saveReason: String?): List<AdminGameRecordSummary> {
        val normalizedKeyword = keyword?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val monthRange = resolveMonthRange(from, to)
        val monthlyItems = listMonthKeys()
            .asSequence()
            .filter { monthRange == null || monthInRange(it, monthRange.first, monthRange.second) }
            .flatMap { readMonthIndex(it).asSequence() }
            .toList()
        val legacyItems = readLegacyIndex()

        return (monthlyItems + legacyItems)
            .asSequence()
            .filter { item ->
                if (normalizedKeyword != null) {
                    val inRoom = item.roomId.lowercase().contains(normalizedKeyword)
                    val inPlayers = item.players.any { it.lowercase().contains(normalizedKeyword) }
                    if (!inRoom && !inPlayers) {
                        return@filter false
                    }
                }
                if (from != null && item.savedAt < from) {
                    return@filter false
                }
                if (to != null && item.savedAt > to) {
                    return@filter false
                }
                if (!saveReason.isNullOrBlank() && item.saveReason != saveReason) {
                    return@filter false
                }
                true
            }
            .distinctBy { it.id }
            .sortedByDescending { it.savedAt }
            .toList()
    }

    private fun resolveMonthRange(from: Long?, to: Long?): Pair<YearMonth, YearMonth>? {
        if (from == null && to == null) {
            return null
        }
        val start = from?.let { YearMonth.from(Instant.ofEpochMilli(it).atZone(zoneId)) } ?: YearMonth.of(1970, 1)
        val end = to?.let { YearMonth.from(Instant.ofEpochMilli(it).atZone(zoneId)) } ?: YearMonth.of(9999, 12)
        return start to end
    }

    private fun monthInRange(storageMonth: String, start: YearMonth, end: YearMonth): Boolean {
        return try {
            val current = YearMonth.parse(storageMonth, monthFormatter)
            !current.isBefore(start) && !current.isAfter(end)
        } catch (e: Exception) {
            false
        }
    }

    private fun determineMatchWinnerName(item: AdminGameRecordSummary): String? {
        if (item.saveReason != "game_finished") {
            return null
        }
        item.roundWinnerName?.takeIf { it.isNotBlank() && it != Store.ROBOT_NAME }?.let { return it }
        return item.roundWinner
            ?.let { winnerIndex -> item.players.getOrNull(winnerIndex) }
            ?.takeIf { it.isNotBlank() && it != Store.ROBOT_NAME }
    }

    private fun getRecordInternal(id: String, storageMonthHint: String? = null): AdminGameRecord? {
        ensureStorage()
        val normalizedId = id.trim()
        if (normalizedId.isEmpty()) {
            return null
        }

        val storageMonths = linkedSetOf<String>()
        storageMonthHint?.takeIf { monthPattern.matches(it) }?.let(storageMonths::add)
        extractMonthFromId(normalizedId)?.let(storageMonths::add)
        storageMonths.addAll(listMonthKeys())

        storageMonths.forEach { storageMonth ->
            val record = readRecordFile(File(monthRecordsDir(storageMonth), "$normalizedId.json"))
            if (record != null) {
                return record
            }
        }

        return readRecordFile(File(legacyRecordsDir, "$normalizedId.json"))
    }

    private fun extractMonthFromId(id: String): String? {
        val prefix = id.substringBefore("_")
        return prefix.takeIf { monthPattern.matches(it) }
    }

    private fun readRecordFile(recordFile: File): AdminGameRecord? {
        if (!recordFile.exists()) {
            return null
        }
        return try {
            json.decodeFromString<AdminGameRecord>(recordFile.readText(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            logger.error("Failed to read game record ${recordFile.absolutePath}", e)
            null
        }
    }

    private fun AdminGameRecord.toSummary(): AdminGameRecordSummary {
        return AdminGameRecordSummary(
            id = id,
            storageMonth = storageMonth,
            roomId = roomId,
            savedAt = savedAt,
            saveReason = saveReason,
            roundWinner = roundWinner,
            roundWinnerName = roundWinnerName,
            players = players,
            gameType = gameType,
            gameTypeName = gameTypeName,
            score = score,
            actionCount = actionCount,
            durationMs = durationMs,
            startedAt = startedAt,
            isCustomGame = isCustomGame,
            hasGameLog = hasGameLog,
            boardSize = boardSize,
        )
    }

    private fun Map<String, Int>.toNamedCounts(sortDescendingByLabel: Boolean = false): List<AdminNamedCount> {
        val items = entries.map { (label, count) -> AdminNamedCount(label = label, count = count) }
        return if (sortDescendingByLabel) {
            items.sortedByDescending { it.label }
        } else {
            items.sortedByDescending { it.count }
        }
    }

    private class DailyAccumulator {
        var recordCount: Int = 0
        val players: MutableSet<String> = linkedSetOf()
    }

    private class UserAccumulator {
        var matchCount: Int = 0
        var replayableCount: Int = 0
        var finishedMatchCount: Int = 0
        var winCount: Int = 0
        var totalDurationMs: Long = 0
        var durationSamples: Long = 0
        var latestActiveAt: Long = 0
        val activeDays: MutableSet<String> = linkedSetOf()
    }
}
