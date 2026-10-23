package org.tfcc.bingo

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.tfcc.bingo.SpellStatus.*
import org.tfcc.bingo.message.HandlerException
import org.tfcc.bingo.message.LinkData
import java.util.*

object RoomTypeLink : RoomType {
    override val name = "Link赛"

    override val canPause = false

    override fun difficultyMode(difficulty: Int?): DifficultyMode = DifficultyMode.LINK

    override fun rollSpellCard(room: Room, stars: IntArray?) {
        val linkStars = SpellFactory.buildLinkStarArray(
            linkDifficulty(room.roomConfig.difficulty),
            room.roomConfig.boardSize,
            setOf(room.roomConfig.linkStartA, room.roomConfig.linkStartB)
        )
        super.rollSpellCard(room, linkStars)
    }

    override fun onStart(room: Room) {
        val baseCdTime = (room.roomConfig.cdTime ?: 0).toLong() * 1000L
        room.actualCdTime[0] = baseCdTime.coerceAtLeast(1000L)
        room.actualCdTime[1] = baseCdTime.coerceAtLeast(1000L)
        val board = room.boardSpec
        val startA = startIndex(room, 0)
        val startB = startIndex(room, 1)
        room.spellStatus!![startA] = LEFT_SELECT
        room.spellStatus!![startB] = RIGHT_SELECT
        val linkData = LinkData()
        linkData.ensureStatusSize(board.area)
        linkData.statusA[startA] = LEFT_SELECT.value
        linkData.statusB[startB] = RIGHT_SELECT.value
        linkData.linkIdxA.add(startA)
        linkData.linkIdxB.add(startB)
        linkData.disabledIdx.addAll(room.roomConfig.linkDisabledIdx.distinct())
        room.linkData = linkData
        if (room.roomConfig.useAI && room.players[1]!!.name.equals(Store.ROBOT_NAME)) {
            room.linkAiAgent = LinkAIAgent(room)
            room.linkAiAgent?.start()
        }
    }

    override fun handleNextRound(room: Room) {
        throw HandlerException("不支持下一回合的游戏类型")
    }

    @Throws(HandlerException::class)
    override fun randSpells(
        spellCardVersion: Int,
        games: Array<String>,
        ranks: Array<String>,
        difficulty: Int?,
        boardSize: Int,
        customSettings: IntArray?
    ): Array<Spell> {
        val diffObj = linkDifficulty(difficulty)
        return SpellFactory.drawSpells(
            DifficultyMode.LINK, spellCardVersion, games, ranks,
            difficultyObj = diffObj,
            boardSize = boardSize,
        )
    }

    override fun randSpellsWithStar(
        spellCardVersion: Int,
        games: Array<String>,
        ranks: Array<String>,
        difficulty: Int?,
        stars: IntArray?,
        boardSize: Int,
        customSettings: IntArray?
    ): Array<Spell> {
        if (stars == null) {
            return randSpells(spellCardVersion, games, ranks, difficulty, boardSize, customSettings)
        }
        return SpellFactory.drawSpellsWithStar(
            DifficultyMode.LINK, spellCardVersion, games, ranks, stars, boardSize, customSettings
        )
    }

    override fun rollSpellsStarArray(difficulty: Int?, boardSize: Int, customSettings: IntArray?): IntArray {
        return SpellFactory.buildStarArray(
            DifficultyMode.LINK,
            difficulty,
            difficultyObj = linkDifficulty(difficulty),
            boardSize = boardSize,
        )
    }

    private fun linkDifficulty(difficulty: Int?): Difficulty = when (difficulty) {
        1 -> Difficulty.E
        2 -> Difficulty.N
        3 -> Difficulty.L
        else -> Difficulty.L
    }

    override fun handleSelectSpell(room: Room, playerIndex: Int, spellIndex: Int) {
        appendRoute(room, playerIndex, spellIndex)
    }

    override fun handleFinishSpell(room: Room, isHost: Boolean, playerIndex: Int, spellIndex: Int, success: Boolean) {
        isHost || playerIndex >= 0 || throw HandlerException("没有权限")
        finishSelected(room, playerIndex, false, spellIndex)
    }

