package org.tfcc.bingo

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.tfcc.bingo.SpellStatus.*
import org.tfcc.bingo.message.HandlerException
import org.tfcc.bingo.message.NormalData
import java.util.concurrent.ThreadLocalRandom
import kotlin.random.asKotlinRandom

object RoomTypeNormal : RoomType {
    override val name = "标准赛"

    override val canPause = true

    var spellStatusBackup = emptyArray<SpellStatus>()

    override fun onStart(room: Room) {
        spellStatusBackup = Array(room.boardArea) { NONE }
        room.normalData = NormalData.create(room.boardSpec)
        room.spells2 = emptyArray()
        if (room.roomConfig.dualBoard > 0) {
            handleDualExistRandomCardSettings(room)
        }
        if (room.roomConfig.blindSetting > 1) {
            handleBlindSettings(room)
        }
        // 6x6 extra lines — single copy shared by both boards
        if (room.boardSpec.size == 6 && room.roomConfig.extraLineCount > 0) {
            val rand = ThreadLocalRandom.current().asKotlinRandom()
            try {
                room.normalData!!.extraLines = room.boardSpec.generateExtraLines(
                    room.roomConfig.extraLineCount, rand
                )
            } catch (e: IllegalStateException) {
                throw HandlerException("额外连线生成失败：${e.message}")
            }
        }

        if (room.roomConfig.useAI && room.players[1]!!.name.equals(Store.ROBOT_NAME)) {
            if (room.roomConfig.blindSetting > 1 || room.roomConfig.dualBoard > 0) {
                throw HandlerException("AI陪练模式不支持盲盒或双重盘面")
            }
            if (room.roomConfig.boardSize != 5) {
                throw HandlerException("AI陪练模式仅支持5x5")
            }
            room.aiAgent = AIAgent(room)
            room.aiAgent?.start()
        }
    }

    private fun handleBlindSettings(room: Room) {
        room.spellStatus = Array(room.spells!!.size) { BOTH_HIDDEN }
        val board = room.boardSpec
        val allIndices = (0 until board.area).toMutableList()
        val rand = ThreadLocalRandom.current().asKotlinRandom()
        allIndices.shuffle(rand)

        if (room.roomConfig.blindSetting == 2) {
            // leftOnly, rightOnly, bothSee — 5x5 base counts, scaled by board area
            val reveal = BlindRevealProfile.normal(
                room.roomConfig.blindSetting,
                board.size,
                room.roomConfig.blindRevealLevel
            )
            val leftCount = reveal.first.coerceIn(0, board.area)
            val rightCount = reveal.second.coerceIn(0, board.area)
            val bothCount = reveal.both.coerceIn(0, board.area)
            var idx = 0
            for (i in 0 until leftCount) {
                room.spellStatus!![allIndices[idx++]] = LEFT_SEE_ONLY
            }
            for (i in 0 until rightCount) {
                room.spellStatus!![allIndices[idx++]] = RIGHT_SEE_ONLY
            }
            for (i in 0 until bothCount) {
                room.spellStatus!![allIndices[idx++]] = NONE
            }
        } else if (room.roomConfig.blindSetting == 3) {
            // gameOnly, stageOnly, bothReveal — 5x5 base counts, scaled by board area
            val reveal = BlindRevealProfile.normal(
                room.roomConfig.blindSetting,
                board.size,
                room.roomConfig.blindRevealLevel
            )
            val gameCount = reveal.first.coerceIn(0, board.area)
            val stageCount = reveal.second.coerceIn(0, board.area)
            val bothCount = reveal.both.coerceIn(0, board.area)
            var idx = 0
            for (i in 0 until gameCount) {
                room.spellStatus!![allIndices[idx++]] = ONLY_REVEAL_GAME
            }
            for (i in 0 until stageCount) {
                room.spellStatus!![allIndices[idx++]] = ONLY_REVEAL_GAME_STAGE
            }
            for (i in 0 until bothCount) {
                room.spellStatus!![allIndices[idx++]] = NONE
            }
        }
        room.spellStatus!!.forEachIndexed { index, status -> spellStatusBackup[index] = status }
    }

