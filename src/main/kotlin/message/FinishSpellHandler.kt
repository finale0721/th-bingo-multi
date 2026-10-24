package org.tfcc.bingo.message

import io.netty.channel.ChannelHandlerContext
import kotlinx.serialization.json.*
import org.tfcc.bingo.*
import org.tfcc.bingo.standard.StandardGame

object FinishSpellHandler : RequestHandler {
    @Throws(HandlerException::class)
    override fun handle(ctx: ChannelHandlerContext, player: Player, data: JsonElement?): JsonElement? {
        val m = data!!.jsonObject
        val idx = m["index"]!!.jsonPrimitive.int
        val success = m["success"]?.jsonPrimitive?.booleanOrNull ?: true
        success || throw HandlerException("标准赛不支持收卡失败的操作")
        val room = player.room ?: throw HandlerException("不在房间里")
        room.boardSpec.isValidIndex(idx) || throw HandlerException("idx超出范围")
        room.started || throw HandlerException("游戏还没开始")
        val member = room.memberOf(player) ?: throw HandlerException("没有权限")
        StandardGame.finish(room, member, idx)
        return null
    }
}