    override fun pushSpells(room: Room, spellIndex: Int, causer: String) {
        val status = room.spellStatus!![spellIndex]
        room.push(
            "push_update_spell_status", JsonObject(
                mapOf(
                    "index" to JsonPrimitive(spellIndex),
                    "status" to JsonPrimitive(status.value),
                    "causer" to JsonPrimitive(causer),
                )
            )
        )
        with(room.spellStatusInPlayerClient!!) {
            indices.forEach {
                this[it][spellIndex] = status.value
            }
        }
    }

    override fun getAllSpellStatus(room: Room, playerIndex: Int): List<Int> {
        val linkData = room.linkData ?: return super.getAllSpellStatus(room, playerIndex)
        linkData.ensureStatusSize(room.boardArea)
        return (0 until room.boardArea).map { idx ->
            mergeStatus(linkData.statusA[idx], linkData.statusB[idx])
        }
    }

    fun routeOpPlayerIndex(room: Room, player: Player): Int {
        val takeover = room.linkData?.takeoverPlayerIndex ?: -1
        if (takeover < 0) {
            val idx = room.players.indexOf(player)
            idx >= 0 || throw HandlerException("找不到对应玩家")
            return idx
        }
        val ownIdx = room.players.indexOf(player)
        if (ownIdx == takeover) throw HandlerException("路线已被接管，无法操作")
        val isController = room.isHost(player) || (room.host == null && room.players[0] === player)
        if (isController) return takeover
        ownIdx >= 0 || throw HandlerException("找不到对应玩家")
        return ownIdx
    }

    fun appendRoute(room: Room, playerIndex: Int, spellIndex: Int) {
        room.phase == 1 || throw HandlerException("不在路线构筑阶段")
        val linkData = room.linkData!!
        linkData.ensureStatusSize(room.boardArea)
        if (spellIndex in linkData.disabledIdx) throw HandlerException("该格已禁用")
        val route = routeOf(linkData, playerIndex)
        if (isRouteConfirmed(linkData, playerIndex)) throw HandlerException("路线已确认")
        if (spellIndex in route) throw HandlerException("已经选了这张卡")
        val end = endIndex(room, playerIndex)
        val last = route.last()
        if (last == end) throw HandlerException("路线已到达终点")
        if (!neighbors(room, last).contains(spellIndex)) throw HandlerException("不合理的选卡")
        route.add(spellIndex)
        pushLinkData(room)
        room.gameLogger?.logLinkAction(room, playerIndex, "link_route", spellIndex)
    }

    fun undoRoute(room: Room, playerIndex: Int) {
        room.phase == 1 || throw HandlerException("不在路线构筑阶段")
        val linkData = room.linkData!!
        val route = routeOf(linkData, playerIndex)
        if (isRouteConfirmed(linkData, playerIndex)) throw HandlerException("路线已确认")
        if (route.size <= 1) throw HandlerException("起点不能撤回")
        val removed = route.removeAt(route.lastIndex)
        pushLinkData(room)
        room.gameLogger?.logLinkAction(room, playerIndex, "link_undo", removed)
    }

    fun confirmRoute(room: Room, playerIndex: Int, confirmed: Boolean) {
        room.phase == 1 || throw HandlerException("不在路线构筑阶段")
        val linkData = room.linkData!!
        val route = routeOf(linkData, playerIndex)
        if (confirmed) {
            route.last() == endIndex(room, playerIndex) || throw HandlerException("路线还未到达终点")
        }
        if (playerIndex == 0) linkData.routeConfirmedA = confirmed else linkData.routeConfirmedB = confirmed
        pushLinkData(room)
        room.gameLogger?.logLinkAction(
            room,
            playerIndex,
            if (confirmed) "link_confirm_route" else "link_unconfirm_route",
            route.lastOrNull() ?: -1,
        )
    }

