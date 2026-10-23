package org.tfcc.bingo.message

import io.netty.channel.ChannelHandlerContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tfcc.bingo.Player
import org.tfcc.bingo.RequestHandler
import org.tfcc.bingo.SpellConfig
import org.tfcc.bingo.SpellConfig.NORMAL_GAME
import org.tfcc.bingo.encode
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

object GetXlsxDataHandler : RequestHandler {
    @Throws(HandlerException::class)
    override fun handle(ctx: ChannelHandlerContext, player: Player, data: JsonElement?): JsonElement? {
        val id = data?.jsonObject?.get("id")?.jsonPrimitive?.content?.toInt() ?: throw HandlerException("缺少参数id")
        val spells = SpellConfig.get(NORMAL_GAME, id)
        val json = spells.encode().toString()

        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).bufferedWriter().use { it.write(json) }
        val compressedData = bos.toByteArray()

        return Base64.getEncoder().encodeToString(compressedData).encode()
    }
}
