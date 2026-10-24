package org.tfcc.bingo.message

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.tfcc.bingo.Spell

private fun legacyRoomConfigDefault() = RoomConfig(
    rid = "legacy",
    type = 1,
    gameTime = 30,
    countdown = 60,
    games = emptyArray(),
    ranks = emptyArray(),
    needWin = 1,
    difficulty = 3,
    cdTime = 0,
    cdModifierA = 0,
    cdModifierB = 0,
    reservedType = null,
    blindSetting = 1,
    spellCardVersion = 2,
    dualBoard = 0,
    blindRevealLevel = 2,
    diffLevel = 3,
    gameWeight = hashMapOf(),
    customLevelCount = arrayOf(2, 6, 12, 4, 1, 1, 0, 4, 1, 1, 5),
    boardSize = 5,
    extraLineCount = 0,
)

@Serializable
data class PlayerAction(
    val playerName: String = "",
    val actionType: String = "",
    val spellIndex: Int = -1,
    val spellName: String = "",
    val timestamp: Long = 0L,
    val scoreNow: List<Int> = listOf(0, 0),
    val linkData: LinkData? = null,
    /** 操作者所在队伍（0/1）；导播操作时为被操作方队伍 */
    val team: Int? = null,
    /** select|cancel|capture_partial|capture_complete|grab_victim|grab_public|director_*|change|refund */
    val op: String? = null,
    /** 操作盘面；单盘恒为 0 */
    val board: Int? = null,
    /** 导播代选/代取消时受影响玩家 */
    val name: String? = null,
    /** 抢卡受害者 */
    val victims: List<String> = emptyList(),
    /** 操作后两队共享换卡次数 */
    @SerialName("change_card_count")
    val changeCardCount: List<Int>? = null,
    /** 操作后该逻辑格快照（回放直接应用，不做状态推断） */
    @SerialName("cell_after")
    val cellAfter: CellSnapshot? = null,
    /** 双盘：另一面同 index 的快照（完整收取会同时清除两面的选择） */
    @SerialName("cell_after_other")
    val cellAfterOther: CellSnapshot? = null,
    @SerialName("capture_count_a")
    val captureCountA: Int? = null,
    @SerialName("capture_count_b")
    val captureCountB: Int? = null,
    @SerialName("captured_by_a")
    val capturedByA: Boolean? = null,
    @SerialName("captured_by_b")
    val capturedByB: Boolean? = null,
    @SerialName("fully_captured_by")
    val fullyCapturedBy: Int? = null,
)

@Serializable
data class GameTeamMember(
    val name: String,
    val captain: Boolean,
    /** 该玩家本局实际 CD（含队伍修正），毫秒 */
    @SerialName("actual_cd_time_ms")
    val actualCdTimeMs: Long,
)

@Serializable
data class GameTeam(
    val key: String,
    val members: List<GameTeamMember>,
    val captain: String?,
)

@Serializable
data class CellSnapshot(
    @SerialName("selected_by_a")
    val selectedByA: List<String> = emptyList(),
    @SerialName("selected_by_b")
    val selectedByB: List<String> = emptyList(),
    @SerialName("capture_count_a")
    val captureCountA: Int = 0,
    @SerialName("capture_count_b")
    val captureCountB: Int = 0,
    @SerialName("captured_by_a")
    val capturedByA: Boolean = false,
    @SerialName("captured_by_b")
    val capturedByB: Boolean = false,
    @SerialName("fully_captured_by")
    val fullyCapturedBy: Int? = null,
)

@Serializable
data class StandardData(
    @SerialName("capture_standard")
    val captureStandard: Int,
    @SerialName("dual_board")
    val dualBoard: Boolean,
    @SerialName("copy_board")
    val copyBoard: Boolean,
    @SerialName("board_size")
    val boardSize: Int,
    @SerialName("extra_lines")
    val extraLines: List<List<Int>>,
    val teams: List<GameTeam>,
    /** 第一面（单盘即唯一盘面） */
    val cells: List<CellSnapshot>,
    /** 第二面（仅双盘） */
    @SerialName("cells2")
    val cells2: List<CellSnapshot>? = null,
)

@Serializable
data class GameLog(
    val roomConfig: RoomConfig = legacyRoomConfigDefault(),
    val players: List<String> = emptyList(),
    val spells: List<Spell> = emptyList(),
    val spells2: List<Spell>? = null,
    val normalData: NormalData? = null,
    val actions: List<PlayerAction> = emptyList(),
    val gameStartTimestamp: Long = 0L,
    val score: List<Int> = listOf(0, 0),
    val initStatus: List<Int> = emptyList(),
    val isCustomGame: Boolean = false,
    val linkData: LinkData? = null,
    /** 标准赛权威初始状态；回放使用 cells/cells2，不再使用 initStatus */
    @SerialName("standard_data")
    val standardData: StandardData? = null,
)