    fun startRun(room: Room) {
        room.phase = 2
        val linkData = room.linkData!!
        linkData.takeoverPlayerIndex = -1
        completeRouteIfNeeded(room, 0)
        completeRouteIfNeeded(room, 1)
        linkData.routeConfirmedA = true
        linkData.routeConfirmedB = true
        val now = System.currentTimeMillis()
        room.startMs = now - room.roomConfig.countdown * 1000L
        linkData.startMsA = now
        linkData.startMsB = now
        linkData.eventA = 1
        linkData.eventB = 1
        linkData.lastGetTimeA = now - room.actualCdTime[0]
        linkData.lastGetTimeB = now - room.actualCdTime[1]
        if (linkData.linkIdxA.isNotEmpty()) linkData.statusA[linkData.linkIdxA[0]] = LEFT_SELECT.value
        if (linkData.linkIdxB.isNotEmpty()) linkData.statusB[linkData.linkIdxB[0]] = RIGHT_SELECT.value
        pushLinkData(room)
        room.gameLogger?.logLinkAction(room, 0, "link_start_run", linkData.linkIdxA.firstOrNull() ?: -1)
        room.gameLogger?.logLinkAction(room, 1, "link_start_run", linkData.linkIdxB.firstOrNull() ?: -1)
    }

    fun selectNext(room: Room, playerIndex: Int) {
        room.phase == 2 || throw HandlerException("不在正式比赛阶段")
        val linkData = room.linkData!!
        linkData.ensureStatusSize(room.boardArea)
        val route = routeOf(linkData, playerIndex)
        val currentStep = currentStepOf(linkData, playerIndex)
        if (currentStep >= route.size) throw HandlerException("路线已完成")
        val idx = route[currentStep]
        val status = if (playerIndex == 0) linkData.statusA[idx] else linkData.statusB[idx]
        if (status == LEFT_SELECT.value || status == RIGHT_SELECT.value) throw HandlerException("已经选择了这张卡")
        val now = System.currentTimeMillis()
        val lastGet = if (playerIndex == 0) linkData.lastGetTimeA else linkData.lastGetTimeB
        val remain = lastGet + room.actualCdTime[playerIndex] - now
        if (remain > 1000L) throw HandlerException("还有${remain / 1000 + 1}秒才能选卡")
        if (playerIndex == 0) {
            linkData.statusA[idx] = LEFT_SELECT.value
        } else {
            linkData.statusB[idx] = RIGHT_SELECT.value
        }
        pushLinkData(room)
        room.gameLogger?.logLinkAction(room, playerIndex, "link_next_card", idx)
    }

    fun finishSelected(room: Room, playerIndex: Int, skip: Boolean, expectedIndex: Int? = null, force: Boolean = false) {
        room.phase == 2 || throw HandlerException("不在正式比赛阶段")
        val linkData = room.linkData!!
        linkData.ensureStatusSize(room.boardArea)
        val route = routeOf(linkData, playerIndex)
        val currentStep = currentStepOf(linkData, playerIndex)
        if (currentStep >= route.size) throw HandlerException("路线已完成")
        val idx = route[currentStep]
        if (expectedIndex != null && expectedIndex != idx) throw HandlerException("只能按路线顺序收取")
        val currentStatus = if (playerIndex == 0) linkData.statusA[idx] else linkData.statusB[idx]
        if (!skip && currentStatus != LEFT_SELECT.value && currentStatus != RIGHT_SELECT.value) {
            throw HandlerException("还未选择这张卡")
        }
        val now = System.currentTimeMillis()
        if (skip) {
            val allowed = if (route.size > 10) 2 else 1
            if (!force && skipUsedOf(linkData, playerIndex) >= allowed) throw HandlerException("跳过次数已用完")
            val startedAt = if (playerIndex == 0) linkData.lastGetTimeA else linkData.lastGetTimeB
            val cd = room.actualCdTime[playerIndex]
            if (startedAt > 0L && now - startedAt < cd) throw HandlerException("CD期间不能跳过")
            val waitMs = 45_000L
            if (!force && now - startedAt - cd < waitMs) throw HandlerException("还不能跳过这张卡")
            setSkipUsed(linkData, playerIndex, (skipUsedOf(linkData, playerIndex) + 1).coerceAtMost(allowed))
            skippedRouteOf(linkData, playerIndex).add(idx)
        } else {
            skippedRouteOf(linkData, playerIndex).remove(idx)
        }
        if (playerIndex == 0) {
            linkData.statusA[idx] = LEFT_GET.value
            linkData.currentStepA = currentStep + 1
            linkData.lastGetTimeA = now
            if (linkData.currentStepA >= route.size) {
                linkData.eventA = 3
                linkData.endMsA = now
            }
        } else {
            linkData.statusB[idx] = RIGHT_GET.value
            linkData.currentStepB = currentStep + 1
            linkData.lastGetTimeB = now
            if (linkData.currentStepB >= route.size) {
                linkData.eventB = 3
                linkData.endMsB = now
            }
        }
        updateScores(room)
        pushLinkData(room)
        room.gameLogger?.logLinkAction(room, playerIndex, if (skip) "link_skip_card" else "link_finish_card", idx)
    }

