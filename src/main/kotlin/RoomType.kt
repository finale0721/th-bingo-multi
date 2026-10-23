package org.tfcc.bingo

import org.apache.logging.log4j.kotlin.logger
import org.tfcc.bingo.message.HandlerException

sealed interface RoomType {
    val name: String

    fun difficultyMode(difficulty: Int?): DifficultyMode = DifficultyMode.NORMAL

    @Throws(HandlerException::class)
    fun rollSpellCard(room: Room, stars: IntArray? = null) {
        if (room.isCustomGame) {
            return
        }
        SpellConfig.fixWeightVar(room.roomConfig.gameWeight.get("weight_balancer") ?: 0)
        SpellConfig.setWeightDict(room.roomConfig.gameWeight)
        val start = System.currentTimeMillis()
        var retryCount = 0
        val boardSize = room.roomConfig.boardSize
        while (true) {
            try {
                if (stars == null) {
                    val starArray = room.type.rollSpellsStarArray(
                        room.roomConfig.difficulty,
                        boardSize,
                        room.roomConfig.customLevelCount.toIntArray()
                    )
                    room.spells = if (room.roomConfig.customCardPoolEnabled) {
                        SpellFactory.drawCustomPoolSpellsWithStar(
                            room.customCardPool,
                            room.roomConfig.games,
                            room.roomConfig.ranks,
                            starArray,
                            boardSize,
                            room.type.difficultyMode(room.roomConfig.difficulty),
                            room.roomConfig.customLevelCount.toIntArray()
                        )
                    } else {
                        room.type.randSpellsWithStar(
                            room.roomConfig.spellCardVersion, room.roomConfig.games,
                            room.roomConfig.ranks, room.roomConfig.difficulty, starArray, boardSize,
                            room.roomConfig.customLevelCount.toIntArray()
                        )
                    }
                } else {
                    room.spells = if (room.roomConfig.customCardPoolEnabled) {
                        SpellFactory.drawCustomPoolSpellsWithStar(
                            room.customCardPool,
                            room.roomConfig.games,
                            room.roomConfig.ranks,
                            stars,
                            boardSize,
                            room.type.difficultyMode(room.roomConfig.difficulty),
                            room.roomConfig.customLevelCount.toIntArray()
                        )
                    } else {
                        room.type.randSpellsWithStar(
                            room.roomConfig.spellCardVersion, room.roomConfig.games,
                            room.roomConfig.ranks, room.roomConfig.difficulty, stars, boardSize,
                            room.roomConfig.customLevelCount.toIntArray()
                        )
                    }
                }
                PickGuarantee.apply(room)
                break
            } catch (e: HandlerException) {
                if (++retryCount >= 10 ||
                    (e.message != "符卡数量不足" && e.message != PickGuarantee.FAIL_MESSAGE)
                ) {
                    logger.error("随符卡失败", e)
                    throw e
                }
            }
        }
        val now = System.currentTimeMillis()
        logger.debug("随符卡耗时：${now - start}")
        /*
        val debugSpells = room.debugSpells
        if (debugSpells != null) { // 测试用强制选符卡
            val roomType = SpellConfig.NORMAL_GAME
            room.spells!!.forEachIndexed { i, _ ->
                if (debugSpells[i] != 0) {
                    SpellConfig.getSpellById(
                        roomType, room.roomConfig.spellCardVersion, debugSpells[i])?.also { room.spells!![i] = it }
                }
            }
        }
         */
        // SpellLog.logRandSpells(room.spells!!, room.type)
    }

    fun initStatus(room: Room) {
        room.started = true
        room.startMs = System.currentTimeMillis()
        room.spellStatus = Array(room.spells!!.size) { SpellStatus.NONE }
        room.spellStatusInPlayerClient = Array(room.players.size) { room.spellStatus!!.map { it.value }.toIntArray() }
        room.locked = true
        room.banPick = null
        room.refreshManager1 = RefreshSpellManager()
        room.refreshManager1!!.init(SpellConfig.getSpellLeftCache())

        // 初始化各选手的实际CD时间
        val baseCdTime = (room.roomConfig.cdTime ?: 0).toLong() * 1000L
        val cdModifierA = (room.roomConfig.cdModifierA ?: 0).toLong() * 1000L
        val cdModifierB = (room.roomConfig.cdModifierB ?: 0).toLong() * 1000L
        room.actualCdTime[0] = (baseCdTime + cdModifierA).coerceAtLeast(1000L) // 最低1秒
        room.actualCdTime[1] = (baseCdTime + cdModifierB).coerceAtLeast(1000L) // 最低1秒
        // 最高为基础值的3倍
        room.actualCdTime[0] = room.actualCdTime[0].coerceAtMost(baseCdTime * 3)
        room.actualCdTime[1] = room.actualCdTime[1].coerceAtMost(baseCdTime * 3)
    }

    fun resetData(room: Room) {
        room.spells = null
        room.spells2 = null
        room.normalData = null
        room.bpData = null
        room.linkData = null
        room.phase = 0
        room.isCustomGame = false
    }

    fun setUp(room: Room) {}

    fun onStart(room: Room)

    @Throws(HandlerException::class)
    fun handleNextRound(room: Room)

    val canPause: Boolean

    @Throws(HandlerException::class)
    fun randSpells(
        spellCardVersion: Int,
        games: Array<String>,
        ranks: Array<String>,
        difficulty: Int?,
        boardSize: Int = 5,
        customSettings: IntArray? = null,
    ): Array<Spell>

    @Throws(HandlerException::class)
    fun randSpellsWithStar(
        spellCardVersion: Int,
        games: Array<String>,
        ranks: Array<String>,
        difficulty: Int?,
        stars: IntArray?,
        boardSize: Int = 5,
        customSettings: IntArray? = null,
    ): Array<Spell> {
        return randSpells(spellCardVersion, games, ranks, difficulty, boardSize, customSettings)
    }

    fun rollSpellsStarArray(difficulty: Int?, boardSize: Int = 5, customSettings: IntArray? = null): IntArray {
        return intArrayOf()
    }

    /**
     * @param banSelect true-ban操作，false-选卡操作
     */
    @Throws(HandlerException::class)
    fun handleSelectSpell(room: Room, playerIndex: Int, spellIndex: Int)

    @Throws(HandlerException::class)
    fun handleFinishSpell(room: Room, isHost: Boolean, playerIndex: Int, spellIndex: Int, success: Boolean)

    /** 向房间内所有玩家推送符卡状态 */
    fun pushSpells(room: Room, spellIndex: Int, causer: String)

    /**
     * 获取所有符卡状态
     *
     * @param playerIndex 0:左侧玩家，1:右侧玩家，-1:不是玩家
     */
    fun getAllSpellStatus(room: Room, playerIndex: Int): List<Int> {
        return room.spellStatus!!.map { it.value }
    }

    /** 有符卡的状态被直接设定时，应如何进行后处理 */
    fun updateSpellStatusPostProcesser(
        room: Room,
        player: Player,
        spellIndex: Int,
        prevStatus: SpellStatus,
        status: SpellStatus
    ) {
    }
}
