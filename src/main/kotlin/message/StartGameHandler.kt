package org.tfcc.bingo.message

import io.netty.channel.ChannelHandlerContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tfcc.bingo.Dispatcher
import org.tfcc.bingo.GameLogger
import org.tfcc.bingo.Player
import org.tfcc.bingo.RequestHandler
import org.tfcc.bingo.Spell
import org.tfcc.bingo.admin.CustomPoolStore
import org.tfcc.bingo.decode
import org.tfcc.bingo.encode
import org.tfcc.bingo.push
import org.tfcc.bingo.pushRosterUpdate
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.InflaterInputStream

object StartGameHandler : RequestHandler {
    @Throws(HandlerException::class)
    override fun handle(ctx: ChannelHandlerContext, player: Player, data: JsonElement?): JsonElement? {
        val room = player.room ?: throw HandlerException("不在房间里")
        room.canManage(player) || throw HandlerException("没有权限")
        !room.started || throw HandlerException("游戏已经开始")
        room.banPick?.let { it.phase == 9999 || throw HandlerException("作品BP还没结束") }
        room.bothTeamsReady() || throw HandlerException(
            if (room.roomConfig.practice) "A队必须有一名队长" else "双方队伍必须各有且仅有一名队长"
        )

        val obj = data?.jsonObject
        obj?.get("change_card_base_a")?.jsonPrimitive?.intOrNull?.let { room.changeCardBase[0] = it.coerceIn(0, 99) }
        obj?.get("change_card_base_b")?.jsonPrimitive?.intOrNull?.let { room.changeCardBase[1] = it.coerceIn(0, 99) }

        val customPool = readCustomPool(data)
        if (customPool != null) {
            validateCustomPool(customPool)
            room.customCardPool = customPool
            room.roomConfig = room.roomConfig + RoomConfigNullable(
                rid = room.roomId,
                customCardPoolEnabled = customPool.isNotEmpty()
            )
        } else if (!room.roomConfig.customCardPoolEnabled) {
            room.customCardPool = emptyArray()
        }
        if (room.roomConfig.customCardPoolEnabled && room.customCardPool.isEmpty()) {
            throw HandlerException("custom card pool is empty")
        }

        room.roomConfig.validate()
        room.type.resetData(room)
        room.type.setUp(room)
        room.type.rollSpellCard(room)
        room.type.initStatus(room)
        if (room.gameLogger == null) {
            room.gameLogger = GameLogger()
        }
        room.type.onStart(room)
        room.push("push_start_game", room.roomConfig.encode())
        room.gameLogger!!.startLog(room)
        room.pushRosterUpdate()
        return null
    }

    private fun readCustomPool(data: JsonElement?): Array<Spell>? {
        val obj = data?.jsonObject ?: return null

        val poolMd5 = obj["custom_pool_md5"]?.jsonPrimitive?.contentOrNull
        if (!poolMd5.isNullOrBlank()) {
            return CustomPoolStore.getPoolSpells(poolMd5)
                ?: throw HandlerException("在线卡池不存在或已过期")
        }

        val compressed = obj["custom_card_pool_compressed"]?.jsonPrimitive?.contentOrNull
        if (!compressed.isNullOrBlank()) {
            val bytes = Base64.getDecoder().decode(compressed)
            val json = InflaterInputStream(ByteArrayInputStream(bytes)).bufferedReader().use { it.readText() }
            return Dispatcher.json.decodeFromString(json)
        }
        return obj["custom_card_pool"]?.decode<Array<Spell>>()
    }

    private fun validateCustomPool(customPool: Array<Spell>) {
        customPool.isNotEmpty() || throw HandlerException("custom card pool is empty")
        customPool.size <= 5000 || throw HandlerException("custom card pool is too large")
        customPool.all { it.game.isNotBlank() && it.name.isNotBlank() && it.rank in arrayOf("L", "EX", "PH") } ||
            throw HandlerException("custom card pool format error")
    }
}
