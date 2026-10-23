package org.tfcc.bingo.network

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketFrame
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import io.netty.util.AttributeKey
import org.apache.logging.log4j.kotlin.logger
import org.tfcc.bingo.Dispatcher
import org.tfcc.bingo.Store
import org.tfcc.bingo.Supervisor
import org.tfcc.bingo.pushPlayersOnline
import java.net.SocketException

class WebSocketServerChannelHandler : SimpleChannelInboundHandler<WebSocketFrame>() {
    @Throws(Exception::class)
    override fun channelRead0(ctx: ChannelHandlerContext, msg: WebSocketFrame) {
        handlerWebSocketFrame(ctx, msg)
    }

    @Throws(Exception::class)
    override fun channelActive(ctx: ChannelHandlerContext) {
        logger.debug("客户端加入连接：${ctx.channel()}")
    }

    @Throws(Exception::class)
    override fun channelInactive(ctx: ChannelHandlerContext) {
        logger.debug("客户端断开连接：${ctx.channel()}")
        val channel = ctx.channel()
        // Supervisor/Store 非线程安全，统一回到 Dispatcher 单线程执行
        Dispatcher.pool.execute {
            val name = Supervisor.removeChannel(channel) ?: return@execute
            val player = Store.findPlayer(name) ?: return@execute
            // 顶号场景下映射已指向新连接，removeChannel 返回 null，不会误报离线
            player.room?.pushPlayersOnline()
        }
    }

    @Throws(Exception::class)
    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        ctx.flush()
    }

    @Deprecated("Deprecated in Java")
    @Throws(Exception::class)
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        if (cause is SocketException && "Connection reset" == cause.message) return
        super.exceptionCaught(ctx, cause)
    }

    override fun userEventTriggered(ctx: ChannelHandlerContext?, evt: Any?) {
        if (evt is IdleStateEvent && evt.state() == IdleState.READER_IDLE) {
            logger.debug("客户端心跳超时：${ctx?.channel()}")
            ctx?.channel()?.close()
        } else {
            super.userEventTriggered(ctx, evt)
        }
    }

    private fun handlerWebSocketFrame(ctx: ChannelHandlerContext, frame: WebSocketFrame) {
        when (frame) {
            is TextWebSocketFrame -> {
                if (frame.isFinalFragment) {
                    Dispatcher.handle(ctx, frame.text())
                } else {
                    val buf = StringBuilder(frame.text())
                    ctx.channel().attr(FRAGMENT_BUFFER_KEY).set(buf)
                }
            }
            is ContinuationWebSocketFrame -> {
                val buf = ctx.channel().attr(FRAGMENT_BUFFER_KEY).get()
                if (buf == null) {
                    logger.warn("Received continuation frame without initial text frame")
                    return
                }
                buf.append(frame.text())
                if (frame.isFinalFragment) {
                    ctx.channel().attr(FRAGMENT_BUFFER_KEY).set(null)
                    Dispatcher.handle(ctx, buf.toString())
                }
            }
            else -> {
                logger.debug("仅支持文本消息，不支持二进制消息")
                throw UnsupportedOperationException("${frame.javaClass.name} frame types not supported")
            }
        }
    }

    companion object {
        private val FRAGMENT_BUFFER_KEY = AttributeKey.valueOf<StringBuilder>("fragmentBuffer")
    }
}