    fun undoFinished(room: Room, playerIndex: Int) {
        room.phase == 2 || throw HandlerException("不在正式比赛阶段")
        val linkData = room.linkData!!
        linkData.ensureStatusSize(room.boardArea)
        val step = currentStepOf(linkData, playerIndex)
        if (step <= 0) throw HandlerException("没有可撤销的收取")
        val route = routeOf(linkData, playerIndex)
        val idx = route[step - 1]
        val now = System.currentTimeMillis()
        if (playerIndex == 0) {
            route.drop(step).forEach { linkData.statusA[it] = NONE.value }
            route.drop(step - 1).forEach { linkData.skippedIdxA.remove(it) }
            linkData.statusA[idx] = LEFT_SELECT.value
            linkData.currentStepA = step - 1
            linkData.eventA = 1
            linkData.endMsA = 0
            linkData.lastGetTimeA = now - room.actualCdTime[0]
        } else {
            route.drop(step).forEach { linkData.statusB[it] = NONE.value }
            route.drop(step - 1).forEach { linkData.skippedIdxB.remove(it) }
            linkData.statusB[idx] = RIGHT_SELECT.value
            linkData.currentStepB = step - 1
            linkData.eventB = 1
            linkData.endMsB = 0
            linkData.lastGetTimeB = now - room.actualCdTime[1]
        }
        updateScores(room)
        pushLinkData(room)
        room.gameLogger?.logLinkAction(room, playerIndex, "link_undo_finish", idx)
    }

    fun takeoverRoute(room: Room, targetPlayerIndex: Int) {
        room.phase == 1 || throw HandlerException("不在路线构筑阶段")
        val linkData = room.linkData!!
        linkData.takeoverPlayerIndex = targetPlayerIndex
        pushLinkData(room)
        room.gameLogger?.logLinkAction(room, targetPlayerIndex, "link_takeover_route")
    }

    fun releaseTakeover(room: Room) {
        val linkData = room.linkData ?: return
        linkData.takeoverPlayerIndex = -1
        pushLinkData(room)
        room.gameLogger?.logLinkAction(room, -1, "link_release_takeover")
    }

    fun setSkipUsedPublic(room: Room, playerIndex: Int, value: Int) {
        val route = routeOf(room.linkData!!, playerIndex)
        val allowed = if (route.size > 10) 2 else 1
        setSkipUsed(room.linkData!!, playerIndex, value.coerceIn(0, allowed))
        pushLinkData(room)
        room.gameLogger?.logLinkAction(room, playerIndex, "link_set_skip_used")
    }

    fun setPhase(room: Room, phase: Int) {
        when (phase) {
            1 -> room.phase = 1
            2 -> startRun(room)
            3 -> {
                room.phase = 3
                val now = System.currentTimeMillis()
                val linkData = room.linkData!!
                if (linkData.eventA != 3) {
                    linkData.eventA = 3
                    linkData.endMsA = now
                }
                if (linkData.eventB != 3) {
                    linkData.eventB = 3
                    linkData.endMsB = now
                }
                updateScores(room)
                pushLinkData(room)
                room.gameLogger?.logLinkAction(room, 0, "link_finish_run", linkData.linkIdxA.lastOrNull() ?: -1)
                room.gameLogger?.logLinkAction(room, 1, "link_finish_run", linkData.linkIdxB.lastOrNull() ?: -1)
            }
            else -> throw HandlerException("Link赛不支持该阶段")
        }
    }

