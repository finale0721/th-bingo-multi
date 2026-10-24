package org.tfcc.bingo.message

import io.netty.channel.ChannelHandlerContext
import kotlinx.serialization.json.*
import org.tfcc.bingo.*
import org.tfcc.bingo.team.RoomMember

object JoinRoomHandler : RequestHandler {
    override fun handle(ctx: ChannelHandlerContext, player: Player, data: JsonElement?): JsonElement {
        player.room == null || throw HandlerException("已经在房间里了")
        val m = data!!.jsonObject
        val rid = m["rid"]!!.jsonPrimitive.content
        val room = Store.getRoom(rid) ?: throw HandlerException("房间不存在")
        player.room = room
        val team = if (room.isRosterLocked()) null else room.nextJoinTeam()
        if (team != null) {
            val member = RoomMember(player, team, captain = room.teamSize(team) == 0)
            room.members.add(member)
            room.push(
                "push_join_room", JsonObject(
                    mapOf(
                        "name" to JsonPrimitive(player.name),
                        "position" to JsonPrimitive(team.index),
                    )
                )
            )
        } else {
            room.watchers.add(player)
            room.push(
                "push_join_room", JsonObject(
                    mapOf(
                        "name" to JsonPrimitive(player.name),
                        "position" to JsonPrimitive(-1),
                    )
                )
            )
        }
        room.pushRosterUpdate()
        return room.roomInfo(room.teamOf(player)).encode()
    }
}
