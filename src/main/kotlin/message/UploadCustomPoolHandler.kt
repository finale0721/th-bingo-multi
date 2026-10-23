package org.tfcc.bingo.message

import io.netty.channel.ChannelHandlerContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tfcc.bingo.Dispatcher
import org.tfcc.bingo.RequestHandler
import org.tfcc.bingo.Spell
import org.tfcc.bingo.admin.CustomPoolStore
import org.tfcc.bingo.encode
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.InflaterInputStream

object UploadCustomPoolHandler : RequestHandler {
    override fun handle(ctx: ChannelHandlerContext, player: org.tfcc.bingo.Player, data: JsonElement?): JsonElement? {
        val obj = data?.jsonObject ?: throw HandlerException("缺少参数")
        val xlsxBase64 = obj["xlsx_base64"]?.jsonPrimitive?.contentOrNull
            ?: throw HandlerException("缺少卡池文件内容")
        val fileName = obj["file_name"]?.jsonPrimitive?.contentOrNull ?: "未命名.xlsx"
        val note = obj["note"]?.jsonPrimitive?.contentOrNull ?: ""

        val spellsCompressed = obj["spells_compressed"]?.jsonPrimitive?.contentOrNull
        val spellsJson = if (spellsCompressed != null) {
            inflateFromBase64(spellsCompressed)
        } else {
            obj["spells_json"]?.jsonPrimitive?.contentOrNull
                ?: throw HandlerException("缺少卡池数据")
        }
        val gamesCompressed = obj["games_compressed"]?.jsonPrimitive?.contentOrNull
        val gamesJson = if (gamesCompressed != null) {
            inflateFromBase64(gamesCompressed)
        } else {
            obj["games_json"]?.jsonPrimitive?.contentOrNull
                ?: throw HandlerException("缺少作品数据")
        }

        val spells = Dispatcher.json.decodeFromString<Array<Spell>>(spellsJson)
        spells.isNotEmpty() || throw HandlerException("custom card pool is empty")
        spells.size <= 5000 || throw HandlerException("custom card pool is too large")
        spells.all { it.game.isNotBlank() && it.name.isNotBlank() && it.rank in arrayOf("L", "EX", "PH") } ||
            throw HandlerException("custom card pool format error")

        val metadata = CustomPoolStore.savePool(
            uploaderName = player.name,
            fileName = fileName,
            note = note,
            xlsxBase64 = xlsxBase64,
            spellsJson = spellsJson,
            gamesJson = gamesJson,
        )
        return metadata.encode()
    }

    private fun inflateFromBase64(base64: String): String {
        val bytes = Base64.getDecoder().decode(base64)
        return InflaterInputStream(ByteArrayInputStream(bytes)).bufferedReader().use { it.readText() }
    }
}
