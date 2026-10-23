package org.tfcc.bingo.message

import io.netty.channel.ChannelHandlerContext
import kotlinx.serialization.json.JsonElement
import org.tfcc.bingo.*

object UpdateRoomConfigHandler : RequestHandler {
    override fun handle(ctx: ChannelHandlerContext, player: Player, data: JsonElement?): JsonElement? {
        val room = player.room ?: throw HandlerException("不在房间里")
        val m = data!!.decode<RoomConfigNullable>()
        m.rid == room.roomId || throw HandlerException("不是你所在的房间")
        room.isHost(player) || throw HandlerException("没有权限")
        if (room.started && m.boardSize != null && m.boardSize != room.roomConfig.boardSize) {
            throw HandlerException("board size cannot be changed after game start")
        }
        m.validate()
        val next = room.roomConfig + m
        next.validate()
        room.roomConfig = next
        room.push("push_update_room_config", room.roomConfig.encode())
        return null
    }
}
