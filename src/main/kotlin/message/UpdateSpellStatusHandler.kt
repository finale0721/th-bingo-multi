package org.tfcc.bingo.message

import io.netty.channel.ChannelHandlerContext
import kotlinx.serialization.json.*
import org.tfcc.bingo.*
import org.tfcc.bingo.SpellStatus.*

object UpdateSpellStatusHandler : RequestHandler {
    @Throws(HandlerException::class)
    override fun handle(ctx: ChannelHandlerContext, player: Player, data: JsonElement?): JsonElement? {
        val m = data!!.jsonObject
        val idx = m["index"]!!.jsonPrimitive.int
        val status = m["status"]!!.jsonPrimitive.int
        val room = player.room ?: throw HandlerException("不在房间里")
        room.boardSpec.isValidIndex(idx) || throw HandlerException("idx超出范围")
        val spellStatus = status.toSpellStatus()
        room.started || throw HandlerException("游戏还没开始")
        if (room.host != null) { // 自己是房主则有权限
            room.host === player || throw HandlerException("没有权限")
        } else { // 无房主模式，只要是选手就有权限
            player in room.players || throw HandlerException("没有权限")
        }
        if (room.host === player && room.linkData != null) {
            if (room.phase == 1) {
                throw HandlerException("link赛符卡还未选完，暂不能操作")
            }
        }
        val formerStatus = room.spellStatus!![idx]
        cardStateTransform(room, player, idx, spellStatus)
        room.type.updateSpellStatusPostProcesser(room, player, idx, formerStatus, spellStatus)
        recordGetBoard(room, player, idx)
        room.type.pushSpells(room, idx, player.name)
        room.aiAgent?.onCellStatusChanged(idx, formerStatus, spellStatus)
        // Log the update action
        room.gameLogger?.logAction(
            player = player,
            actionType = "set-${spellStatus.value}",
            spellIndex = idx,
            spell = room.spells!![idx]
        )
        return null
    }

    private fun cardStateTransform(room: Room, player: Player, spellIdx: Int, stat: SpellStatus) {
        // 有导播，直接覆盖
        if (room.host != null) {
            room.spellStatus!![spellIdx] = stat
            return
        }
        // 判断玩家是左还是右
        val isLeftPlayer = (player === room.players[0])
        var st = room.spellStatus!![spellIdx]
        if (!isLeftPlayer) st = st.opposite()
        when (stat) {
            // 如果操作是置空，则只置空己方选择状态，其余状态不受影响
            NONE -> {
                room.spellStatus!![spellIdx] = when (st) {
                    LEFT_SELECT -> NONE
                    BOTH_SELECT -> RIGHT_SELECT
                    else -> stat
                }.run { if (!isLeftPlayer) opposite() else this }
            }
            // 如果操作是选择，则将对方选择状态提升为双选。由于需要保留原始结果，故不反转
            LEFT_SELECT, RIGHT_SELECT -> {
                room.spellStatus!![spellIdx] = when (st) {
                    RIGHT_SELECT -> BOTH_SELECT
                    else -> stat
                }
            }
            // 其余情况，直接覆盖即可
            else -> {
                room.spellStatus!![spellIdx] = stat
            }
        }
    }

    private fun recordGetBoard(room: Room, player: Player, spellIdx: Int) {
        if (room.normalData == null) return
        val st = room.spellStatus!![spellIdx]
        if (st == LEFT_GET && room.normalData!!.whichBoardA == 0) {
            room.normalData!!.getOnWhichBoard[spellIdx] = 0x1
            if (room.normalData!!.isPortalA[spellIdx] == 1) {
                room.normalData!!.whichBoardA = 1 - room.normalData!!.whichBoardA
            }
        } else if (st == LEFT_GET && room.normalData!!.whichBoardA == 1) {
            room.normalData!!.getOnWhichBoard[spellIdx] = 0x2
            if (room.normalData!!.isPortalB[spellIdx] == 1) {
                room.normalData!!.whichBoardA = 1 - room.normalData!!.whichBoardA
            }
        } else if (st == RIGHT_GET && room.normalData!!.whichBoardB == 0) {
            room.normalData!!.getOnWhichBoard[spellIdx] = 0x10
            if (room.normalData!!.isPortalA[spellIdx] == 1) {
                room.normalData!!.whichBoardB = 1 - room.normalData!!.whichBoardB
            }
        } else if (st == RIGHT_GET && room.normalData!!.whichBoardB == 1) {
            room.normalData!!.getOnWhichBoard[spellIdx] = 0x20
            if (room.normalData!!.isPortalB[spellIdx] == 1) {
                room.normalData!!.whichBoardB = 1 - room.normalData!!.whichBoardB
            }
        }
    }
}