    fun recalculateScoresAndPush(room: Room, logSpeedrun: Boolean = true) {
        updateScores(room)
        pushLinkData(room)
        if (logSpeedrun) {
            room.gameLogger?.logLinkAction(room, 1, "link_ai_speedrun", room.linkData?.linkIdxB?.lastOrNull() ?: -1)
        }
    }

    private fun updateScores(room: Room) {
        val linkData = room.linkData!!
        linkData.scoreA = scoreOf(room, 0)
        linkData.scoreB = scoreOf(room, 1)
    }

    private fun scoreOf(room: Room, playerIndex: Int): Double {
        val linkData = room.linkData!!
        val skipped = skippedRouteOf(linkData, playerIndex).toSet()
        val route = routeOf(linkData, playerIndex).take(currentStepOf(linkData, playerIndex)).filter { it !in skipped }
        val levelSum = route.sumOf { room.spells!![it].star }
        val fastestSum = route.sumOf { room.spells!![it].fastest.toDouble() }
        val start = if (playerIndex == 0) linkData.startMsA else linkData.startMsB
        val event = if (playerIndex == 0) linkData.eventA else linkData.eventB
        val end = if (playerIndex == 0) linkData.endMsA else linkData.endMsB
        val usedMs = activeUsedMs(room, playerIndex, event, start, end)
        return room.boardSpec.size * 200.0 +
            levelSum * room.roomConfig.linkLevelCoefficient +
            fastestSum * room.roomConfig.linkFastestCoefficient -
            usedMs / 1000.0
    }

    private fun activeUsedMs(room: Room, playerIndex: Int, event: Int, start: Long, end: Long): Long {
        if (start <= 0L) return 0L
        val linkData = room.linkData!!
        val now = System.currentTimeMillis()
        val stop = if (event == 3 && end > 0L) end else now
        val elapsed = (stop - start).coerceAtLeast(0L)
        val step = currentStepOf(linkData, playerIndex)
        val cd = room.actualCdTime[playerIndex].coerceAtLeast(0L)
        val completedCd = ((if (event == 3) step - 1 else step - 1).coerceAtLeast(0)) * cd
        val lastGet = if (playerIndex == 0) linkData.lastGetTimeA else linkData.lastGetTimeB
        val currentCd = if (event == 3 || step <= 0 || lastGet <= 0L) {
            0L
        } else {
            (stop - lastGet).coerceIn(0L, cd)
        }
        return (elapsed - completedCd - currentCd).coerceAtLeast(0L)
    }

    private fun routeOf(linkData: LinkData, playerIndex: Int): ArrayList<Int> =
        if (playerIndex == 0) linkData.linkIdxA else linkData.linkIdxB

    private fun skippedRouteOf(linkData: LinkData, playerIndex: Int): ArrayList<Int> =
        if (playerIndex == 0) linkData.skippedIdxA else linkData.skippedIdxB

    private fun currentStepOf(linkData: LinkData, playerIndex: Int): Int =
        if (playerIndex == 0) linkData.currentStepA else linkData.currentStepB

    private fun skipUsedOf(linkData: LinkData, playerIndex: Int): Int =
        if (playerIndex == 0) linkData.skipUsedA else linkData.skipUsedB

    private fun setSkipUsed(linkData: LinkData, playerIndex: Int, value: Int) {
        if (playerIndex == 0) linkData.skipUsedA = value else linkData.skipUsedB = value
    }

    private fun isRouteConfirmed(linkData: LinkData, playerIndex: Int): Boolean =
        if (playerIndex == 0) linkData.routeConfirmedA else linkData.routeConfirmedB

