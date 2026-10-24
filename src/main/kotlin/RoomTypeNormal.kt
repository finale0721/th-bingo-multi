package org.tfcc.bingo

import org.tfcc.bingo.message.HandlerException
import org.tfcc.bingo.standard.StandardGame

/**
 * 标准赛（新应用唯一可用的对局类型）。
 *
 * 选卡/收卡/换卡/导播操作由 [StandardGame] 实现；本对象只负责盘面与初始数据生成。
 */
object RoomTypeNormal : RoomType {
    override val name = "标准赛"

    override val canPause = true

    override fun onStart(room: Room) {
        if (room.roomConfig.dualBoard > 0) {
            generateDualBoard(room)
        }
        StandardGame.createState(room)
        if (room.roomConfig.blindSetting > 1) {
            StandardGame.generateBlind(room)
        }
    }

    private fun generateDualBoard(room: Room) {
        if (room.roomConfig.copyBoard) {
            room.spells2 = room.spells!!.copyOf()
            room.refreshManager2 = RefreshSpellManager()
            room.refreshManager2!!.init(SpellConfig.getSpellLeftCache())
            return
        }
        // 差异盘面：先按第一次结果保存，再生成第二套星级并重 roll 第一面
        val starArray = rollSpellsStarArray(
            room.roomConfig.difficulty,
            room.roomConfig.boardSize,
            room.roomConfig.customLevelCount.toIntArray()
        )
        rollSpellCard(room, starArray)
        room.spells2 = room.spells!!.copyOf()
        val spell2RankArray = SimilarBoardGenerator.findMatrixB(
            starArray,
            room.roomConfig.diffLevel,
            room.boardSpec,
            preserveFixedHighLevelLayout = room.roomConfig.difficulty != 6 || room.roomConfig.customLevelCount[5] == 1,
        )
        rollSpellCard(room, spell2RankArray)
        room.refreshManager2 = RefreshSpellManager()
        room.refreshManager2!!.init(SpellConfig.getSpellLeftCache())
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
        throw HandlerException("标准赛请使用 StandardGame")
    }

    override fun handleFinishSpell(room: Room, isHost: Boolean, playerIndex: Int, spellIndex: Int, success: Boolean) {
        throw HandlerException("标准赛请使用 StandardGame")
    }

    override fun pushSpells(room: Room, spellIndex: Int, causer: String) {
        // 标准赛由 StandardGame 按查看者裁剪后推送
    }
}
