package org.tfcc.bingo.message

import io.netty.channel.ChannelHandlerContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tfcc.bingo.Player
import org.tfcc.bingo.RequestHandler
import org.tfcc.bingo.RoomTypeLink

private fun linkRoom(player: Player) = (player.room ?: throw HandlerException("不在房间内")).also {
    it.started || throw HandlerException("游戏没开始")
    it.type is RoomTypeLink || throw HandlerException("不支持的操作")
}

private fun playerIndex(player: Player): Int {
    val room = player.room ?: throw HandlerException("不在房间内")
    val idx = room.players.indexOf(player)
    // idx >= 0 || throw HandlerException("找不到对应玩家")
    return idx
}

private fun canControlLinkPhase(player: Player): Boolean {
    val room = player.room ?: return false
    return if (room.host != null) room.isHost(player) else room.players[0] === player
}

private fun linkTargetPlayerIndex(player: Player, data: JsonElement?): Int {
    val room = player.room ?: throw HandlerException("不在房间内")
    val ownIndex = playerIndex(player)
    val requested = data?.jsonObject?.get("player_index")?.jsonPrimitive?.int ?: ownIndex
    requested in 0..1 || throw HandlerException("目标玩家错误")
    if (room.host == null) {
        requested == ownIndex || throw HandlerException("没有权限")
    }
    return requested
}

private fun routeOpPlayerIndex(player: Player): Int {
    val room = player.room ?: throw HandlerException("不在房间内")
    return RoomTypeLink.routeOpPlayerIndex(room, player)
}

object LinkRouteHandler : RequestHandler {
    override fun handle(ctx: ChannelHandlerContext, player: Player, data: JsonElement?): JsonElement? {
        val room = linkRoom(player)
        val idx = data!!.jsonObject["index"]!!.jsonPrimitive.int
        room.boardSpec.isValidIndex(idx) || throw HandlerException("无效的选卡")
        RoomTypeLink.appendRoute(room, routeOpPlayerIndex(player), idx)
        return null
    }
}

object LinkUndoHandler : RequestHandler {
    override fun handle(ctx: ChannelHandlerContext, player: Player, data: JsonElement?): JsonElement? {
        val room = linkRoom(player)
        RoomTypeLink.undoRoute(room, routeOpPlayerIndex(player))
        return null
    }
}

object LinkConfirmRouteHandler : RequestHandler {
    override fun handle(ctx: ChannelHandlerContext, player: Player, data: JsonElement?): JsonElement? {
        val room = linkRoom(player)
        val confirmed = data!!.jsonObject["confirmed"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
        RoomTypeLink.confirmRoute(room, routeOpPlayerIndex(player), confirmed)
        return null
    }
}

object LinkNextCardHandler : RequestHandler {
    override fun handle(ctx: ChannelHandlerContext, player: Player, data: JsonElement?): JsonElement? {
        val room = linkRoom(player)
        RoomTypeLink.selectNext(room, playerIndex(player))
        return null
    }
}

object LinkFinishCardHandler : RequestHandler {
    override fun handle(ctx: ChannelHandlerContext, player: Player, data: JsonElement?): JsonElement? {
        val room = linkRoom(player)
        RoomTypeLink.finishSelected(room, playerIndex(player), skip = false)
        return null
    }
}

object LinkSkipCardHandler : RequestHandler {
    override fun handle(ctx: ChannelHandlerContext, player: Player, data: JsonElement?): JsonElement? {
        val room = linkRoom(player)
        RoomTypeLink.finishSelected(room, playerIndex(player), skip = true)
        return null
    }
}

object LinkForceSkipHandler : RequestHandler {
    override fun handle(ctx: ChannelHandlerContext, player: Player, data: JsonElement?): JsonElement? {
        val room = linkRoom(player)
        RoomTypeLink.finishSelected(room, linkTargetPlayerIndex(player, data), skip = true, force = true)
        return null
    }
}

object LinkUndoFinishHandler : RequestHandler {
    override fun handle(ctx: ChannelHandlerContext, player: Player, data: JsonElement?): JsonElement? {
        val room = linkRoom(player)
        RoomTypeLink.undoFinished(room, linkTargetPlayerIndex(player, data))
        return null
    }
}

object LinkSetSkipUsedHandler : RequestHandler {
    override fun handle(ctx: ChannelHandlerContext, player: Player, data: JsonElement?): JsonElement? {
        val room = linkRoom(player)
        val value = data!!.jsonObject["value"]!!.jsonPrimitive.int
        value in 0..9999 || throw HandlerException("invalid value")
        RoomTypeLink.setSkipUsedPublic(room, linkTargetPlayerIndex(player, data), value)
        return null
    }
}

object LinkSetPhaseHandler : RequestHandler {
    override fun handle(ctx: ChannelHandlerContext, player: Player, data: JsonElement?): JsonElement? {
        val room = linkRoom(player)
        canControlLinkPhase(player) || throw HandlerException("没有控制权")
        val phase = data!!.jsonObject["phase"]!!.jsonPrimitive.int
        RoomTypeLink.setPhase(room, phase)
        return null
    }
}

object LinkAiSpeedrunHandler : RequestHandler {
    override fun handle(ctx: ChannelHandlerContext, player: Player, data: JsonElement?): JsonElement? {
        val room = linkRoom(player)
        room.roomConfig.useAI || throw HandlerException("没有开启AI练习")
        canControlLinkPhase(player) || throw HandlerException("没有控制权")
        room.linkAiAgent?.speedrun() ?: throw HandlerException("AI没有运行")
        return null
    }
}

object LinkTakeoverRouteHandler : RequestHandler {
    override fun handle(ctx: ChannelHandlerContext, player: Player, data: JsonElement?): JsonElement? {
        val room = linkRoom(player)
        canControlLinkPhase(player) || throw HandlerException("没有控制权")
        val targetIndex = data!!.jsonObject["player_index"]!!.jsonPrimitive.int
        targetIndex in 0..1 || throw HandlerException("目标玩家错误")
        if (room.host == null) {
            targetIndex != playerIndex(player) || throw HandlerException("无导播模式下只能接管对方玩家")
        }
        RoomTypeLink.takeoverRoute(room, targetIndex)
        return null
    }
}

object LinkReleaseTakeoverHandler : RequestHandler {
    override fun handle(ctx: ChannelHandlerContext, player: Player, data: JsonElement?): JsonElement? {
        val room = linkRoom(player)
        canControlLinkPhase(player) || throw HandlerException("没有控制权")
        RoomTypeLink.releaseTakeover(room)
        return null
    }
}
