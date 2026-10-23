package org.tfcc.bingo.admin

import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.QueryStringDecoder
import io.netty.util.CharsetUtil
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.apache.logging.log4j.kotlin.logger
import org.tfcc.bingo.Dispatcher
import org.tfcc.bingo.encode

class AdminApiHandler : SimpleChannelInboundHandler<FullHttpRequest>() {
    override fun channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val decoder = QueryStringDecoder(request.uri())
        if (!decoder.path().startsWith("/admin-api")) {
            ctx.fireChannelRead(request.retain())
            return
        }

        if (request.method() == HttpMethod.OPTIONS) {
            writeJson(ctx, request, HttpResponseStatus.NO_CONTENT, 0, "ok", JsonNull)
            return
        }

        try {
            when {
                request.method() == HttpMethod.POST && decoder.path() == "/admin-api/login" -> {
                    handleLogin(ctx, request)
                }

                request.method() == HttpMethod.GET && decoder.path() == "/admin-api/matches" -> {
                    requireAuth(request)
                    handleListMatches(ctx, request, decoder)
                }

                request.method() == HttpMethod.POST && decoder.path() == "/admin-api/matches/batch" -> {
                    requireAuth(request)
                    handleBatchMatches(ctx, request)
                }

                request.method() == HttpMethod.GET && decoder.path().startsWith("/admin-api/matches/") -> {
                    requireAuth(request)
                    handleGetMatch(ctx, request, decoder.path().removePrefix("/admin-api/matches/"))
                }

                request.method() == HttpMethod.GET && decoder.path() == "/admin-api/analytics/user-overview" -> {
                    requireAuth(request)
                    handleUserOverview(ctx, request, decoder)
                }

                else -> {
                    writeJson(ctx, request, HttpResponseStatus.NOT_FOUND, 404, "not found", JsonNull)
                }
            }
        } catch (e: UnauthorizedException) {
            writeJson(ctx, request, HttpResponseStatus.UNAUTHORIZED, 401, e.message ?: "unauthorized", JsonNull)
        } catch (e: IllegalArgumentException) {
            writeJson(ctx, request, HttpResponseStatus.BAD_REQUEST, 400, e.message ?: "bad request", JsonNull)
        } catch (e: Exception) {
            logger.error("Admin API request failed: ${request.uri()}", e)
            writeJson(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR, 500, "server error", JsonNull)
        }
    }

    private fun handleLogin(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val body = request.content().toString(CharsetUtil.UTF_8)
        val loginRequest = Dispatcher.json.decodeFromString<AdminLoginRequest>(body)
        val response = AdminAuthStore.login(
            username = loginRequest.username.trim(),
            password = loginRequest.password
        )
            ?: throw UnauthorizedException("invalid username or password")
        writeJson(ctx, request, HttpResponseStatus.OK, 0, "ok", response.encode())
    }

    private fun handleListMatches(ctx: ChannelHandlerContext, request: FullHttpRequest, decoder: QueryStringDecoder) {
        val parameters = decoder.parameters()
        val response = GameRecordStore.listRecords(
            keyword = parameters["keyword"]?.firstOrNull(),
            from = parameters["from"]?.firstOrNull()?.toLongOrNull(),
            to = parameters["to"]?.firstOrNull()?.toLongOrNull(),
            saveReason = parameters["save_reason"]?.firstOrNull(),
            limit = parameters["limit"]?.firstOrNull()?.toIntOrNull() ?: 200,
        )
        writeJson(ctx, request, HttpResponseStatus.OK, 0, "ok", response.encode())
    }

    private fun handleBatchMatches(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val body = request.content().toString(CharsetUtil.UTF_8)
        val batchRequest = if (body.isBlank()) {
            AdminMatchBatchRequest()
        } else {
            Dispatcher.json.decodeFromString<AdminMatchBatchRequest>(body)
        }
        val items = GameRecordStore.getRecords(batchRequest.ids)
        writeJson(
            ctx,
            request,
            HttpResponseStatus.OK,
            0,
            "ok",
            AdminMatchBatchResponse(total = items.size, items = items).encode(),
        )
    }

    private fun handleGetMatch(ctx: ChannelHandlerContext, request: FullHttpRequest, recordId: String) {
        val normalizedId = recordId.trim()
        if (normalizedId.isEmpty()) {
            throw IllegalArgumentException("record id is required")
        }
        val record = GameRecordStore.getRecord(normalizedId)
            ?: throw IllegalArgumentException("record not found")
        writeJson(ctx, request, HttpResponseStatus.OK, 0, "ok", record.encode())
    }

    private fun handleUserOverview(ctx: ChannelHandlerContext, request: FullHttpRequest, decoder: QueryStringDecoder) {
        val parameters = decoder.parameters()
        val response = GameRecordStore.buildUserOverview(
            keyword = parameters["keyword"]?.firstOrNull(),
            from = parameters["from"]?.firstOrNull()?.toLongOrNull(),
            to = parameters["to"]?.firstOrNull()?.toLongOrNull(),
            saveReason = parameters["save_reason"]?.firstOrNull(),
        )
        writeJson(ctx, request, HttpResponseStatus.OK, 0, "ok", response.encode())
    }

    private fun requireAuth(request: FullHttpRequest) {
        val authorized = AdminAuthStore.isAuthorized(request.headers().get(HttpHeaderNames.AUTHORIZATION))
        if (!authorized) {
            throw UnauthorizedException("admin authorization required")
        }
    }

    private fun writeJson(
        ctx: ChannelHandlerContext,
        request: FullHttpRequest,
        status: HttpResponseStatus,
        code: Int,
        msg: String,
        data: JsonElement,
    ) {
        val payload = JsonObject(
            mapOf(
                "code" to JsonPrimitive(code),
                "msg" to JsonPrimitive(msg),
                "data" to data,
            ),
        )
        val content = Unpooled.copiedBuffer(
            Dispatcher.json.encodeToString(JsonObject.serializer(), payload),
            CharsetUtil.UTF_8,
        )
        val response: FullHttpResponse = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content)
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8")
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())
        val origin = request.headers().get(HttpHeaderNames.ORIGIN) ?: "*"
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, origin)
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,OPTIONS")
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type,Authorization")
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE, "86400")
        response.headers().set(HttpHeaderNames.VARY, "Origin")

        val keepAlive = HttpUtil.isKeepAlive(request)
        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
            ctx.writeAndFlush(response)
        } else {
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
        }
    }

    private class UnauthorizedException(message: String) : RuntimeException(message)
}
