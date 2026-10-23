package org.tfcc.bingo.admin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.tfcc.bingo.message.GameLog

@Serializable
data class AdminAccountConfig(
    val username: String,
    val password: String,
    @SerialName("updated_at")
    val updatedAt: Long,
)

@Serializable
data class AdminLoginRequest(
    val username: String = "",
    val password: String = "",
)

@Serializable
data class AdminLoginResponse(
    val token: String,
    val username: String,
    @SerialName("expires_at")
    val expiresAt: Long,
)

@Serializable
data class AdminGameRecordSummary(
    val id: String,
    @SerialName("storage_month")
    val storageMonth: String? = null,
    @SerialName("room_id")
    val roomId: String,
    @SerialName("saved_at")
    val savedAt: Long,
    @SerialName("save_reason")
    val saveReason: String,
    @SerialName("round_winner")
    val roundWinner: Int?,
    @SerialName("round_winner_name")
    val roundWinnerName: String?,
    val players: List<String>,
    @SerialName("game_type")
    val gameType: Int,
    @SerialName("game_type_name")
    val gameTypeName: String,
    val score: List<Int>,
    @SerialName("action_count")
    val actionCount: Int,
    @SerialName("duration_ms")
    val durationMs: Long,
    @SerialName("started_at")
    val startedAt: Long?,
    @SerialName("is_custom_game")
    val isCustomGame: Boolean,
    @SerialName("has_game_log")
    val hasGameLog: Boolean,
    @SerialName("board_size")
    val boardSize: Int = 5,
)

@Serializable
data class AdminGameRecord(
    val id: String,
    @SerialName("storage_month")
    val storageMonth: String? = null,
    @SerialName("room_id")
    val roomId: String,
    @SerialName("saved_at")
    val savedAt: Long,
    @SerialName("save_reason")
    val saveReason: String,
    @SerialName("round_winner")
    val roundWinner: Int?,
    @SerialName("round_winner_name")
    val roundWinnerName: String?,
    val players: List<String>,
    @SerialName("game_type")
    val gameType: Int,
    @SerialName("game_type_name")
    val gameTypeName: String,
    val score: List<Int>,
    @SerialName("action_count")
    val actionCount: Int,
    @SerialName("duration_ms")
    val durationMs: Long,
    @SerialName("started_at")
    val startedAt: Long?,
    @SerialName("is_custom_game")
    val isCustomGame: Boolean,
    @SerialName("has_game_log")
    val hasGameLog: Boolean,
    @SerialName("game_log")
    val gameLog: GameLog?,
    @SerialName("board_size")
    val boardSize: Int = 5,
)

@Serializable
data class AdminMatchListResponse(
    val total: Int,
    val items: List<AdminGameRecordSummary>,
)

@Serializable
data class AdminMatchBatchRequest(
    val ids: List<String> = emptyList(),
)

@Serializable
data class AdminMatchBatchResponse(
    val total: Int,
    val items: List<AdminGameRecord>,
)

@Serializable
data class AdminNamedCount(
    val label: String,
    val count: Int,
)

@Serializable
data class AdminDailyActivity(
    val date: String,
    @SerialName("record_count")
    val recordCount: Int,
    @SerialName("unique_players")
    val uniquePlayers: Int,
)

@Serializable
data class AdminUserActivitySummary(
    @SerialName("player_name")
    val playerName: String,
    @SerialName("match_count")
    val matchCount: Int,
    @SerialName("replayable_count")
    val replayableCount: Int,
    @SerialName("finished_match_count")
    val finishedMatchCount: Int,
    @SerialName("win_count")
    val winCount: Int,
    @SerialName("win_rate")
    val winRate: Double,
    @SerialName("active_days")
    val activeDays: Int,
    @SerialName("latest_active_at")
    val latestActiveAt: Long,
    @SerialName("average_duration_ms")
    val averageDurationMs: Long,
)

@Serializable
data class AdminUserOverviewResponse(
    @SerialName("total_records")
    val totalRecords: Int,
    @SerialName("replayable_records")
    val replayableRecords: Int,
    @SerialName("finished_matches")
    val finishedMatches: Int,
    @SerialName("unique_players")
    val uniquePlayers: Int,
    @SerialName("active_days")
    val activeDays: Int,
    @SerialName("average_duration_ms")
    val averageDurationMs: Long,
    @SerialName("average_records_per_day")
    val averageRecordsPerDay: Double,
    @SerialName("average_matches_per_user")
    val averageMatchesPerUser: Double,
    @SerialName("top_active_users")
    val topActiveUsers: List<AdminUserActivitySummary>,
    @SerialName("daily_activity")
    val dailyActivity: List<AdminDailyActivity>,
    @SerialName("month_distribution")
    val monthDistribution: List<AdminNamedCount>,
    @SerialName("save_reason_distribution")
    val saveReasonDistribution: List<AdminNamedCount>,
    @SerialName("game_type_distribution")
    val gameTypeDistribution: List<AdminNamedCount>,
)