    private fun handleDualExistRandomCardSettings(room: Room) {
        // rewrite the roll spell logic. We generate spellStarArray by individual calls
        val starArray = rollSpellsStarArray(
            room.roomConfig.difficulty,
            room.roomConfig.boardSize,
            room.roomConfig.customLevelCount.toIntArray()
        )
        rollSpellCard(room, starArray)
        // only room.spells can be assigned in rollSpellCard, so spells2 can only copy from spells
        room.spells2 = room.spells!!.copyOf()
        // generate another starArray: diffLevel -1 = random, 1..5 = tiered similarity
        val spell2RankArray = SimilarBoardGenerator.findMatrixB(
            starArray,
            room.roomConfig.diffLevel,
            room.boardSpec,
            preserveFixedHighLevelLayout = room.roomConfig.difficulty != 6 || room.roomConfig.customLevelCount[5] == 1,
        )
        // reassign room.spells
        rollSpellCard(room, spell2RankArray)
        room.refreshManager2 = RefreshSpellManager()
        room.refreshManager2!!.init(SpellConfig.getSpellLeftCache())

        // 传送格设定：全盘均匀随机
        val rand = ThreadLocalRandom.current().asKotlinRandom()
        val board = room.boardSpec
        val portalCount = room.roomConfig.portalCount
        val allIndices = (0 until board.area).toMutableList()

        allIndices.shuffle(rand)
        for (i in 0 until portalCount) {
            room.normalData!!.isPortalA[allIndices[i]] = 1
        }

        allIndices.shuffle(rand)
        for (i in 0 until portalCount) {
            room.normalData!!.isPortalB[allIndices[i]] = 1
        }
    }

    override fun handleNextRound(room: Room) {
        throw HandlerException("不支持下一回合的游戏类型")
    }

    override fun difficultyMode(difficulty: Int?): DifficultyMode = when {
        difficulty == 6 -> DifficultyMode.CUSTOM
        difficulty != null && difficulty >= 4 -> DifficultyMode.OD
        else -> DifficultyMode.NORMAL
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
        val mode = difficultyMode(difficulty)
        val diffObj = when (mode) {
            DifficultyMode.NORMAL -> when (difficulty) {
                1 -> Difficulty.E
                2 -> Difficulty.N
                3 -> Difficulty.L
                else -> Difficulty.L
            }
            else -> null
        }
        return SpellFactory.drawSpells(
            mode, spellCardVersion, games, ranks, difficulty,
            boardSize = boardSize,
            difficultyObj = diffObj,
            customSettings = customSettings,
        )
    }

