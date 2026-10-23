package org.tfcc.bingo.message

import io.netty.channel.ChannelHandlerContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.tfcc.bingo.Player
import org.tfcc.bingo.RequestHandler
import org.tfcc.bingo.Store
import org.tfcc.bingo.encode

object GetRoomListHandler : RequestHandler {
    override fun handle(ctx: ChannelHandlerContext, player: Player, data: JsonElement?): JsonElement? {
        player.room == null || throw HandlerException("已经在房间里了")
        return Store.getRoomList().encode()
    }
}

@Serializable
data class RoomInfoForList(
    val rid: String,
    val host: String?,
    val players: List<String?>,
    val lastActive: Long,
    val isMatching: Boolean
)
