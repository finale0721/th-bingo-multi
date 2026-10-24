package org.tfcc.bingo.message

import io.netty.channel.ChannelHandlerContext
import kotlinx.serialization.json.JsonElement
import org.tfcc.bingo.BanPick
import org.tfcc.bingo.Player
import org.tfcc.bingo.RequestHandler
import org.tfcc.bingo.pushRosterUpdate
import kotlin.random.Random

object StartBanPickHandler : RequestHandler {
    override fun handle(ctx: ChannelHandlerContext, player: Player, data: JsonElement?): JsonElement? {
        val room = player.room ?: throw HandlerException("不在房间里")
        room.canManage(player) || throw HandlerException("没有权限")
        !room.started || throw HandlerException("游戏已经开始")
        !room.roomConfig.practice || throw HandlerException("练习模式不支持作品BP")
        room.bothTeamsReady() || throw HandlerException("双方队伍必须各有且仅有一名队长")
        if (room.banPick != null && room.banPick!!.phase != 9999) throw HandlerException("正在BP环节")
        val bp = BanPick(Random.nextInt(2))
        room.banPick = bp
        bp.notifyAll(room)
        room.pushRosterUpdate()
        return null
    }
}
