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
    members.forEach { it.player.push(pushAction, pushData) }
    watchers.forEach { it.push(pushAction, pushData) }
}

/** 全量阵容快照：成员列表/观众/队长/队伍分数与换卡次数 */
fun Room.pushRosterUpdate() {
    push("push_roster_update", rosterSnapshot())
}

fun Room.rosterSnapshot(): JsonElement {
    val teams = org.tfcc.bingo.team.Team.entries.map { team ->
        val teamMembers = membersOf(team)
        JsonObject(
            mapOf(
                "key" to JsonPrimitive(team.name),
                "members" to JsonArray(
                    teamMembers.map { m ->
                        JsonObject(
                            mapOf(
                                "name" to JsonPrimitive(m.name),
                                "captain" to JsonPrimitive(m.captain),
                                "online" to JsonPrimitive(Supervisor.getChannel(m.name) != null),
                                "order" to JsonPrimitive(members.indexOf(m)),
                            )
                        )
                    }
                ),
                "score" to JsonPrimitive(score[team.index]),
                "change_card_count" to JsonPrimitive(changeCardCount[team.index]),
            )
        )
    }
    return JsonObject(
        mapOf(
            "teams" to JsonArray(teams),
            "watchers" to JsonArray(watchers.map { JsonPrimitive(it.name) }),
            "host" to JsonPrimitive(host?.name ?: ""),
            "started" to JsonPrimitive(started),
            "roster_locked" to JsonPrimitive(isRosterLocked()),
            "practice" to JsonPrimitive(roomConfig.practice),
        )
    )
}

/** 广播成员连接状态快照，登录/断线时调用；离线期间错过推送的成员靠下一次快照自愈 */
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
    "switch_team" to SwitchTeamHandler,
    "toggle_captain" to ToggleCaptainHandler,
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
    "finish_spell" to FinishSpellHandler,
    "update_cell_state" to UpdateCellStateHandler,
    "change_card" to ChangeCardHandler,
    "ban_pick" to BanPickHandler,
    "start_ban_pick" to StartBanPickHandler,
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
                            player.room?.pushRosterUpdate()
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
