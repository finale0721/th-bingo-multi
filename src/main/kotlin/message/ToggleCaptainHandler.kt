package org.tfcc.bingo.message

import io.netty.channel.ChannelHandlerContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.tfcc.bingo.Player
import org.tfcc.bingo.RequestHandler
import org.tfcc.bingo.pushRosterUpdate

/**
 * toggle_captain {name?: string}
 *
 * 本人：自己是队长则放弃；本队无队长则接任；本队已有其他队长则拒绝。
 * 导播：可强制指定某成员成为队长（原队长自动卸任），或清空某队队长。
 */
object ToggleCaptainHandler : RequestHandler {
    override fun handle(ctx: ChannelHandlerContext, player: Player, data: JsonElement?): JsonElement? {
        val room = player.room ?: throw HandlerException("不在房间里")
        !room.isRosterLocked() || throw HandlerException("BP期间不能变更队长")
        val obj = data as? JsonObject
        val requestedName = obj?.get("name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

        if (room.host != null && room.host === player) {
            val name = requestedName ?: throw HandlerException("缺少目标玩家")
            val target = room.members.firstOrNull { it.name == name } ?: throw HandlerException("找不到目标成员")
            val alreadyCaptain = target.captain
            // 清空该队所有队长
            room.membersOf(target.team).forEach { it.captain = false }
            if (!alreadyCaptain) target.captain = true
        } else {
            val me = room.memberOf(player) ?: throw HandlerException("没有权限")
            if (me.captain) {
                me.captain = false
            } else {
                if (room.captainOf(me.team) != null) throw HandlerException("本队已有队长")
                me.captain = true
            }
        }
        room.pushRosterUpdate()
        return null
    }
}
