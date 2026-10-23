package org.tfcc.bingo.message

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.tfcc.bingo.Room
import org.tfcc.bingo.Store
import org.tfcc.bingo.Supervisor

@Serializable
class RoomInfo(
    val rid: String,
    val type: Int,
    val host: String?,
    val names: List<String?>,
    @SerialName("change_card_count")
    val changeCardCount: IntArray,
    val started: Boolean,
    val score: IntArray,
    val watchers: List<String>,
    @SerialName("last_winner")
    val lastWinner: Int,
    @SerialName("ban_pick")
    val banPick: BanPickInfo? = null,
    @SerialName("players_online")
    val playersOnline: Map<String, Boolean>,
)

/** 两位选手到服务器的连接状态，机器人没有连接状态不参与 */
fun Room.playersOnline(): Map<String, Boolean> = players
    .filterNotNull()
    .filter { it.name != Store.ROBOT_NAME }
    .associate { it.name to (Supervisor.getChannel(it.name) != null) }

/**
 * @param playerIndex 左边玩家0，右边玩家1，不是玩家-1
 */
fun Room.roomInfo(playerIndex: Int) = RoomInfo(
    rid = roomId,
    type = roomConfig.type,
    host = host?.name,
    names = players.map { it?.name },
    changeCardCount = changeCardCount,
    started = started,
    score = score,
    watchers = watchers.map { it.name },
    lastWinner = lastWinner,
    banPick = banPick?.toPb(playerIndex),
    playersOnline = playersOnline(),
)
