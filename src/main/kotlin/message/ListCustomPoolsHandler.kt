package org.tfcc.bingo.message

import io.netty.channel.ChannelHandlerContext
import kotlinx.serialization.json.JsonElement
import org.tfcc.bingo.RequestHandler
import org.tfcc.bingo.admin.CustomPoolStore
import org.tfcc.bingo.encode

object ListCustomPoolsHandler : RequestHandler {
    override fun handle(ctx: ChannelHandlerContext, player: org.tfcc.bingo.Player, data: JsonElement?): JsonElement? {
        return CustomPoolStore.listPools().encode()
    }
}