    override fun rollSpellsStarArray(difficulty: Int?, boardSize: Int, customSettings: IntArray?): IntArray {
        val mode = difficultyMode(difficulty)
        val diffObj = when (mode) {
            DifficultyMode.NORMAL -> when (difficulty) {
                1 -> Difficulty.E
                2 -> Difficulty.N
                3 -> Difficulty.L
                else -> Difficulty.L
            }
            else -> null
        }
        return SpellFactory.buildStarArray(
            mode, difficulty, difficultyObj = diffObj,
            boardSize = boardSize,
            customSettings = customSettings,
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
        val mode = difficultyMode(difficulty)
        return SpellFactory.drawSpellsWithStar(
            mode, spellCardVersion, games, ranks, stars, boardSize, customSettings
        )
    }

    override fun handleSelectSpell(room: Room, playerIndex: Int, spellIndex: Int) {
        var st = room.spellStatus!![spellIndex]
        if (playerIndex == 1) st = st.opposite()
        room.pauseBeginMs == 0L || throw HandlerException("暂停中，不能操作")
        var now = System.currentTimeMillis()
        val gameTime = room.roomConfig.gameTime.toLong() * 60000L
        val countdown = room.roomConfig.countdown.toLong() * 1000
        if (room.startMs <= now - gameTime - countdown - room.totalPauseMs &&
            !room.isHost(room.players[playerIndex]!!) && !room.scoreDraw()
        )
            throw HandlerException("游戏时间到")
        if (now < room.startMs + countdown) {
            if (st == RIGHT_SELECT) throw HandlerException("倒计时阶段不能抢卡")
        }
        // 选卡CD
        val cdTime = room.actualCdTime[playerIndex] // 使用各选手的实际CD时间
        if (cdTime > 0) {
            val lastGetTime = room.lastGetTime[playerIndex]
            val nextCanSelectTime = lastGetTime + (cdTime - 1000L) // 服务器扛一秒，以防网络延迟
            val remainSelectTime = nextCanSelectTime - now
            if (remainSelectTime > 0)
                throw HandlerException("还有${remainSelectTime / 1000 + 1}秒才能选卡")
        }

        room.spellStatus!![spellIndex] = when (st) {
            LEFT_GET -> throw HandlerException("你已打完")
            RIGHT_GET -> throw HandlerException("对方已打完")
            NONE -> LEFT_SELECT
            LEFT_SELECT -> throw HandlerException("重复选卡")
            BOTH_SELECT, RIGHT_SELECT -> BOTH_SELECT
            BOTH_HIDDEN, LEFT_SEE_ONLY, RIGHT_SEE_ONLY -> LEFT_SELECT
            ONLY_REVEAL_GAME, ONLY_REVEAL_GAME_STAGE, ONLY_REVEAL_STAR -> LEFT_SELECT
            else -> throw HandlerException("状态错误：$st")
        }.run { if (playerIndex == 1) opposite() else this }

        if (room.roomConfig.useAI) {
            if (playerIndex == 0) room.aiAgent?.onOpponentSelectedCell(spellIndex)
        }

        if (room.roomConfig.dualBoard <= 0) {
            room.gameLogger?.logAction(
                player = room.players[playerIndex]!!,
                actionType = "select",
                spellIndex = spellIndex,
                spell = room.spells!![spellIndex],
            )
            return
        }
        val board = if (playerIndex == 0) room.normalData!!.whichBoardA else room.normalData!!.whichBoardB
        room.gameLogger?.logAction(
            player = room.players[playerIndex]!!,
            actionType = "select",
            spellIndex = spellIndex,
            spell = if (board == 0) room.spells!![spellIndex] else room.spells2!![spellIndex],
        )

        return
        /*
        // 无导播模式不记录
        room.host != null || return
        // 等操作结束后再记录
        if (now < room.startMs + countdown) {
            // 倒计时没结束，需要按照倒计时已经结束的时间点计算开始收卡的时间
            now = room.startMs + countdown
        }
        val playerName = room.players[playerIndex]!!.name
        var status = LEFT_SELECT
        if (playerIndex == 1) status = status.opposite()
        SpellLog.logSpellOperate(status, room.spells!![spellIndex], playerName, now, SpellLog.GameType.NORMAL)
         */
    }

    override fun handleFinishSpell(room: Room, isHost: Boolean, playerIndex: Int, spellIndex: Int, success: Boolean) {
        success || throw HandlerException("标准赛不支持收卡失败的操作")
        playerIndex >= 0 || throw HandlerException("只有玩家才能主动收卡")
        var st = room.spellStatus!![spellIndex]
        if (playerIndex == 1) st = st.opposite()
        room.pauseBeginMs == 0L || throw HandlerException("暂停中，不能操作")
        var now = System.currentTimeMillis()
        val gameTime = room.roomConfig.gameTime.toLong() * 60000L
        val countdown = room.roomConfig.countdown.toLong() * 1000
        if (room.startMs <= now - gameTime - countdown - room.totalPauseMs &&
            !room.isHost(room.players[playerIndex]!!) && !room.scoreDraw()
        )
            throw HandlerException("游戏时间到")
        if (now < room.startMs + countdown) {
            throw HandlerException("倒计时还没结束")
        }

        room.spellStatus!![spellIndex] = when (st) {
            LEFT_GET -> throw HandlerException("你已打完")
            RIGHT_GET -> throw HandlerException("对方已打完")
            NONE, RIGHT_SELECT -> throw HandlerException("你还未选卡")
            BOTH_SELECT, LEFT_SELECT -> LEFT_GET
            BOTH_HIDDEN, LEFT_SEE_ONLY, RIGHT_SEE_ONLY -> throw HandlerException("你还未选卡")
            ONLY_REVEAL_GAME, ONLY_REVEAL_GAME_STAGE, ONLY_REVEAL_STAR -> throw HandlerException("你还未选卡")
            else -> throw HandlerException("状态错误：$st")
        }.run { if (playerIndex == 1) opposite() else this }

        room.lastGetTime[playerIndex] = now // 更新上次收卡时间

        if (room.roomConfig.useAI) {
            if (playerIndex == 0) room.aiAgent?.onOpponentFinishedCell(spellIndex)
        }

        if (room.roomConfig.dualBoard <= 0) {
            room.gameLogger?.logAction(
                player = room.players[playerIndex]!!,
                actionType = if (st == BOTH_SELECT) "contest_win" else "finish",
                spellIndex = spellIndex,
                spell = room.spells!![spellIndex],
            )
            return
        }

        // 记录是谁在哪个盘面上收取的
        val boardIndex = if (playerIndex == 0) room.normalData!!.whichBoardA else room.normalData!!.whichBoardB
        room.normalData!!.getOnWhichBoard[spellIndex] = when (playerIndex * 2 + boardIndex) {
            0 -> 0x1
            1 -> 0x2
            2 -> 0x10
            3 -> 0x20
            else -> throw HandlerException("错误的收取盘面记录")
        }
        // 日志记录
        val board = if (playerIndex == 0) room.normalData!!.whichBoardA else room.normalData!!.whichBoardB
        room.gameLogger?.logAction(
            player = room.players[playerIndex]!!,
            actionType = if (st == BOTH_SELECT) "contest_win" else "finish",
            spellIndex = spellIndex,
            spell = if (board == 0) room.spells!![spellIndex] else room.spells2!![spellIndex],
        )

        // 如果是传送门格，更改该玩家的盘面
        // 如果是A玩家在A面收取一张A传送门卡，或者...
        if (playerIndex == 0) {
            if (room.normalData!!.whichBoardA == 0 && room.normalData!!.isPortalA[spellIndex] > 0)
                room.normalData!!.whichBoardA = 1
            else if (room.normalData!!.whichBoardA == 1 && room.normalData!!.isPortalB[spellIndex] > 0)
                room.normalData!!.whichBoardA = 0
        } else if (playerIndex == 1) {
            if (room.normalData!!.whichBoardB == 0 && room.normalData!!.isPortalA[spellIndex] > 0)
                room.normalData!!.whichBoardB = 1
            else if (room.normalData!!.whichBoardB == 1 && room.normalData!!.isPortalB[spellIndex] > 0)
                room.normalData!!.whichBoardB = 0
        }
        // Finish Spell 会调用pushSpell推送盘面更改结果，视觉改变交由前端处理

        return
        /*
        // 无导播模式不记录
        room.host != null || return
        // 等操作结束后再记录
        if (now < room.startMs + countdown) {
            // 倒计时没结束，需要按照倒计时已经结束的时间点计算开始收卡的时间
            now = room.startMs + countdown
        }
        val playerName = room.players[playerIndex]!!.name
        var status = LEFT_GET
        if (playerIndex == 1) status = status.opposite()
        SpellLog.logSpellOperate(status, room.spells!![spellIndex], playerName, now, SpellLog.GameType.NORMAL)
         */
    }

    /**
     * 收了一定数量的卡之后，隐藏对方的选卡
     */
    private fun formatSpellStatus(room: Room, status: SpellStatus, playerIndex: Int): Int {
        var st = status
        if (st.isSelectStatus()) {
            if ((room.roomConfig.reservedType ?: 0) == 0) {
                // 个人赛对方达到隐藏阈值后，不再可以看到对方的选卡
                if (playerIndex == 0 && shouldHideSelectionsOf(room, 1)) {
                    if (status == RIGHT_SELECT) st = NONE
                    else if (status == BOTH_SELECT) st = LEFT_SELECT
                } else if (playerIndex == 1 && shouldHideSelectionsOf(room, 0)) {
                    if (status == LEFT_SELECT) st = NONE
                    else if (status == BOTH_SELECT) st = RIGHT_SELECT
                }
            } else if (
                (playerIndex == 0 && shouldHideSelectionsOf(room, 1)) ||
                (playerIndex == 1 && shouldHideSelectionsOf(room, 0))
            ) {
                // 团体赛对方达到隐藏阈值后，不再可以看到对方的选卡
                if (playerIndex == 0) {
                    if (status == RIGHT_SELECT) st = NONE
                    else if (status == BOTH_SELECT) st = LEFT_SELECT
                } else if (playerIndex == 1) {
                    if (status == LEFT_SELECT) st = NONE
                    else if (status == BOTH_SELECT) st = RIGHT_SELECT
                }
            }
        }
        return st.value
    }

    /**
     * 仅对选手生效
     * 收了一定数量的卡之后，隐藏对方的选卡
     * 前五张对方的选卡不是HIDDEN状态，只要选择就是双方可见状态
     * 而五张之后对方选卡不可见，若处于自己视野之外则为HIDDEN，否则为NONE。
     */
    private fun formatSpellStatus2(room: Room, status: SpellStatus, playerIndex: Int, spellIndex: Int): Int {
        var st = status
        // 如果是对称的可见情况，隐藏选卡
        if (st.isSelectStatus()) {
            if ((room.roomConfig.reservedType ?: 0) == 0) {
                // 个人赛对方达到隐藏阈值后，不再可以看到对方的选卡
                if (playerIndex == 0 && shouldHideSelectionsOf(room, 1)) {
                    if (status == RIGHT_SELECT)
                        st = decideStatus(room, spellIndex, false)
                    else if (status == BOTH_SELECT) st = LEFT_SELECT
                } else if (playerIndex == 1 && shouldHideSelectionsOf(room, 0)) {
                    if (status == LEFT_SELECT)
                        st = decideStatus(room, spellIndex, true)
                    else if (status == BOTH_SELECT) st = RIGHT_SELECT
                }
            } else if (
                (playerIndex == 0 && shouldHideSelectionsOf(room, 1)) ||
                (playerIndex == 1 && shouldHideSelectionsOf(room, 0))
            ) {
                // 团体赛对方达到隐藏阈值后，不再可以看到对方的选卡
                if (playerIndex == 0) {
                    if (status == RIGHT_SELECT)
                        st = decideStatus(room, spellIndex, false)
                    else if (status == BOTH_SELECT) st = LEFT_SELECT
                } else if (playerIndex == 1) {
                    if (status == LEFT_SELECT)
                        st = decideStatus(room, spellIndex, true)
                    else if (status == BOTH_SELECT) st = RIGHT_SELECT
                }
            }
        }
        // 如果是不对称的可见情况，将当前能看到的改为NONE，否则为HIDDEN
        if (st == LEFT_SEE_ONLY) {
            st = if (playerIndex == 0) NONE else BOTH_HIDDEN
        } else if (st == RIGHT_SEE_ONLY) {
            st = if (playerIndex == 1) NONE else BOTH_HIDDEN
        }
        return st.value
    }

    private fun decideStatus(room: Room, spellIndex: Int, isLeftSelect: Boolean): SpellStatus {
        if (room.roomConfig.blindSetting == 2) {
            if (spellStatusBackup[spellIndex] == NONE ||
                (spellStatusBackup[spellIndex] == LEFT_SEE_ONLY && !isLeftSelect) ||
                (spellStatusBackup[spellIndex] == RIGHT_SEE_ONLY && isLeftSelect)
            )
                return NONE
            else return BOTH_HIDDEN
        } else if (room.roomConfig.blindSetting == 3) {
            return spellStatusBackup[spellIndex]
        }
        return NONE
    }

    private fun defaultHiddenSelectThreshold(boardSize: Int): Int {
        return when (boardSize) {
            4 -> 3
            6 -> 7
            else -> 5
        }
    }

    private fun hiddenSelectThreshold(room: Room, playerIndex: Int): Int {
        return when (playerIndex) {
            0 -> room.roomConfig.hiddenSelectThresholdA
            1 -> room.roomConfig.hiddenSelectThresholdB
            else -> null
        } ?: defaultHiddenSelectThreshold(room.boardSpec.size)
    }

    private fun shouldHideSelectionsOf(room: Room, playerIndex: Int): Boolean {
        val getStatus = if (playerIndex == 0) LEFT_GET else RIGHT_GET
        return room.spellStatus!!.count { it == getStatus } >= hiddenSelectThreshold(room, playerIndex)
    }

    override fun pushSpells(room: Room, spellIndex: Int, causer: String) {
        val status = room.spellStatus!![spellIndex]
        val allStatus = JsonObject(
            mapOf(
                "index" to JsonPrimitive(spellIndex),
                "status" to JsonPrimitive(status.value),
                "causer" to JsonPrimitive(causer),
                // 收取符卡后可能改变：玩家所处的版面、收取记录
                "which_board_a" to JsonPrimitive(room.normalData!!.whichBoardA),
                "which_board_b" to JsonPrimitive(room.normalData!!.whichBoardB),
                "get_on_which_board" to JsonPrimitive(room.normalData!!.getOnWhichBoard[spellIndex]),
            )
        )
        room.host?.push("push_update_spell_status", allStatus)
        for (i in room.players.indices) {
            val oldStatus = room.spellStatusInPlayerClient!![i][spellIndex]
            val newStatus = if (room.roomConfig.blindSetting == 1) formatSpellStatus(room, status, i)
            else formatSpellStatus2(room, status, i, spellIndex)
            if (oldStatus != newStatus) {
                room.players[i]?.push(
                    "push_update_spell_status", JsonObject(
                        mapOf(
                            "index" to JsonPrimitive(spellIndex),
                            "status" to JsonPrimitive(newStatus),
                            "causer" to JsonPrimitive(causer),
                            "which_board_a" to JsonPrimitive(room.normalData!!.whichBoardA),
                            "which_board_b" to JsonPrimitive(room.normalData!!.whichBoardB),
                            "get_on_which_board" to JsonPrimitive(room.normalData!!.getOnWhichBoard[spellIndex]),
                        )
                    )
                )
                room.spellStatusInPlayerClient!![i][spellIndex] = newStatus
            }
        }
        room.watchers.forEach { it.push("push_update_spell_status", allStatus) }
    }

    override fun getAllSpellStatus(room: Room, playerIndex: Int): List<Int> {
        if (playerIndex == -1)
            return super.getAllSpellStatus(room, playerIndex)
        return if (room.roomConfig.blindSetting == 1)
            room.spellStatus!!.map { formatSpellStatus(room, it, playerIndex) }
        else room.spellStatus!!.mapIndexed { index, status -> formatSpellStatus2(room, status, playerIndex, index) }
    }

    override fun updateSpellStatusPostProcesser(
        room: Room,
        player: Player,
        spellIndex: Int,
        prevStatus: SpellStatus,
        status: SpellStatus
    ) {
        // 盲盒模式1中，单方面选择并取消选卡会使符卡回归初始状态
        if (room.roomConfig.blindSetting == 2) {
            if (status == NONE && prevStatus.isOneSelectStatus()) {
                room.spellStatus!![spellIndex] = spellStatusBackup[spellIndex]
            }
        }
        // 盲盒模式2中，不允许出现NONE状态。翻回去的牌返回初始状态（如果不是BOTH_SELECT）
        if (room.roomConfig.blindSetting == 3) {
            if (status == NONE && prevStatus.isOneSelectStatus()) {
                room.spellStatus!![spellIndex] = spellStatusBackup[spellIndex]
            }
        }
    }

    /** 是否平局 */
    private fun Room.scoreDraw(): Boolean {
        var left = 0
        spellStatus!!.forEach {
            if (it == LEFT_GET) left++
            else if (it == RIGHT_GET) left--
        }
        return left == 0
    }
}
