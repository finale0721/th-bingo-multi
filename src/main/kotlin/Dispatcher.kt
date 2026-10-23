package org.tfcc.bingo

import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import kotlinx.serialization.json.*
import message.RefreshSpellHandler
import org.apache.logging.log4j.kotlin.logger
import org.tfcc.bingo.message.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private val verbosePayloadActions = setOf("upload_custom_pool", "get_xlsx_data", "get_custom_pool")

private fun ChannelHandlerContext.writeMessage(
    message: ResponseMessage,
    writeLog: Boolean = true,
    action: String? = null
): ChannelFuture {
    val text = Dispatcher.json.encodeToString(message)
    if (writeLog) {
        val logText = if (action in verbosePayloadActions) "action=$action, size=${text.length}" else text
        Dispatcher.logger.debug("返回${channel().id().asShortText()}：$logText")
    }
    return writeAndFlush(TextWebSocketFrame(text))
}

private fun Channel.push(pushAction: String, pushData: JsonElement?): ChannelFuture {
    val text = Dispatcher.json.encodeToString(PushMessage(pushAction, pushData))
    Dispatcher.logger.debug("推送${id().asShortText()}：$text")
    return writeAndFlush(TextWebSocketFrame(text))
}

fun Player.push(pushAction: String, pushData: JsonElement?, mustOnline: Boolean = false): ChannelFuture? {
    if (name == Store.ROBOT_NAME) {
        if (mustOnline) throw HandlerException("对方已离线")
        else return null
    }
    val channel = Supervisor.getChannel(name)
    if (channel == null) {
        if (mustOnline) throw HandlerException("对方已离线")
        Dispatcher.logger.warn("对方已离线：$name，推送失败")
        return null
    }
    val text = Dispatcher.json.encodeToString(PushMessage(pushAction, pushData))
    Dispatcher.logger.debug("推送${channel.id().asShortText()}：$text")
    return channel.writeAndFlush(TextWebSocketFrame(text))
}

fun Room.push(pushAction: String, pushData: JsonElement?) {
    host?.push(pushAction, pushData)
    players.forEach { it?.push(pushAction, pushData) }
    watchers.forEach { it.push(pushAction, pushData) }
}

/** 广播两位选手的连接状态快照，登录/断线时调用；离线期间错过推送的成员靠下一次快照自愈 */
fun Room.pushPlayersOnline() {
    push("push_player_status", JsonObject(mapOf("players_online" to playersOnline().encode())))
}

private val handlers = mapOf(
    "login" to LoginHandler,
    "create_room" to CreateRoomHandler,
    "get_room_config" to GetRoomConfigHandler,
    "update_room_config" to UpdateRoomConfigHandler,
    "join_room" to JoinRoomHandler,
    "get_room" to GetRoomHandler,
    "leave_room" to LeaveRoomHandler,
    "stand_up" to StandUpHandler,
    "sit_down" to SitDownHandler,
    "set_phase" to SetPhaseHandler,
    "get_phase" to GetPhaseHandler,
    "start_game" to StartGameHandler,
    "stop_game" to StopGameHandler,
    "gm_warn_player" to GmWarnPlayerHandler,
    "update_change_card_count" to UpdateChangeCardCountHandler,
    "pause" to PauseHandler,
    "reset_room" to ResetRoomHandler,
    "set_debug_spells" to SetDebugSpellsHandler,
    "get_all_spells" to GetAllSpellsHandler,
    "select_spell" to SelectSpellHandler,
    "cancel_select_spell" to CancelSelectSpellHandler,
    "finish_spell" to FinishSpellHandler,
    "update_spell_status" to UpdateSpellStatusHandler,
    "ban_pick" to BanPickHandler,
    "start_ban_pick" to StartBanPickHandler,
    "bp_game_ban_pick" to BpGameBanPickHandler,
    "bp_game_next_round" to BpGameNextRoundHandler,
    "link_time" to LinkTimeHandler,
    "link_route" to LinkRouteHandler,
    "link_undo" to LinkUndoHandler,
    "link_confirm_route" to LinkConfirmRouteHandler,
    "link_next_card" to LinkNextCardHandler,
    "link_finish_card" to LinkFinishCardHandler,
    "link_skip_card" to LinkSkipCardHandler,
    "link_force_skip" to LinkForceSkipHandler,
    "link_undo_finish" to LinkUndoFinishHandler,
    "link_set_skip_used" to LinkSetSkipUsedHandler,
    "link_set_phase" to LinkSetPhaseHandler,
    "link_ai_speedrun" to LinkAiSpeedrunHandler,
    "link_takeover_route" to LinkTakeoverRouteHandler,
    "link_release_takeover" to LinkReleaseTakeoverHandler,
    "normal_dual_board_change" to NormalDualBoardChangeHandler,
    "refresh_one_spell" to RefreshSpellHandler,
    "print_log" to PrintLogHandler,
    "get_room_list" to GetRoomListHandler,
    "get_xlsx_data" to GetXlsxDataHandler,
    "start_custom_game" to StartCustomGameHandler,
    "upload_custom_pool" to UploadCustomPoolHandler,
    "list_custom_pools" to ListCustomPoolsHandler,
    "get_custom_pool" to GetCustomPoolHandler,
    "delete_custom_pool" to DeleteCustomPoolHandler,
)