    private fun startIndex(room: Room, playerIndex: Int): Int =
        if (playerIndex == 0) room.roomConfig.linkStartA else room.roomConfig.linkStartB

    private fun endIndex(room: Room, playerIndex: Int): Int =
        if (playerIndex == 0) room.roomConfig.linkEndA else room.roomConfig.linkEndB

    private fun neighbors(room: Room, index: Int): List<Int> = when (room.roomConfig.linkConnectivity) {
        4 -> prioritizedNeighbors(room.boardSpec, index, false)
        else -> prioritizedNeighbors(room.boardSpec, index, true)
    }

    private fun neighbors(board: BoardSpec, index: Int): List<Int> = prioritizedNeighbors(board, index, true)

    private fun prioritizedNeighbors(board: BoardSpec, index: Int, includeDiagonal: Boolean): List<Int> {
        val row = board.row(index)
        val col = board.col(index)
        val offsets = if (includeDiagonal) {
            listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1, -1 to -1, -1 to 1, 1 to -1, 1 to 1)
        } else {
            listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
        }
        return offsets.mapNotNull { (dr, dc) ->
            val nr = row + dr
            val nc = col + dc
            if (nr in 0 until board.size && nc in 0 until board.size) board.index(nr, nc) else null
        }
    }

    private fun completeRouteIfNeeded(room: Room, playerIndex: Int) {
        val linkData = room.linkData!!
        val route = routeOf(linkData, playerIndex)
        val end = endIndex(room, playerIndex)
        if (route.last() == end) return
        val disabled = linkData.disabledIdx.toSet()
        while (route.size > 1) {
            val suffix = shortestPath(room, route.last(), end, disabled, route.dropLast(1).toSet())
            if (suffix != null) {
                route.addAll(suffix.drop(1))
                return
            }
            route.removeAt(route.lastIndex)
        }
        val suffix = shortestPath(room, route.last(), end, disabled, emptySet())
            ?: throw HandlerException(if (playerIndex == 0) "左侧路线不可达" else "右侧路线不可达")
        route.addAll(suffix.drop(1))
    }

    private fun shortestPath(room: Room, start: Int, end: Int, disabled: Set<Int>, occupied: Set<Int>): List<Int>? {
        val blocked = disabled + occupied
        val queue: Queue<Int> = LinkedList()
        val prev = HashMap<Int, Int>()
        val seen = mutableSetOf(start)
        queue.add(start)
        while (queue.isNotEmpty()) {
            val cur = queue.remove()
            if (cur == end) {
                val path = ArrayList<Int>()
                var p = cur
                path.add(p)
                while (p != start) {
                    p = prev[p] ?: return null
                    path.add(p)
                }
                path.reverse()
                return path
            }
            for (next in neighbors(room, cur)) {
                if (next in blocked || !seen.add(next)) continue
                prev[next] = cur
                queue.add(next)
            }
        }
        return null
    }

    private fun isReachable(board: BoardSpec, start: Int, end: Int, disabled: Set<Int>): Boolean {
        val queue: Queue<Int> = LinkedList()
        val seen = mutableSetOf(start)
        queue.add(start)
        while (queue.isNotEmpty()) {
            val cur = queue.remove()
            if (cur == end) return true
            for (next in neighbors(board, cur)) {
                if (next !in disabled && seen.add(next)) queue.add(next)
            }
        }
        return false
    }

    private fun mergeStatus(a: Int, b: Int): Int = when {
        a == LEFT_GET.value && b == RIGHT_GET.value -> BOTH_GET.value
        a == LEFT_SELECT.value && b == RIGHT_SELECT.value -> BOTH_SELECT.value
        a == LEFT_GET.value -> LEFT_GET.value
        b == RIGHT_GET.value -> RIGHT_GET.value
        a == LEFT_SELECT.value -> LEFT_SELECT.value
        b == RIGHT_SELECT.value -> RIGHT_SELECT.value
        else -> 0
    }

    private fun pushLinkData(room: Room) {
        room.push("push_link_data", room.linkData!!.encode())
    }
}
