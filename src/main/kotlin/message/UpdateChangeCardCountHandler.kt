package org.tfcc.bingo.message

import io.netty.channel.ChannelHandlerContext
import kotlinx.serialization.json.*
import org.tfcc.bingo.Player
import org.tfcc.bingo.RequestHandler
import org.tfcc.bingo.push
import org.tfcc.bingo.team.Team

object UpdateChangeCardCountHandler : RequestHandler {
    @Throws(HandlerException::class)
    override fun handle(ctx: ChannelHandlerContext, player: Player, data: JsonElement?): JsonElement? {
        val m = data!!.jsonObject
        val team = Team.of(m["team"]?.jsonPrimitive?.intOrNull) ?: throw HandlerException("队伍参数错误")
        val count = m["count"]!!.jsonPrimitive.int
        count in 0..9999 || throw HandlerException("cnt参数错误")
        val room = player.room ?: throw HandlerException("不在房间里")
        room.canManage(player) || throw HandlerException("没有权限")
        room.changeCardCount[team.index] = count
        room.push(
            "push_update_change_card_count", JsonObject(
                mapOf(
                    "team" to JsonPrimitive(team.index),
                    "count" to JsonPrimitive(count),
                    "reason" to JsonPrimitive("manual"),
                )
            )
        )
        return null
    }
}
