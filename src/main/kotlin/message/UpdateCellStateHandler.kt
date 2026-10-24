package org.tfcc.bingo.message

import io.netty.channel.ChannelHandlerContext
import kotlinx.serialization.json.*
import org.tfcc.bingo.Player
import org.tfcc.bingo.RequestHandler
import org.tfcc.bingo.standard.StandardGame

/**
 * 导播/无导播房直接操作格子：
 * {index, op: toggle_select|capture_once|set_captured|clear, name?, team?, board?}
 */
object UpdateCellStateHandler : RequestHandler {
    @Throws(HandlerException::class)
    override fun handle(ctx: ChannelHandlerContext, player: Player, data: JsonElement?): JsonElement? {
        val m = data!!.jsonObject
        val room = player.room ?: throw HandlerException("不在房间里")
        val idx = m["index"]!!.jsonPrimitive.int
        room.boardSpec.isValidIndex(idx) || throw HandlerException("idx超出范围")
        val op = m["op"]!!.jsonPrimitive.content
        val name = m["name"]?.jsonPrimitive?.contentOrNull
        val team = m["team"]?.jsonPrimitive?.intOrNull
        val board = m["board"]?.jsonPrimitive?.intOrNull ?: 0
        StandardGame.updateCellState(room, player, idx, op, name, team, board)
        return null
    }
}
