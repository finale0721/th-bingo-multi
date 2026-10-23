package org.tfcc.bingo.message

import io.netty.channel.ChannelHandlerContext
import kotlinx.serialization.json.JsonElement
import org.tfcc.bingo.BanPick
import org.tfcc.bingo.Player
import org.tfcc.bingo.RequestHandler
import org.tfcc.bingo.Store
import kotlin.random.Random

object StartBanPickHandler : RequestHandler {
    override fun handle(ctx: ChannelHandlerContext, player: Player, data: JsonElement?): JsonElement? {
        val room = player.room ?: throw HandlerException("不在房间里")
        room.isHost(player) || throw HandlerException("没有权限")
        !room.started || throw HandlerException("游戏已经开始")
        room.players.all { it != null } || throw HandlerException("玩家没满")
        room.players.none { it?.name == Store.ROBOT_NAME } || throw HandlerException("单人练习模式不支持BP")
        if (room.banPick != null && room.banPick!!.phase != 9999) throw HandlerException("正在BP环节")
        val bp = BanPick(Random.nextInt(2))
        room.banPick = bp
        room.locked = true
        bp.notifyAll(room)
        return null
    }
}
