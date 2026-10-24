package org.tfcc.bingo.message

import io.netty.channel.ChannelHandlerContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tfcc.bingo.Player
import org.tfcc.bingo.RequestHandler
import org.tfcc.bingo.Room
import org.tfcc.bingo.pushRosterUpdate
import org.tfcc.bingo.team.Team

/**
 * 切换队伍：switch_team {to: 0|1, name?: string}
 *
 * 导播可移动任意成员；无导播房只能移动自己。
 */
object SwitchTeamHandler : RequestHandler {
    override fun handle(ctx: ChannelHandlerContext, player: Player, data: JsonElement?): JsonElement? {
        val room = player.room ?: throw HandlerException("不在房间里")
        !room.isRosterLocked() || throw HandlerException("当前阵容已锁定")
        val obj = data!!.jsonObject
        val to = Team.of(obj["to"]?.jsonPrimitive?.intOrNull) ?: throw HandlerException("目标队伍不合法")
        val requestedName = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

        val target = if (room.host != null) {
            if (room.host === player) {
                val name = requestedName ?: throw HandlerException("缺少目标玩家")
                room.members.firstOrNull { it.name == name } ?: throw HandlerException("找不到目标成员")
            } else {
                room.memberOf(player) ?: throw HandlerException("没有权限")
            }
        } else {
            // 无导播房忽略 name，只允许移动自己
            room.memberOf(player) ?: throw HandlerException("没有权限")
        }

        if (room.roomConfig.practice) {
            to == Team.A || throw HandlerException("练习房只能加入 A 队")
        }
        if (target.team == to) return null
        room.teamSize(to) < Room.MAX_TEAM_SIZE || throw HandlerException("目标队伍已满")

        val oldTeam = target.team
        target.team = to
        if (target.captain) {
            target.captain = false
            // 原队队长清空
            room.membersOf(oldTeam).forEach { it.captain = false }
        }
        // 目标队为空时，切队者自动成为队长
        if (room.membersOf(to).size == 1) {
            target.captain = true
        }
        room.pushRosterUpdate()
        return null
    }
}
