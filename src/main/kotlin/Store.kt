package org.tfcc.bingo

import org.apache.logging.log4j.kotlin.logger
import org.tfcc.bingo.admin.GameRecordStore
import org.tfcc.bingo.message.*
import java.io.*
import java.util.*

object Store {
    private val playerCache = HashMap<String, Player>()
    private val roomCache = HashMap<String, Room>()

    init {
        Timer().schedule(object : TimerTask() {
            override fun run() {
                Dispatcher.pool.submit(::clean)
            }
        }, 500, 30 * 60 * 1000)
    }

    private fun clean() {
        logger.debug("开始清除过期缓存")
        try {
            val now = System.currentTimeMillis()
            val outdatedPlayers = HashSet<String>()
            val outdatedRooms = HashSet<String>()
            for ((playerName, player) in playerCache) {
                player.room == null || continue // 只清除无房间的玩家
                now - player.lastOperateMs >= 2 * 3600 * 1000 || continue // 只清除2小时以上无操作的玩家
                outdatedPlayers.add(playerName)
            }
            for ((roomId, room) in roomCache) {
                now - room.lastOperateMs >= 6 * 3600 * 1000 || continue // 只清除6小时以上无操作的房间
                try {
                    GameRecordStore.saveCleanupSnapshotIfNeeded(room)
                } catch (e: Exception) {
                    logger.error("Failed to save cleanup snapshot for room $roomId, continuing cleanup", e)
                }
                outdatedRooms.add(roomId)
                room.host?.let { outdatedPlayers.add(it.name) }
                room.members.forEach { outdatedPlayers.add(it.name) }
                room.watchers.forEach { outdatedPlayers.add(it.name) }
            }
            for (playerName in outdatedPlayers) {
                logger.info("玩家 $playerName 过期, 自动清除")
                playerCache.remove(playerName)
            }
            for (roomId in outdatedRooms) {
                logger.info("房间 $roomId 过期, 自动清除")
                roomCache.remove(roomId)
            }
        } catch (e: Exception) {
            logger.error("Unexpected error while cleaning expired cache", e)
        }
    }

    @Throws(HandlerException::class)
    fun getPlayer(name: String, pwd: String? = null): Player {
        var player = playerCache[name]
        if (player != null) {
            pwd == null || player.pwd == pwd || throw HandlerException("密码错误")
            return player
        }
        player = Player(name, pwd!!)
        playerCache[name] = player
        return player
    }

    fun findPlayer(name: String): Player? = playerCache[name]

    fun putRoom(room: Room) {
        roomCache[room.roomId] = room
    }

    fun getRoom(roomId: String): Room? {
        return roomCache[roomId]
    }

    fun removeRoom(roomId: String) {
        roomCache.remove(roomId)
    }

    fun getRoomList(): List<RoomInfoForList> {
        return roomCache.values
            .sortedByDescending { it.lastOperateMs }
            .take(10)
            .map {
                RoomInfoForList(
                    rid = it.roomId,
                    host = it.host?.name,
                    players = it.members.map { m -> m.name },
                    teamA = it.membersOf(org.tfcc.bingo.team.Team.A).joinToString(" ") { m -> m.name },
                    teamB = it.membersOf(org.tfcc.bingo.team.Team.B).joinToString(" ") { m -> m.name },
                    lastActive = it.lastOperateMs,
                    isMatching = it.started
                )
            }
    }
}