inline fun <reified T> JsonElement.decode(): T = Dispatcher.json.decodeFromJsonElement(this)

inline fun <reified T> T.encode(): JsonElement = Dispatcher.json.encodeToJsonElement(this)

object Dispatcher {
    val pool: ExecutorService = Executors.newSingleThreadExecutor()

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun handle(ctx: ChannelHandlerContext, text: String) {
        var echo: JsonElement? = null
        try {
            val m = json.parseToJsonElement(text).jsonObject
            echo = m["echo"]
            val action = m["action"]!!.jsonPrimitive.content
            if (action != "heart") {
                val logText = if (action in verbosePayloadActions) "action=$action, size=${text.length}" else text
                logger.debug("收到${ctx.channel().id().asShortText()}：$logText")
            }
            val data = m["data"]
            pool.submit {
                try {
                    var player: Player? = null
                    val playerName = Supervisor.getPlayerName(ctx.channel())
                    if (playerName != null)
                        player = Store.getPlayer(playerName)
                    when (action) {
                        "login" -> {
                            if (player != null) {
                                ctx.writeMessage(ResponseMessage(-1, "You have already logged", null, echo))
                                return@submit
                            }
                            val dataObj = data!!.jsonObject
                            val name = dataObj["name"]!!.jsonPrimitive.content
                            name.toByteArray().size <= 48 || throw HandlerException("名字太长")
                            val pwd = dataObj["pwd"]!!.jsonPrimitive.content
                            player = Store.getPlayer(name, pwd)
                            val oldChannel = Supervisor.put(ctx.channel(), player.name)
                            oldChannel?.push("push_kick", null)
                            // 重连登录后重推快照：既通知房内其他人该玩家已上线，也让该玩家补齐离线期间错过的状态
                            player.room?.pushPlayersOnline()
                        }

                        "heart" -> {
                            val response = JsonObject(mapOf("now" to JsonPrimitive(System.currentTimeMillis())))
                            ctx.writeMessage(ResponseMessage(0, "ok", response, echo), false)
                            return@submit
                        }

                        else -> {
                            if (player == null) {
                                ctx.writeMessage(ResponseMessage(-1, "You haven't login", null, echo))
                                return@submit
                            }
                        }
                    }
                    val handler = handlers[action] ?: throw HandlerException("unknown action")
                    val now = System.currentTimeMillis()
                    player.lastOperateMs = now
                    player.room?.lastOperateMs = now // 对于离开房间类协议，在执行之前需要修改
                    val response = handler.handle(ctx, player, data)
                    player.room?.lastOperateMs = now // 对于加入房间类协议，在执行之后需要修改
                    ctx.writeMessage(ResponseMessage(0, "ok", response, echo), action = action)
                } catch (e: IllegalArgumentException) {
                    logger.error("illegal json", e)
                    ctx.writeMessage(ResponseMessage(400, "illegal json", null, echo))
                } catch (e: NullPointerException) {
                    logger.error("illegal request", e)
                    ctx.writeMessage(ResponseMessage(400, "illegal request", null, echo))
                } catch (e: HandlerException) {
                    logger.warn("handle failed: $action", e)
                    ctx.writeMessage(ResponseMessage(500, e.message ?: "handle failed", null, echo))
                } catch (e: Throwable) {
                    logger.error("handler unknown throwable: $action", e)
                    ctx.writeMessage(ResponseMessage(500, "handler unknown throwable", null, echo))
                }
            }
        } catch (e: IllegalArgumentException) {
            logger.error("illegal json", e)
            ctx.writeMessage(ResponseMessage(400, "illegal json", null, echo))
        } catch (e: NullPointerException) {
            logger.error("illegal request", e)
            ctx.writeMessage(ResponseMessage(400, "illegal request", null, echo))
        } catch (e: Throwable) {
            logger.error("server error", e)
            ctx.writeMessage(ResponseMessage(500, "server error", null, echo))
        }
    }
}
