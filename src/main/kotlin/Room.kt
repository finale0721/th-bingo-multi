package org.tfcc.bingo

import org.tfcc.bingo.message.*
import org.tfcc.bingo.standard.StandardState
import org.tfcc.bingo.team.RoomMember
import org.tfcc.bingo.team.Team
import java.util.*

class Room(
    val roomId: String,
    val host: Player?,
    var roomConfig: RoomConfig,
) {
    companion object {
        const val MAX_TEAM_SIZE = 4
    }

    /** 每队成员。玩家/观众的区别由“是否在此列表中”表示。 */
    val members = mutableListOf<RoomMember>()

    var started = false
    var spells: Array<Spell>? = null
    var spells2: Array<Spell>? = null
    var startMs: Long = 0

    /** 标准赛唯一权威状态；未开局或休眠模式为 null */
    var standardState: StandardState? = null

    /** 系列赛比分（A/B 队） */
    val score = intArrayOf(0, 0)

    /** 系列赛进行中：房间不可被普通退出销毁（不再阻止换人） */
    var locked = false

    /** A/B 队共享的换卡次数 */
    val changeCardCount = intArrayOf(0, 0)

    /** 开局时下发的 A/B 队基础换卡次数（由 start_game payload 携带） */
    val changeCardBase = intArrayOf(2, 2)

    /** 累计暂停时长，毫秒 */
    var totalPauseMs: Long = 0

    /** 开始暂停时刻，毫秒，0表示没暂停 */
    var pauseBeginMs: Long = 0

    /** 上一次结束暂停的时刻，毫秒，0表示从未暂停过 */
    var pauseEndMs: Long = 0

    /** 上一场获胜队伍（0/1），-1 表示没有上一场 */
    var lastWinner: Int = -1

    var customCardPool: Array<Spell> = emptyArray()

    /** 纯客户端用，服务器只记录 */
    var phase: Int = 0

    /** 观众 */
    val watchers = ArrayList<Player>()

    var banPick: BanPick? = null
    var debugSpells: IntArray? = null

    /** 操作记录器 */
    var gameLogger: GameLogger? = null

    /** 最后一次操作的时间戳，毫秒，业务逻辑中请勿修改此字段 */
    var lastOperateMs: Long = 0

    val boardSpec: BoardSpec
        get() = BoardSpec(roomConfig.boardSize)

    val boardArea: Int
        get() = boardSpec.area

    /** 新应用只支持标准赛；BP/Link 入口在协议层拒绝 */
    val type
        get() = when (roomConfig.type) {
            1 -> RoomTypeNormal
            else -> throw HandlerException("暂不支持的游戏类型")
        }

    // ------------------------------------------------------------------
    // 休眠模式（BP/Link）兼容字段：标准赛永远为 null，不读写
    // ------------------------------------------------------------------
    @Deprecated("仅休眠模式使用")
    var bpData: BpData? = null

    @Deprecated("仅休眠模式使用")
    var linkData: LinkData? = null

    @Deprecated("仅休眠模式使用")
    var normalData: NormalData? = null

    @Deprecated("仅休眠模式使用")
    var spellStatus: Array<SpellStatus>? = null

    @Deprecated("仅休眠模式使用")
    var spellStatusInPlayerClient: Array<IntArray>? = null

    /** 休眠模式兼容视图：A/B 队第一人。标准赛请使用 [members]。 */
    @Deprecated("仅休眠模式使用")
    val players: List<Player?>
        get() = listOf(
            membersOf(Team.A).firstOrNull()?.player,
            membersOf(Team.B).firstOrNull()?.player,
        )

    // ------------------------------------------------------------------
    // 组队
    // ------------------------------------------------------------------

    fun membersOf(team: Team): List<RoomMember> = members.filter { it.team == team }

    fun memberOf(player: Player): RoomMember? = members.firstOrNull { it.player === player }

    fun teamOf(player: Player): Team? = memberOf(player)?.team

    fun teamSize(team: Team): Int = membersOf(team).size

    fun captainOf(team: Team): RoomMember? = membersOf(team).firstOrNull { it.captain }

    /** 每队必须非空且恰有一名队长；练习房只要求 A 队 */
    fun bothTeamsReady(): Boolean {
        if (roomConfig.practice) {
            val a = membersOf(Team.A)
            return a.isNotEmpty() && a.count { it.captain } == 1
        }
        return Team.entries.all { team ->
            val teamMembers = membersOf(team)
            teamMembers.isNotEmpty() && teamMembers.count { it.captain } == 1
        }
    }

    /** 阵容锁：开局后或赛前作品BP进行中 */
    fun isRosterLocked(): Boolean = started || (banPick?.let { it.phase != 9999 } == true)

    /** 导播房由导播管理；无导播房由 A 队队长管理 */
    fun canManage(player: Player): Boolean {
        if (host != null) return host === player
        val member = memberOf(player) ?: return false
        return member.team == Team.A && member.captain
    }

    fun isHost(player: Player): Boolean = if (host != null) host === player else memberOf(player) != null

    /** 收卡数（单盘=完整收取格数；双盘=完整收取格数） */
    fun teamScore(team: Team): Int {
        val state = standardState ?: return 0
        return (0 until state.board.area).count { state.fullyCapturedBy(it) == team.index }
    }

    /** 人数较少的队伍；相同则 A 队。练习房只允许 A 队。 */
    fun nextJoinTeam(): Team? {
        if (roomConfig.practice) {
            return if (teamSize(Team.A) < MAX_TEAM_SIZE) Team.A else null
        }
        val a = teamSize(Team.A)
        val b = teamSize(Team.B)
        val team = if (a <= b) Team.A else Team.B
        return if (teamSize(team) < MAX_TEAM_SIZE) team else null
    }

    var refreshManager1: RefreshSpellManager? = null
    var refreshManager2: RefreshSpellManager? = null

    var isCustomGame = false
}
