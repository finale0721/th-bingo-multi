package org.tfcc.bingo.network

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelOption
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import org.apache.logging.log4j.kotlin.logger
import org.tfcc.bingo.admin.AdminAuthStore
import org.tfcc.bingo.admin.CustomPoolStore
import org.tfcc.bingo.admin.GameRecordStore

object Network {
    /** 多人版默认端口；可用环境变量 TFCC_PORT 覆盖，便于本地并行起多个测试实例 */
    val PORT: Int = System.getenv("TFCC_PORT")?.toIntOrNull() ?: 9998

    fun onInit() {
        GameRecordStore.ensureStorage()
        AdminAuthStore.ensureStorage()
        CustomPoolStore.ensureStorage()
        initGameNetwork()
    }

    private fun initGameNetwork() {
        val bossGroup = NioEventLoopGroup()
        val workGroup = NioEventLoopGroup()
        try {
            val bootstrap = ServerBootstrap().group(bossGroup, workGroup)
                .channel(NioServerSocketChannel::class.java)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(WebSocketServerInitializer())
            val channelFuture = bootstrap.bind(PORT).sync()
            channelFuture.addListener(object : ChannelFutureListener {
                override fun operationComplete(future: ChannelFuture) {
                    logger.debug(if (channelFuture.isSuccess) "监听端口 $PORT 成功" else "监听端口 $PORT 失败")
                }
            })
            channelFuture.channel().closeFuture().sync()
        } finally {
            bossGroup.shutdownGracefully()
            workGroup.shutdownGracefully()
        }
    }
}
