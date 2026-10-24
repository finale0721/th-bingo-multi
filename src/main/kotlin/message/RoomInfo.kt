package org.tfcc.bingo.message

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.tfcc.bingo.Room
import org.tfcc.bingo.Supervisor
import org.tfcc.bingo.team.Team

@Serializable
class RoomTeamMemberInfo(
    val name: String,
    val captain: Boolean,
    val online: Boolean,
    /** 加入房间的稳定顺序；切队不改变该值，客户端据此保持名单位置不变 */
    val order: Int,
)

@Serializable
class RoomTeamInfo(
    val key: String,
    val members: List<RoomTeamMemberInfo>,
    val score: Int,
    @SerialName("change_card_count")
    val changeCardCount: Int,
)

@Serializable
class RoomInfo(
    val rid: String,
    val type: Int,
    val host: String?,
    /** 过渡字段：A 队第一人 / B 队第一人 */
    val names: List<String?>,
    @SerialName("change_card_count")
    val changeCardCount: IntArray,
    val started: Boolean,
    val score: IntArray,
    val watchers: List<String>,
    @SerialName("last_winner")
    val lastWinner: Int,
    @SerialName("ban_pick")
    val banPick: BanPickInfo? = null,
    @SerialName("players_online")
    val playersOnline: Map<String, Boolean>,
    val teams: List<RoomTeamInfo>,
    val practice: Boolean,
    @SerialName("roster_locked")
    val rosterLocked: Boolean,
)

/** 所有成员到服务器的连接状态 */
fun Room.playersOnline(): Map<String, Boolean> = members
    .map { it.name }
    .associateWith { Supervisor.getChannel(it) != null }

/**
 * @param viewerTeam 查看者所在队伍；非成员传 null
 */
fun Room.roomInfo(viewerTeam: Team?): RoomInfo {
    val orderOf = members.withIndex().associate { it.value to it.index }
    return RoomInfo(
        rid = roomId,
        type = roomConfig.type,
        host = host?.name,
        names = listOf(
            membersOf(Team.A).firstOrNull()?.name,
            membersOf(Team.B).firstOrNull()?.name,
        ),
        changeCardCount = changeCardCount,
        started = started,
        score = score,
        watchers = watchers.map { it.name },
        lastWinner = lastWinner,
        banPick = banPick?.toPb(viewerTeam?.index ?: -1),
        playersOnline = playersOnline(),
        teams = Team.entries.map { team ->
            val teamMembers = membersOf(team)
            RoomTeamInfo(
                key = team.name,
                members = teamMembers.map {
                    RoomTeamMemberInfo(
                        it.name,
                        it.captain,
                        Supervisor.getChannel(it.name) != null,
                        orderOf[it] ?: 0,
                    )
                },
                score = score[team.index],
                changeCardCount = changeCardCount[team.index],
            )
        },
        practice = roomConfig.practice,
        rosterLocked = isRosterLocked(),
    )
}
