package org.tfcc.bingo.message

import io.netty.channel.ChannelHandlerContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tfcc.bingo.RequestHandler
import org.tfcc.bingo.admin.CustomPoolStore

object DeleteCustomPoolHandler : RequestHandler {
    override fun handle(ctx: ChannelHandlerContext, player: org.tfcc.bingo.Player, data: JsonElement?): JsonElement? {
        val md5 = data?.jsonObject?.get("md5")?.jsonPrimitive?.contentOrNull
            ?: throw HandlerException("缺少md5参数")
        CustomPoolStore.deletePool(md5, player.name)
        return null
    }
}
