package org.tfcc.bingo.message

import io.netty.channel.ChannelHandlerContext
import kotlinx.serialization.json.JsonElement
import org.tfcc.bingo.GameLogger
import org.tfcc.bingo.Player
import org.tfcc.bingo.RequestHandler
import org.tfcc.bingo.decode
import org.tfcc.bingo.encode
import org.tfcc.bingo.push
import org.tfcc.bingo.toSpellStatus
import kotlin.random.Random

object StartCustomGameHandler : RequestHandler {
    override fun handle(ctx: ChannelHandlerContext, player: Player, data: JsonElement?): JsonElement? {
        val room = player.room ?: throw HandlerException("不在房间里")
        room.isHost(player) || throw HandlerException("没有权限")
        !room.started || throw HandlerException("游戏已经开始")
        room.players.all { it != null } || throw HandlerException("玩家没满")

        val msg = data!!.decode<StartCustomGameMessage>()
        msg.validate()
        msg.roomConfig.validate()

        // Apply the new roomConfig first so that boardSpec/boardArea reflect the new board size
        room.type.resetData(room)
        room.roomConfig = msg.roomConfig
        room.type.setUp(room)

        room.spells2 = emptyArray()
        room.spells = msg.spells.toTypedArray()
        if (room.roomConfig.dualBoard > 0) {
            room.spells2 = msg.spells2!!.toTypedArray()
        } else {
            room.spells2 = null
        }

        room.type.initStatus(room)
        if (room.gameLogger == null) {
            room.gameLogger = GameLogger()
        }

        // 自定义处理环节
        room.isCustomGame = true
        val generatedExtraLines = if (room.boardSpec.size == 6 && room.roomConfig.extraLineCount > 0) {
            room.boardSpec.generateExtraLines(
                room.roomConfig.extraLineCount,
                Random.Default
            )
        } else {
            emptyList()
        }

        room.normalData = NormalData.create(room.boardSpec)
        room.normalData!!.extraLines = generatedExtraLines

        val spellStatusInts = msg.spellStatus.toIntArray()
        room.spellStatus = spellStatusInts.map { it.toSpellStatus() }.toTypedArray()
        room.spellStatusInPlayerClient = Array(2) { spellStatusInts }

        if (room.roomConfig.dualBoard > 0) {
            val normalData = NormalData.create(room.boardSpec)
            normalData.whichBoardA = 0
            normalData.whichBoardB = 0
            normalData.isPortalA = msg.isPortalA.toTypedArray()
            normalData.isPortalB = msg.isPortalB.toTypedArray()
            normalData.getOnWhichBoard = Array(room.boardArea) { 0 }
            normalData.extraLines = generatedExtraLines
            room.normalData = normalData
        }

        room.push("push_start_game", room.roomConfig.encode())
        room.gameLogger!!.startLog(room)
        return null
    }
}
