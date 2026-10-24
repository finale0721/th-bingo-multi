package org.tfcc.bingo.message

import io.netty.channel.ChannelHandlerContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.tfcc.bingo.Player
import org.tfcc.bingo.RequestHandler
import org.tfcc.bingo.Store
import org.tfcc.bingo.push
import org.tfcc.bingo.pushRosterUpdate

object LeaveRoomHandler : RequestHandler {
    override fun handle(ctx: ChannelHandlerContext, player: Player, data: JsonElement?): JsonElement? {
        val room = player.room ?: throw HandlerException("不在房间里")
        val member = room.memberOf(player)
        if (room.host === player || member != null) {
            !room.isRosterLocked() || throw HandlerException("当前阵容已锁定，不能退出")
        }
        // 导播在系列赛进行中退出会销毁房间，直接阻止
        if (room.host === player) {
            !room.locked || throw HandlerException("系列赛进行中，不能退出房间")
        }
        player.room = null
        if (room.host === player) { // 导播退出，踢出所有人，销毁房间
            room.members.forEach { it.player.pushSelfKicked() }
            room.watchers.forEach { it.pushSelfKicked() }
            Store.removeRoom(room.roomId)
        } else {
            if (member != null) {
                room.members.remove(member)
            } else {
                room.watchers.remove(player)
            }
            if (room.host == null && room.members.isEmpty()) {
                // 无导播且所有成员都退出，解散房间
                room.watchers.forEach { it.pushSelfKicked() }
                Store.removeRoom(room.roomId)
            } else {
                room.push("push_leave_room", JsonObject(mapOf("name" to JsonPrimitive(player.name))))
                room.pushRosterUpdate()
            }
        }
        return null
    }

    /** 推送自己被踢了 */
    private fun Player.pushSelfKicked() {
        room = null
        push("push_leave_room", JsonObject(mapOf("name" to JsonPrimitive(name))))
    }
}
