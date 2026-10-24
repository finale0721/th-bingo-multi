package org.tfcc.bingo.message

import io.netty.channel.ChannelHandlerContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.tfcc.bingo.Player
import org.tfcc.bingo.RequestHandler
import org.tfcc.bingo.push
import org.tfcc.bingo.pushRosterUpdate

/**
 * 成为观众：stand_up {name?: string}
 *
 * 无参数：选手本人离座。
 * 带 name：导播（导播房）/ A 队队长（无导播房）把指定选手转成观众。
 */
object StandUpHandler : RequestHandler {
    override fun handle(ctx: ChannelHandlerContext, player: Player, data: JsonElement?): JsonElement? {
        val room = player.room ?: throw HandlerException("找不到房间")
        !room.isRosterLocked() || throw HandlerException("当前阵容已锁定")
        val obj = data as? JsonObject
        val requestedName = obj?.get("name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

        val member = if (requestedName == null || requestedName == player.name) {
            room.memberOf(player) ?: throw HandlerException("你不是选手")
        } else {
            room.canManage(player) || throw HandlerException("没有权限")
            room.members.firstOrNull { it.name == requestedName } ?: throw HandlerException("找不到该玩家")
        }

        room.members.remove(member)
        room.watchers.add(member.player)
        if (member.captain) {
            // 队长离队后本队队长清空（由新成员自行接任）
            room.membersOf(member.team).forEach { it.captain = false }
        }
        room.push("push_stand_up", JsonObject(mapOf("name" to JsonPrimitive(member.name))))
        room.pushRosterUpdate()
        return null
    }
}
