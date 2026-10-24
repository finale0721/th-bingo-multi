package org.tfcc.bingo.standard

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.tfcc.bingo.BoardSpec

/**
 * 一个逻辑格的基础可见性（仅在标准赛使用）。
 *
 * 模式1（blindSetting=2）：Public / TeamOnly / SelfOnly。
 * 模式2（blindSetting=3）：Mode2Hidden / Mode2Game / Mode2GameStage / Mode2Star / Mode2Full。
 * 普通模式（blindSetting=1）：全部为 Public。
 */
sealed interface CellVisibility {
    data object Public : CellVisibility

    /** 模式1 中不属于任何已揭示类型的格子：对所有玩家/观众完全隐藏 */
    data object Hidden : CellVisibility
    data class TeamOnly(val team: Int) : CellVisibility
    data class SelfOnly(val name: String) : CellVisibility
    data object Mode2Hidden : CellVisibility
    data object Mode2Game : CellVisibility
    data object Mode2GameStage : CellVisibility
    data object Mode2Star : CellVisibility
    data object Mode2Full : CellVisibility
}

/**
 * 单个盘面上的格子状态。
 *
 * 单盘：使用 [captureCountA]/[captureCountB] 记录两队中间收取次数，完整收取写入 [capturedBy]。
 * 双盘：每面固定每队 1 次，使用 [capturedByA]/[capturedByB] 记录该队在本面是否已收；
 *       逻辑格的完整收取由 [StandardState.fullyCapturedBy] 计算。
 */
class CellState {
    /** 正在选择该格的成员名（按选择顺序） */
    val selectedBy: LinkedHashSet<String> = linkedSetOf()

    var captureCountA: Int = 0
    var captureCountB: Int = 0

    /** 单盘：完整收取该格的队伍下标；null 表示未完整收取 */
    var capturedBy: Int? = null

    /** 双盘：A 队是否已收本面 */
    var capturedByA: Boolean = false

    /** 双盘：B 队是否已收本面 */
    var capturedByB: Boolean = false

    fun captureCountOf(team: Int): Int = if (team == 0) captureCountA else captureCountB

    fun setCaptureCount(team: Int, count: Int) {
        if (team == 0) captureCountA = count else captureCountB = count
    }

    fun capturedByTeamOnBoard(team: Int): Boolean = if (team == 0) capturedByA else capturedByB

    fun setCapturedOnBoard(team: Int, captured: Boolean) {
        if (team == 0) capturedByA = captured else capturedByB = captured
    }
}

/**
 * 标准赛唯一权威状态。旧 [org.tfcc.bingo.SpellStatus] 在标准赛完全不再读写。
 */
class StandardState(
    val board: BoardSpec,
    val captureStandard: Int,
    val dualBoard: Boolean,
) {
    val cells: Array<CellState> = Array(board.area) { CellState() }
    val cells2: Array<CellState>? = if (dualBoard) Array(board.area) { CellState() } else null

    /** 盲盒可见性按逻辑格共享（双盘两面同 index 同一可见性） */
    var visibility: Array<CellVisibility> = Array(board.area) { CellVisibility.Public }

    /**
     * 选择历史揭示：该逻辑格是否已对 A/B 队公开。
     *
     * 选择发生时按当时的「对手选择隐藏」规则写入；之后即使选择被取消/收取清除也保持公开。
     * 导播/观众只要任一标记为 true 即可见（与选择可见性规则一致）。
     */
    val revealedToA: BooleanArray = BooleanArray(board.area)
    val revealedToB: BooleanArray = BooleanArray(board.area)

    /** 6x6 额外连线 */
    var extraLines: List<List<Int>> = emptyList()

    fun boardCells(boardIdx: Int): Array<CellState> = if (boardIdx == 1 && cells2 != null) cells2 else cells

    fun cell(boardIdx: Int, index: Int): CellState = boardCells(boardIdx)[index]

    /** 逻辑格是否被某队完整收取；null 表示没有 */
    fun fullyCapturedBy(index: Int): Int? {
        if (!dualBoard) return cells[index].capturedBy
        val b0 = cells[index]
        val b1 = cells2!![index]
        return when {
            b0.capturedByA && b1.capturedByA -> 0
            b0.capturedByB && b1.capturedByB -> 1
            else -> null
        }
    }

    /** 逻辑格是否已被任意队伍（任一盘面）收取，用于“收取后内容公开” */
    fun anyCaptured(index: Int): Boolean {
        if (!dualBoard) return cells[index].capturedBy != null
        val b0 = cells[index]
        val b1 = cells2!![index]
        return b0.capturedByA || b0.capturedByB || b1.capturedByA || b1.capturedByB
    }
}

/** 下发给客户端的格子视图（已按查看者裁剪） */
@Serializable
class CellView(
    val index: Int,
    @SerialName("selected_by_a")
    val selectedByA: List<String>,
    @SerialName("selected_by_b")
    val selectedByB: List<String>,
    @SerialName("capture_count_a")
    val captureCountA: Int?,
    @SerialName("capture_count_b")
    val captureCountB: Int?,
    @SerialName("captured_by_a")
    val capturedByA: Boolean,
    @SerialName("captured_by_b")
    val capturedByB: Boolean,
    @SerialName("fully_captured_by")
    val fullyCapturedBy: Int?,
    @SerialName("my_selected")
    val mySelected: Boolean,
    /** 0-完全隐藏，1-只显示作品，2-作品+关卡，3-只显示星级，4-完整显示 */
    @SerialName("content_reveal")
    val contentReveal: Int,
)
