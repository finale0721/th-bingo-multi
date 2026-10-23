package org.tfcc.bingo

import org.tfcc.bingo.message.GameLog
import org.tfcc.bingo.message.LinkData
import org.tfcc.bingo.message.NormalData
import org.tfcc.bingo.message.PlayerAction
import org.tfcc.bingo.message.RoomConfig

class GameLogger {
    private var roomConfig: RoomConfig? = null
    private var spells: Array<Spell>? = null
    private var spells2: Array<Spell>? = null
    private var normalData: NormalData? = null
    private var linkData: LinkData? = null
    private val actions = mutableListOf<PlayerAction>()
    private var gameStartTimestamp: Long = 0
    private var players = arrayOf<String>("", "")
    private var score = intArrayOf(0, 0)
    private var room: Room? = null
    private var initStatus: Array<Int>? = null
    private var isCustomGame = false

    private fun updateScore(room: Room) {
        if (room.type is RoomTypeLink) {
            score[0] = room.linkData?.scoreA?.toInt() ?: room.score[0]
            score[1] = room.linkData?.scoreB?.toInt() ?: room.score[1]
            return
        }
        var left = 0
        var right = 0
        room.spellStatus!!.forEach {
            if (it == SpellStatus.LEFT_GET) left++
            else if (it == SpellStatus.RIGHT_GET) right++
        }
        score[0] = left
        score[1] = right
    }

    private fun getCurrentScore(room: Room): IntArray {
        if (room.type is RoomTypeLink) {
            return intArrayOf(
                room.linkData?.scoreA?.toInt() ?: room.score[0],
                room.linkData?.scoreB?.toInt() ?: room.score[1],
            )
        }
        var left = 0
        var right = 0
        room.spellStatus!!.forEach {
            if (it == SpellStatus.LEFT_GET) left++
            else if (it == SpellStatus.RIGHT_GET) right++
        }
        val scoreNow = intArrayOf(0, 0)
        scoreNow[0] = left
        scoreNow[1] = right
        return scoreNow
    }

    private fun updateNormalData(room: Room) {
        if (room.normalData != null) {
            this.normalData = NormalData()
            this.normalData!!.whichBoardA = room.normalData!!.whichBoardA
            this.normalData!!.whichBoardB = room.normalData!!.whichBoardB
            this.normalData!!.isPortalA = room.normalData!!.isPortalA.copyOf()
            this.normalData!!.isPortalB = room.normalData!!.isPortalB.copyOf()
            this.normalData!!.getOnWhichBoard = room.normalData!!.getOnWhichBoard.copyOf()
            this.normalData!!.extraLines = room.normalData!!.extraLines
        }
    }

    private fun updateLinkData(room: Room) {
        this.linkData = room.linkData?.copy()
    }

    fun startLog(room: Room) {
        clear()
        this.room = room
        this.roomConfig = room.roomConfig
        this.spells = room.spells
        this.spells2 = room.spells2
        this.gameStartTimestamp = room.startMs
        this.players[0] = room.players[0]?.name ?: "PlayerA"
        this.players[1] = room.players[1]?.name ?: "PlayerB"
        this.initStatus = Array(room.spells!!.size) { SpellStatus.NONE.value }
        room.spellStatus!!.forEachIndexed { index, status ->
            initStatus!![index] = status.value
        }
        this.isCustomGame = room.isCustomGame
        updateNormalData(room)
        updateLinkData(room)
        updateScore(room)
    }

    fun logAction(player: Player, actionType: String, spellIndex: Int, spell: Spell, linkData: LinkData? = null) {
        if (gameStartTimestamp == 0L) return

        val action = PlayerAction(
            playerName = player.name,
            actionType = actionType,
            spellIndex = spellIndex,
            spellName = spell.name,
            timestamp = System.currentTimeMillis() - gameStartTimestamp,
            scoreNow = getCurrentScore(room!!).toList(),
            linkData = linkData?.copy() ?: room?.linkData?.copy(),
        )
        actions.add(action)
        updateScore(room!!)
        updateNormalData(room!!)
        updateLinkData(room!!)
    }

    fun logLinkAction(
        room: Room,
        playerIndex: Int,
        actionType: String,
        spellIndex: Int = -1,
        timestampMs: Long = System.currentTimeMillis(),
        linkData: LinkData? = room.linkData,
    ) {
        if (gameStartTimestamp == 0L) return
        val spell = spellIndex.takeIf { it >= 0 }?.let { room.spells?.getOrNull(it) }
        val playerName = room.players.getOrNull(playerIndex)?.name ?: if (playerIndex == 0) "PlayerA" else "PlayerB"
        val action = PlayerAction(
            playerName = playerName,
            actionType = actionType,
            spellIndex = spellIndex,
            spellName = spell?.name ?: "",
            timestamp = (timestampMs - gameStartTimestamp).coerceAtLeast(0L),
            scoreNow = getCurrentScore(room).toList(),
            linkData = linkData?.copy(),
        )
        actions.add(action)
        updateScore(room)
        updateNormalData(room)
        updateLinkData(room)
    }

    fun getSerializedLog(): GameLog? {
        val config = roomConfig ?: return null
        val spellList = spells?.toList() ?: return null // 增加空安全检查
        val spellList2 = spells2?.toList() // spellList2 本身就是可空的，不需要 return null

        return GameLog(
            roomConfig = config,
            players = players.toList(),
            spells = spellList,
            spells2 = spellList2,
            normalData = this.normalData,
            actions = actions.toList(),
            gameStartTimestamp = this.gameStartTimestamp,
            score = this.score.toList(),
            initStatus = initStatus!!.toList(),
            isCustomGame = this.isCustomGame,
            linkData = this.linkData,
        )
    }

    fun clear() {
        roomConfig = null
        spells = null
        spells2 = null
        normalData = null
        linkData = null
        actions.clear()
        gameStartTimestamp = 0
        players.fill("")
        score = intArrayOf(0, 0)
        initStatus = null
        isCustomGame = false
    }
}
