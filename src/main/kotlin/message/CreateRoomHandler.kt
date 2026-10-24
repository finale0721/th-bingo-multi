package org.tfcc.bingo.message

import io.netty.channel.ChannelHandlerContext
import kotlinx.serialization.json.*
import org.tfcc.bingo.*
import org.tfcc.bingo.team.RoomMember

object CreateRoomHandler : RequestHandler {
    override fun handle(ctx: ChannelHandlerContext, player: Player, data: JsonElement?): JsonElement {
        player.room == null || throw HandlerException("已经在房间里了")
        val m0 = data!!.jsonObject
        val solo = m0["solo"]?.jsonPrimitive?.booleanOrNull ?: false
        val practice = m0["practice"]?.jsonPrimitive?.booleanOrNull ?: false
        val config = m0["room_config"]!!.decode<RoomConfig>()
        Store.getRoom(config.rid) == null || throw HandlerException("房间已存在")
        val roomConfig = if (practice && !config.practice) {
            config + RoomConfigNullable(rid = config.rid, practice = true)
        } else {
            config
        }
        roomConfig.validate()
        // 练习房必然是无导播房
        val noHost = solo || practice || roomConfig.practice
        val room = Room(roomConfig.rid, if (noHost) null else player, roomConfig)
        Store.putRoom(room)
        player.room = room
        // 导播只是导播，不占选手席位；无导播房/练习房的创建者才是成员
        if (noHost) {
            val team = room.nextJoinTeam() ?: throw HandlerException("房间已满")
            room.members.add(RoomMember(player, team, captain = room.teamSize(team) == 0))
            room.pushRosterUpdate()
        }
        return JsonObject(mapOf("rid" to JsonPrimitive(roomConfig.rid)))
    }
}
