package org.tfcc.bingo

import org.tfcc.bingo.message.CellSnapshot
import org.tfcc.bingo.message.GameLog
import org.tfcc.bingo.message.GameTeam
import org.tfcc.bingo.message.GameTeamMember
import org.tfcc.bingo.message.LinkData
import org.tfcc.bingo.message.NormalData
import org.tfcc.bingo.message.PlayerAction
import org.tfcc.bingo.message.RoomConfig
import org.tfcc.bingo.message.StandardData
import org.tfcc.bingo.standard.StandardState
import org.tfcc.bingo.team.Team

class GameLogger {
    private var roomConfig: RoomConfig? = null
    private var spells: Array<Spell>? = null
    private var spells2: Array<Spell>? = null
    private var normalData: NormalData? = null
    private var linkData: LinkData? = null
    private var standardData: StandardData? = null
    private val actions = mutableListOf<PlayerAction>()
    private var gameStartTimestamp: Long = 0
    private var players = emptyList<String>()
    private var score = intArrayOf(0, 0)
    private var room: Room? = null
    private var initStatus: List<Int> = emptyList()
    private var isCustomGame = false

    private fun updateScore(room: Room) {
        if (room.type is RoomTypeLink) {
            score[0] = room.linkData?.scoreA?.toInt() ?: room.score[0]
            score[1] = room.linkData?.scoreB?.toInt() ?: room.score[1]
            return
        }
        val now = getCurrentScore(room)
        score[0] = now[0]
        score[1] = now[1]
    }

    private fun getCurrentScore(room: Room): IntArray {
        if (room.type is RoomTypeLink) {
            return intArrayOf(
                room.linkData?.scoreA?.toInt() ?: room.score[0],
                room.linkData?.scoreB?.toInt() ?: room.score[1],
            )
        }
        val state = room.standardState
            ?: return intArrayOf(room.score[0], room.score[1])
        var a = 0
        var b = 0
        for (index in 0 until state.board.area) {
            when (state.fullyCapturedBy(index)) {
                0 -> a++
                1 -> b++
            }
        }
        return intArrayOf(a, b)
    }

    private fun updateNormalData(room: Room) {
        if (room.normalData != null) {
            this.normalData = NormalData()
            this.normalData!!.extraLines = room.normalData!!.extraLines
        }
    }

    private fun updateLinkData(room: Room) {
        this.linkData = room.linkData?.copy()
    }

    /** 按盘面生成快照：回放时把 cellAfter 直接写回对应面 */
    private fun snapshotCell(state: StandardState, board: Int, index: Int): CellSnapshot {
        val cell = state.cell(board, index)
        val names = cell.selectedBy.toList()
        return CellSnapshot(
            selectedByA = names.filter { it in (teamNames[0] ?: emptySet()) },
            selectedByB = names.filter { it in (teamNames[1] ?: emptySet()) },
            captureCountA = cell.captureCountA,
            captureCountB = cell.captureCountB,
            capturedByA = cell.capturedByA || cell.capturedBy == 0,
            capturedByB = cell.capturedByB || cell.capturedBy == 1,
            fullyCapturedBy = state.fullyCapturedBy(index),
        )
    }

    private var teamNames: Map<Int, Set<String>> = emptyMap()

    private fun buildStandardData(room: Room): StandardData? {
        val state = room.standardState ?: return null
        val teams = listOf(Team.A, Team.B).map { team ->
            val members = room.membersOf(team)
            GameTeam(
                key = team.name,
                members = members.map {
                    GameTeamMember(it.name, it.captain, it.actualCdTime)
                },
                captain = members.firstOrNull { it.captain }?.name,
            )
        }
        return StandardData(
            captureStandard = state.captureStandard,
            dualBoard = state.dualBoard,
            copyBoard = room.roomConfig.copyBoard,
            boardSize = room.roomConfig.boardSize,
            extraLines = state.extraLines,
            teams = teams,
            cells = (0 until state.board.area).map { snapshotCell(state, 0, it) },
            cells2 = state.cells2?.let { _ ->
                (0 until state.board.area).map { snapshotCell(state, 1, it) }
            },
        )
    }

    fun startLog(room: Room) {
        clear()
        this.room = room
        this.roomConfig = room.roomConfig
        this.spells = room.spells
        this.spells2 = room.spells2
        this.gameStartTimestamp = room.startMs
        this.players = room.members.map { it.name }
        this.initStatus = emptyList()
        this.isCustomGame = room.isCustomGame
        this.teamNames = mapOf(
            0 to room.membersOf(Team.A).map { it.name }.toSet(),
            1 to room.membersOf(Team.B).map { it.name }.toSet(),
        )
        this.standardData = buildStandardData(room)
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

    /** 标准赛动作记录：状态由 cellAfter 决定，op 只驱动表现 */
    fun logStandardAction(
        room: Room,
        actor: Player?,
        op: String,
        team: Int?,
        board: Int,
        spellIndex: Int,
        spell: Spell?,
        affectedName: String? = null,
        victims: List<String> = emptyList(),
    ) {
        if (gameStartTimestamp == 0L) return
        val state = room.standardState ?: return
        val snapshot = if (spellIndex >= 0) snapshotCell(state, board, spellIndex) else null
        val otherSnapshot = if (spellIndex >= 0 && state.dualBoard) {
            snapshotCell(state, 1 - (board % 2), spellIndex)
        } else {
            null
        }
        val action = PlayerAction(
            playerName = actor?.name ?: "",
            actionType = op,
            spellIndex = spellIndex,
            spellName = spell?.name ?: "",
            timestamp = (System.currentTimeMillis() - gameStartTimestamp).coerceAtLeast(0L),
            scoreNow = getCurrentScore(room).toList(),
            team = team,
            op = op,
            board = board,
            name = affectedName,
            victims = victims,
            changeCardCount = room.changeCardCount.toList(),
            cellAfter = snapshot,
            cellAfterOther = otherSnapshot,
            captureCountA = snapshot?.captureCountA,
            captureCountB = snapshot?.captureCountB,
            capturedByA = snapshot?.capturedByA,
            capturedByB = snapshot?.capturedByB,
            fullyCapturedBy = snapshot?.fullyCapturedBy,
        )
        actions.add(action)
        updateScore(room)
        updateNormalData(room)
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
        val spellList = spells?.toList() ?: return null
        val spellList2 = spells2?.toList()

        // 结束时按最终状态重新生成 standardData 的初始快照？不 —— 初始快照必须保持开局状态。
        return GameLog(
            roomConfig = config,
            players = players,
            spells = spellList,
            spells2 = spellList2,
            normalData = this.normalData,
            actions = actions.toList(),
            gameStartTimestamp = this.gameStartTimestamp,
            score = this.score.toList(),
            initStatus = initStatus,
            isCustomGame = this.isCustomGame,
            linkData = this.linkData,
            standardData = this.standardData,
        )
    }

    fun clear() {
        roomConfig = null
        spells = null
        spells2 = null
        normalData = null
        linkData = null
        standardData = null
        teamNames = emptyMap()
        actions.clear()
        gameStartTimestamp = 0
        players = emptyList()
        score = intArrayOf(0, 0)
        initStatus = emptyList()
        isCustomGame = false
    }
}
