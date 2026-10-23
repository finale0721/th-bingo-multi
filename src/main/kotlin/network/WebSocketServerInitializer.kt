package org.tfcc.bingo.network

import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
import io.netty.handler.timeout.IdleStateHandler
import org.tfcc.bingo.admin.AdminApiHandler
import java.util.concurrent.TimeUnit

class WebSocketServerInitializer : ChannelInitializer<SocketChannel>() {
    override fun initChannel(ch: SocketChannel) {
        val pipeline = ch.pipeline()
        pipeline.addLast("heartBeat", IdleStateHandler(1, 0, 0, TimeUnit.MINUTES))
        pipeline.addLast("httpServerCodec", HttpServerCodec())
        pipeline.addLast("http-aggregator", HttpObjectAggregator(65535))
        pipeline.addLast("admin-api-handler", AdminApiHandler())
        pipeline.addLast("ws-handler", WebSocketServerProtocolHandler("/ws", null, false, 524288))
        pipeline.addLast("webSocketServerHandler", WebSocketServerChannelHandler())
    }
}
