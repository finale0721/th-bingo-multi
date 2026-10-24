package org.tfcc.bingo.message

import io.netty.channel.ChannelHandlerContext
import kotlinx.serialization.json.*
import org.tfcc.bingo.*

object GmWarnPlayerHandler : RequestHandler {
    @Throws(HandlerException::class)
    override fun handle(ctx: ChannelHandlerContext, player: Player, data: JsonElement?): JsonElement? {
        val room = player.room ?: throw HandlerException("不在房间里")
        room.host === player || throw HandlerException("没有权限")
        val name = data!!.jsonObject["name"]!!.jsonPrimitive.content
        val target = room.members.firstOrNull { it.name == name } ?: throw HandlerException("找不到目标玩家")
        target.player.push("push_gm_warn_player", null, true)
        return null
    }
}
