package org.tfcc.bingo.message

import io.netty.channel.ChannelHandlerContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.tfcc.bingo.Player
import org.tfcc.bingo.RequestHandler
import org.tfcc.bingo.Room
import org.tfcc.bingo.push
import org.tfcc.bingo.pushRosterUpdate
import org.tfcc.bingo.team.RoomMember
import org.tfcc.bingo.team.Team

/**
 * 成为玩家：sit_down {name?: string, team?: 0|1}
 *
 * 无参数：观众本人入座，自动分配到人数较少的一队。
 * 带 name：导播（导播房）/ A 队队长（无导播房）把指定观众放到指定队伍。
 */
object SitDownHandler : RequestHandler {
    override fun handle(ctx: ChannelHandlerContext, player: Player, data: JsonElement?): JsonElement? {
        val room = player.room ?: throw HandlerException("找不到房间")
        !room.isRosterLocked() || throw HandlerException("当前阵容已锁定")
        val obj = data as? JsonObject
        val requestedName = obj?.get("name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val requestedTeam = Team.of(obj?.get("team")?.jsonPrimitive?.intOrNull)

        val manager = requestedName != null && requestedName != player.name
        val target: Player
        val team: Team
        if (manager) {
            room.canManage(player) || throw HandlerException("没有权限")
            target = room.watchers.firstOrNull { it.name == requestedName }
                ?: throw HandlerException("找不到该观众")
            team = requestedTeam ?: (room.nextJoinTeam() ?: throw HandlerException("房间已满"))
            if (room.roomConfig.practice && team != Team.A) throw HandlerException("练习房只能加入 A 队")
            room.teamSize(team) < Room.MAX_TEAM_SIZE || throw HandlerException("目标队伍已满")
        } else {
            room.watchers.contains(player) || throw HandlerException("你不是观众")
            target = player
            team = room.nextJoinTeam() ?: throw HandlerException("房间已满")
        }

        room.watchers.remove(target)
        val member = RoomMember(target, team, captain = room.teamSize(team) == 0)
        room.members.add(member)
        room.push(
            "push_sit_down", JsonObject(
                mapOf(
                    "name" to JsonPrimitive(target.name),
                    "position" to JsonPrimitive(team.index),
                )
            )
        )
        room.pushRosterUpdate()
        return null
    }
}
