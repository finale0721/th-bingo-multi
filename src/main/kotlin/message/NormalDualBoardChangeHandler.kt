package org.tfcc.bingo.message

import io.netty.channel.ChannelHandlerContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tfcc.bingo.Player
import org.tfcc.bingo.RequestHandler
import org.tfcc.bingo.push

object NormalDualBoardChangeHandler : RequestHandler {
    @Throws(HandlerException::class)
    override fun handle(ctx: ChannelHandlerContext, player: Player, data: JsonElement?): JsonElement? {
        val room = player.room ?: throw HandlerException("不在房间里")
        val index = room.players.indexOf(player)
        val isHost = room.host == player
        index >= 0 || isHost || throw HandlerException("异常换面请求")
        if (room.roomConfig.dualBoard <= 0 || room.normalData == null) {
            throw HandlerException("未开启双重面板时，该操作无效")
        }
        val m = data!!.jsonObject
        val playerIndex = m["player"]!!.jsonPrimitive.content.toInt()
        val targetBoardIndex = m["to"]!!.jsonPrimitive.content.toInt()

        if (playerIndex == 0) {
            room.normalData!!.whichBoardA = targetBoardIndex % 2
        } else if (playerIndex == 1) {
            room.normalData!!.whichBoardB = targetBoardIndex % 2
        }
        if (isHost) {
            room.push(
                "push_normal_dual_board_change", JsonObject(
                    mapOf(
                        "playerId" to JsonPrimitive(playerIndex),
                        "to" to JsonPrimitive(targetBoardIndex % 2),
                    )
                )
            )
        }
        return null
    }
}
