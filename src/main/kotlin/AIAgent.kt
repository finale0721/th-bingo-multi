package org.tfcc.bingo

import org.apache.logging.log4j.kotlin.logger
import org.tfcc.bingo.message.HandlerException
import java.lang.Float.min
import java.util.concurrent.*
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * AI陪练Agent
 * 实现了分级、分风格的智能决策逻辑，用于在PVE模式下模拟人类玩家。
 * 其核心决策基于“等效时间价值”（ETV）模型，旨在最大化每一步行动带来的时间优势。
 * @param room 对游戏房间的引用，用于读取状态和执行操作。
 */
class AIAgent(private val room: Room) {

    // <editor-fold desc="AIAgent Core Functionality">
    // --- 日志记录器 ---
    private val logger = logger()

    // --- 内部状态与配置 ---
    /** AI的内部状态机，控制其行为模式。 */
    private enum class AIState { IDLE, DECIDING, ATTEMPTING, COOLDOWN, PAUSED }

    private var currentState: AIState = AIState.IDLE
        set(value) {
            if (field != value) {
                logger.info("AI State: $field -> $value")
                field = value
            }
        }

    /** 存储棋盘上每个格子的AI相关参数模型。 */
    private data class GridModel(
        val index: Int, // 棋盘位置索引 (0-24)
        var baseTime: Float, // 理想完成时间
        var penalty: Float, // 失败惩罚时间
        var calculatedAI: Float = 0f, // AI实际尝试时间
        var calculatedPI: Float = 0f, // AI成功率
        var expectedTime: Float = 0f, // 综合期望时间
    )

    private lateinit var gridModels: List<GridModel>

    /** 描述AI当前正在执行的任务。 */
    private data class AITask(
        val targetIndex: Int,
        val startTimeMs: Long,
        val durationMs: Long,
        val isSuccess: Boolean, // 任务开始时即确定成败
    )

    private var failedTask = -1

    private var currentTask: AITask? = null

    /** 动态记录和评估对手的画像。 */
    private data class OpponentProfile(
        var avgEfficiency: Double = 0.66, // 对手效率指数
        var lastSelectionTimeMs: Long = 0,
        var lastSelectedIndex: Int = -1
    )

    private val opponentProfile = OpponentProfile()

    private var remainingAbandons: Int = 2
    private val aiPlayerIndex = 1
    private val humanPlayerIndex = 0

    /** 用于外部系统强制设定AI选择的volatile变量。 */
    @Volatile
    private var externallySetSelection: Int? = null

    // 从房间配置中读取AI的策略等级和风格
    private val strategyLevel = room.roomConfig.aiStrategyLevel
    private val style = room.roomConfig.aiStyle

    /** AI的独立线程池，用于执行其周期性的决策循环。 */
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    // --- 决策常量 ---
    /** 防守价值的基础权重。 */
    private val defenseWeight = 1.25

    /** 在争夺中，AI需要比对手快多少才认为有把握（80%）。 */
    private val safetyMarginDefault = 0.8

    /** 放弃或被抢卡后，模拟切换题目所需的固定时间惩罚。 */
    private val abandonPenaltyMs = 10000L

    /** 在信息不确定的情况下，对预测冲突的格子的价值应用的惩罚系数。 */
    private val uncertainPenaltyFactor = 0.7

    // 形成X连的固定奖励
    private val line2Bonus = 3.0
    private val line3Bonus = 9.0
    private val line4Bonus = 12.0

    /** 棋盘上所有有效的胜利线路。 */
    private val boardLines: List<List<Int>> = room.boardSpec.winningLines()

    // --- 公共接口 ---
    /** 启动AI Agent，初始化并开始决策循环。 */
    fun start() {
        logger.info("AIAgent for room ${room.roomId} starting...")
        initializeGridModels()
        executor.scheduleAtFixedRate(this::tick, 0, 500, TimeUnit.MILLISECONDS)
    }

    /** 停止AI Agent，关闭线程池。 */
    fun stop() {
        logger.info("AIAgent for room ${room.roomId} stopping...")
        executor.shutdownNow()
    }

    /** 当游戏暂停时调用，AI将暂停其当前活动。 */
    fun onGamePaused() {
        logger.info("Game paused. AI is pausing its task.")
        if (currentState == AIState.ATTEMPTING) {
            currentState = AIState.PAUSED
        }
    }

    /** 当游戏恢复时调用，AI将重新验证并继续其任务。 */
    fun onGameResumed() {
        logger.info("Game resumed. AI is resuming its task.")
        if (currentState == AIState.PAUSED) {
            val task = currentTask
            if (task != null) {
                // 恢复前重新验证任务的有效性
                val currentCellStatus = room.spellStatus?.get(task.targetIndex)
                if (currentCellStatus == SpellStatus.RIGHT_SELECT || currentCellStatus == SpellStatus.BOTH_SELECT) {
                    currentState = AIState.ATTEMPTING
                } else {
                    logger.warn("Task for cell ${task.targetIndex} became invalid during pause. Aborting.")
                    currentTask = null
                    currentState = AIState.IDLE
                }
            } else {
                currentState = AIState.IDLE
            }
        }
    }

    /** 当对手选择一个格子时调用，更新对手画像。 */
    fun onOpponentSelectedCell(spellIndex: Int) {
        logger.debug("Opponent selected cell $spellIndex")
        opponentProfile.lastSelectedIndex = spellIndex
        opponentProfile.lastSelectionTimeMs = getCorrectedTime()
    }

    /** 当对手完成一个格子时调用，更新对手效率模型。 */
    fun onOpponentFinishedCell(spellIndex: Int) {
        if (opponentProfile.lastSelectedIndex == spellIndex && opponentProfile.lastSelectionTimeMs > 0) {
            val timeTaken = getCorrectedTime() - opponentProfile.lastSelectionTimeMs
            val idealTime = room.spells!![spellIndex].fastest.times(1000)
            if (timeTaken > 0) {
                val efficiency = min(1.5f, idealTime / timeTaken)
                val oldEfficiency = opponentProfile.avgEfficiency
                // 使用移动平均法平滑更新效率指数
                opponentProfile.avgEfficiency = (0.8 * opponentProfile.avgEfficiency) + (0.2 * efficiency)
                logger.debug(
                    "Opponent finished cell $spellIndex. Time: ${timeTaken}ms. " +
                        "Efficiency updated from $oldEfficiency to ${opponentProfile.avgEfficiency}"
                )
            }
        }
        opponentProfile.lastSelectedIndex = -1
        opponentProfile.lastSelectionTimeMs = 0
    }

    /** 监听棋盘状态变化，用于触发对手画像的更新。 */
    fun onCellStatusChanged(index: Int, oldStatus: SpellStatus, newStatus: SpellStatus) {
        logger.trace("Cell $index status changed from $oldStatus to $newStatus")
        val isOpponentNewSelect = (newStatus == SpellStatus.LEFT_SELECT && oldStatus.isEmptyStatus()) ||
            (newStatus == SpellStatus.BOTH_SELECT && oldStatus == SpellStatus.RIGHT_SELECT)
        if (isOpponentNewSelect) onOpponentSelectedCell(index)

        if (newStatus == SpellStatus.LEFT_GET && oldStatus != SpellStatus.LEFT_GET) onOpponentFinishedCell(index)

        if (newStatus == SpellStatus.RIGHT_SELECT) {
            externallySetSelection = index
            if (!executor.isShutdown) executor.execute(this::tick)
        }
    }

    // --- 核心逻辑 ---
    /** AI的主心跳，每100ms执行一次。 */
    private fun tick() {
        try {
            if (currentState == AIState.PAUSED || !isGameRunning()) {
                return
            }
            if (!synchronizeStateAndHandleExternalCommands()) return

            // 放弃决策是最高优先级的检查
            if (shouldAbandon()) {
                performAbandon()
                return
            }

            when (currentState) {
                AIState.IDLE -> runIdleLogic()
                AIState.ATTEMPTING -> runAttemptingLogic()
                AIState.COOLDOWN -> runCooldownLogic()
                else -> {}
            }
        } catch (e: Exception) {
            logger.error("Exception in AI tick", e)
        }
    }

    /** 当AI空闲时，执行决策并选择新目标。 */
    private fun runIdleLogic() {
        currentState = AIState.DECIDING
        logger.debug("AI is deciding its next move...")
        val bestTargetIndex = findBestTarget()
        if (bestTargetIndex != -1) {
            logger.info("AI decided to target cell $bestTargetIndex.")
            val targetStatus = room.spellStatus?.get(bestTargetIndex)
            if (targetStatus == SpellStatus.NONE || targetStatus == SpellStatus.LEFT_SELECT) {
                try {
                    RoomTypeNormal.handleSelectSpell(room, aiPlayerIndex, bestTargetIndex)
                    room.type.pushSpells(room, bestTargetIndex, room.players[aiPlayerIndex]?.name ?: "AI")
                    startNewSelectionTask(bestTargetIndex)
                } catch (e: HandlerException) {
                    logger.warn("AI failed to select cell $bestTargetIndex due to HandlerException: ${e.message}")
                    currentState = AIState.IDLE
                }
            } else {
                logger.warn("AI wanted to select $bestTargetIndex, but its status was $targetStatus. Retrying decision.")
                currentState = AIState.IDLE
            }
        } else {
            logger.warn("AI could not find any valid target. Will remain idle.")
            currentState = AIState.IDLE
        }
    }

    /** 当AI正在尝试一个格子时，检查任务是否完成。 */
    private fun runAttemptingLogic() {
        val task = currentTask ?: run {
            currentState = AIState.IDLE
            return
        }
        val currentCellStatus = room.spellStatus?.get(task.targetIndex)
        if (currentCellStatus != SpellStatus.RIGHT_SELECT && currentCellStatus != SpellStatus.BOTH_SELECT) {
            val reason = "Task on cell ${task.targetIndex} was preempted. Current status: $currentCellStatus."
            applyPreemptionPenalty(reason)
            return // 必须返回，因为任务已失效
        }

        if (getCorrectedTime() >= task.startTimeMs + task.durationMs) {
            logger.debug("Attempt on cell ${task.targetIndex} finished.")
            if (task.isSuccess) {
                logger.info("Attempt on cell ${task.targetIndex} was SUCCESSFUL.")
                try {
                    RoomTypeNormal.handleFinishSpell(room, false, aiPlayerIndex, task.targetIndex, true)
                    room.type.pushSpells(room, task.targetIndex, room.players[aiPlayerIndex]?.name ?: "AI")
                    currentTask = null
                    currentState = AIState.COOLDOWN
                } catch (e: HandlerException) {
                    logger.error("AI failed to finish spell ${task.targetIndex} after successful attempt: ${e.message}")
                    currentTask = null
                    currentState = AIState.IDLE
                }
            } else {
                logger.info("Attempt on cell ${task.targetIndex} FAILED.")
                // 失败后将任务取消，重建任务
                failedTask = task.targetIndex
                currentTask = null
                startNewSelectionTask(failedTask)
            }
        }
    }

    /** 当AI完成一个格子后，等待冷却CD结束。 */
    private fun runCooldownLogic() {
        val cdTimeMs = room.actualCdTime[aiPlayerIndex] // 使用AI的实际CD时间
        if (System.currentTimeMillis() >= room.lastGetTime[aiPlayerIndex] + cdTimeMs) {
            logger.debug("Cooldown finished.")
            currentState = AIState.IDLE
        }
    }

    /** 处理外部指令并同步状态，确保AI内部状态与游戏世界一致。 */
    private fun synchronizeStateAndHandleExternalCommands(): Boolean {
        externallySetSelection?.let { targetSelection ->
            logger.info("External command received to select cell $targetSelection. Overriding current AI task.")
            externallySetSelection = null
            if (currentTask?.targetIndex != targetSelection) {
                cancelCurrentSelection("Overridden by external command")
            }
            startNewSelectionTask(targetSelection)
            return false
        }
        if (currentState == AIState.ATTEMPTING) {
            val task = currentTask
            val actualStatus = room.spellStatus?.get(task?.targetIndex ?: -1)
            if (task != null && actualStatus != SpellStatus.RIGHT_SELECT && actualStatus != SpellStatus.BOTH_SELECT) {
                val reason = "Task on cell ${task.targetIndex} was changed. Current status: $actualStatus."
                applyPreemptionPenalty(reason)
            }
        }
        return true
    }

    /** 根据决策结果，开始一个新的尝试任务。 */
    private fun startNewSelectionTask(targetIndex: Int) {
        val status = room.spellStatus?.get(targetIndex)
        if (status != SpellStatus.RIGHT_SELECT && status != SpellStatus.BOTH_SELECT) {
            logger.warn("Attempted to start task on $targetIndex, but status is now $status. Aborting.")
            currentState = AIState.IDLE
            return
        }
        val grid = gridModels[targetIndex]
        val isSuccess = Random.nextFloat() < grid.calculatedPI
        val duration = (if (isSuccess) grid.calculatedAI else grid.penalty) * 1000
        currentTask = AITask(targetIndex, getCorrectedTime(), duration.toLong(), isSuccess)
        currentState = AIState.ATTEMPTING
        logger.debug(
            "New task started for cell $targetIndex. " +
                "Duration: ${duration}ms. Success pre-determined: $isSuccess. PI: ${grid.calculatedPI}"
        )
    }

    /** 游戏开始时，根据房间配置和AI等级初始化所有格子的参数模型。 */
    private fun initializeGridModels() {
        logger.info("Initializing AI grid models...")
        val logBuilder = StringBuilder("AI Grid Model Initialization Details:\n")
        logBuilder.append("B.Idx| P.ID | Base AI | Penalty | Calc AI | Calc PI | Time Exp\n")

        var totalTimeEst = 0f

        gridModels = room.spells?.mapIndexed { boardIndex, spell ->
            val model = GridModel(boardIndex, spell.fastest, 0f)
            val prefLevel = room.roomConfig.aiPreference[spell.game] ?: 0
            // 基于等级与作品偏向性计算AI的底力与熟练度
            val aiPower = max(room.roomConfig.aiBasePower + 5f, 5f)
            val aief = prefLevel * if (prefLevel > 0) 3f else 4f
            val aiExp = max(room.roomConfig.aiExperience + 5f + aief, 0.0f)
            // 每张卡的底力与熟练度权重。二者和一定为1
            val spellPowerWeight = min(.95f, max(.05f, spell.powerWeight))
            val spellExpWeight = 1f - spellPowerWeight
            // 时间太长的卡认为时间波动忽略不计（多半为时符，或者速攻差异小）。只加上开游戏的时间
            val randFloat1 = (Random.nextFloat() + Random.nextFloat()) / 2f
            val randFloat2 = (Random.nextFloat() + Random.nextFloat()) / 2f
            if (spell.fastest > 60.0f) {
                model.calculatedAI = spell.fastest + 3.5f + 3f * randFloat1
            } else {
                // 否则，给一个较小的随机时间惩罚（形状不好，最多+25%）、并额外 进行熟练度惩罚。
                model.calculatedAI = spell.fastest + 3.5f + randFloat1 * spell.fastest * .25f +
                    randFloat2 * max(6f - aiExp / 4f, 0f)
            }
            // 根据纯难度计算一个最小收率
            var minCapRate = 0f
            if (spell.difficulty < 6.0f) {
                minCapRate = 0.10f
            } else {
                minCapRate = max(0.10f - (spell.difficulty - 6f) * .01f, 0f)
            }
            // 计算基础收率
            var baseCapRate = spell.maxCapRate + if (prefLevel > 0) (1f - spell.maxCapRate) * 0.33f * prefLevel else 0f
            // 底力不足会严重影响收卡效率
            // 我们认为，如果底力与目标值的差异大于2.5，就视为底力不足
            baseCapRate *= exp(-0.5f * max((spell.difficulty - aiPower) * spellPowerWeight - 2.5f, 0f))
            // 我们如此定义：熟练度影响能力的最终发挥表现
            // 熟练度达到最高时，收率可以100%发挥
            // 熟练度最低时，收率只能发挥较少一部分
            // 这个斜率受符卡对熟练度的要求影响。要求由两部分计算得到
            // 第一，底力系数越低，要求越高
            // 第二，对于较难的卡，难度越高，要求越高
            val req = min(
                (1f - spellPowerWeight) * 10f + max(min((spell.difficulty - 8f) / 4f, 2f), 0f),
                9f)
            // b是熟练度最低时的收率 k代表变化率
            val b = 0.9f - req * req * 0.005f - req * 0.045f
            val k = (1f - b) * 0.1f
            // 计算收率修正
            // 这里，我们把熟练度重新映射到0~10的区间上。
            baseCapRate *= k * min(10f, max(0f, aiExp - 5f)) + b
            // 然后根据能力值计算出收率
            baseCapRate = max(baseCapRate - minCapRate, 0f)
            val finalRate =
                baseCapRate / (1f + exp(-.925f * spell.changeRate *
                    (aiPower * spellPowerWeight + aiExp * spellExpWeight - spell.difficulty)
                )) + minCapRate
            // 给收率一个界限
            model.calculatedPI = min(.999f, max(finalRate, .001f))
            // 计算失败的耗时。重开游戏另加1.5秒。
            model.penalty = 1.5f + spell.missTime

            // 期望时间=A+F*(1-P)/P
            model.expectedTime = model.calculatedAI + model.penalty * (1f - model.calculatedPI) / model.calculatedPI
            totalTimeEst += model.expectedTime

            logBuilder.append(
                String.format(
                    " %-3d | %-4d | %-7.2f | %-7.2f | %-7.2f | %.3f | %.2f\n",
                    model.index,
                    spell.index,
                    model.baseTime,
                    model.penalty,
                    model.calculatedAI,
                    model.calculatedPI,
                    model.expectedTime
                )
            )
            model
        } ?: emptyList()
        logger.debug(logBuilder.toString())
        logger.debug("Total time: $totalTimeEst")
    }

    /** 检查游戏是否正在进行中。 */
    private fun isGameRunning(): Boolean {
        val now = System.currentTimeMillis()
        val countdown = room.roomConfig.countdown * 1000L
        val gameTime = room.roomConfig.gameTime * 60000L
        return room.started && now >= room.startMs + countdown && now < room.startMs + countdown + gameTime + room.totalPauseMs
    }

    /** 获取考虑了暂停时间的当前游戏时间戳。 */
    private fun getCorrectedTime(): Long = System.currentTimeMillis() - (room.totalPauseMs +
        if (room.pauseBeginMs > 0) System.currentTimeMillis() - room.pauseBeginMs else 0)

    /** AI决策引擎的状态枚举。 */
    private enum class PlayerState { AI, HUMAN, EMPTY }
    private enum class InitiativeState { CLEAR, NEUTRAL, DANGEROUS }

    /** 决策的入口，根据AI等级分发到不同的决策函数。 */
    private fun findBestTarget(): Int {
        return when (strategyLevel) {
            1 -> findBestTargetBeginner()
            2 -> findBestTargetIntermediate()
            else -> findBestTargetAdvanced()
        }
    }

    /** 初级AI：贪心+紧急防御。只选择最快的格子，除非看到四连。 */
    private fun findBestTargetBeginner(): Int {
        val boardState = getBoardState()

        for (line in boardLines) {
            if (line.count { boardState[it] == PlayerState.EMPTY } == 1) {
                val human4 = line.count { boardState[it] == PlayerState.HUMAN } == 4
                val ai4 = line.count { boardState[it] == PlayerState.AI } == 4
                if (human4 || ai4) {
                    val target = line.first { boardState[it] == PlayerState.EMPTY }
                    logger.info("Beginner AI: Urgent 4-in-a-row detected. Targeting $target.")
                    return target
                }
            }
        }

        return gridModels
            .filter { boardState[it.index] == PlayerState.EMPTY }
            .minByOrNull { it.expectedTime }?.index ?: -1
    }

    /** 中级AI：懂得基础的时间价值和空间布局，但没有对手建模。 */
    private fun findBestTargetIntermediate(): Int {
        val boardState = getBoardState()
        val baselineTime = calculateBaselineTime(boardState)

        var bestCell = -1
        var maxETV = -Double.MAX_VALUE

        // 会优先封堵四连
        for (line in boardLines) {
            if (line.count { boardState[it] == PlayerState.EMPTY } == 1) {
                val human4 = line.count { boardState[it] == PlayerState.HUMAN } == 4
                if (human4) {
                    val target = line.first { boardState[it] == PlayerState.EMPTY }
                    return target
                }
            }
        }
        // 然后计算时间价值
        for (i in gridModels.indices) {
            if (boardState[i] == PlayerState.EMPTY) {
                val etv = calculateEquivalentTimeValue(i, boardState, baselineTime, 0.0, false)
                if (etv > maxETV) {
                    maxETV = etv
                    bestCell = i
                }
            }
        }
        return bestCell
    }

    /** 高级AI：使用完整的ETV模型，包含对手建模、风险评估、冲突规避和战略规划。 */
    private fun findBestTargetAdvanced(): Int {
        val boardState = getBoardState()
        val humanScore = boardState.count { it == PlayerState.HUMAN }
        val isOpponentVisible = humanScore < (room.roomConfig.hiddenSelectThresholdA ?: 5)
        val initiative = calculateInitiativeState()
        val temperature = max(room.roomConfig.aiTemperature, 0f)

        val predictedOpponentTarget = if (!isOpponentVisible && initiative != InitiativeState.CLEAR) {
            predictOpponentTarget(boardState)
        } else {
            -1
        }

        val baselineTime = calculateBaselineTime(boardState)
        val rhythmAdvantage = calculateRhythmAdvantage(boardState, baselineTime)

        data class Strategy(val cell: Int, val etv: Double, val notes: String)
        val strategies = mutableListOf<Strategy>()

        val decisionLog = StringBuilder(
            "AI Decision Matrix (RhythmAdv: ${"%.2f".format(rhythmAdvantage)}" +
                ", Baseline: ${"%.2f".format(baselineTime)}s, " +
                "OpponentEff: ${"%.2f".format(opponentProfile.avgEfficiency)}," +
                " Initiative: $initiative):\n"
        )
        decisionLog.append("Idx | ETV(s) | Notes\n")

        val isFirstSelect = (boardState.count { it == PlayerState.EMPTY } == room.boardArea)

        for (i in gridModels.indices) {
            if (boardState[i] == PlayerState.EMPTY) {
                var notes = ""

                var etv = calculateEquivalentTimeValue(i, boardState, baselineTime, rhythmAdvantage, true)

                if (isOpponentVisible && opponentProfile.lastSelectedIndex == i) {
                    notes += "[CONTESTED]"
                    val safetyMargin = when (style) {
                        1 -> safetyMarginDefault + 0.15 // 我能抢赢！
                        2 -> safetyMarginDefault - 0.15
                        else -> safetyMarginDefault
                    }
                    if (!isWinnableContention(i, true, safetyMargin)) {
                        notes += "[UNWINNABLE]"
                        etv *= 0.4
                    } else {
                        notes += "[WINNABLE]"
                        etv += baselineTime
                    }
                } else if (!isOpponentVisible && predictedOpponentTarget == i) {
                    notes += "[PREDICTED_CONFLICT]"
                    val uncertaintyPenalty = if (style == 1) uncertainPenaltyFactor + 0.1 else uncertainPenaltyFactor
                    etv *= uncertaintyPenalty
                    notes += "[UNCERTAIN_PENALTY_APPLIED]"
                }

                // 禁止抢一选
                if (isFirstSelect) {
                    if (opponentProfile.lastSelectedIndex == i) {
                        etv = -9999.0
                    }
                }

                strategies.add(Strategy(i, etv, notes))
            }
        }

        strategies.forEach {
            decisionLog.append(String.format(" %-2d | %-6.1f | %s\n", it.cell, it.etv, it.notes))
        }
        logger.debug(decisionLog.toString())

        if (strategies.isEmpty()) {
            return -1
        }

        val topStrategies = strategies.sortedByDescending { it.etv }.take(5)

        if (topStrategies.isEmpty()) {
            return -1
        }

        if (temperature < .01f) {
            return topStrategies.first().cell
        }

        val minEtv = topStrategies.last().etv
        val maxEtv = topStrategies.first().etv

        if (minEtv == maxEtv) {
            return topStrategies.random().cell
        }

        val scaledStrategies = topStrategies.map {
            val compressedEtv = sqrt(it.etv - minEtv) / 2.5
            it to compressedEtv
        }

        val maxCompressedEtv = scaledStrategies.first().second
        val weights = scaledStrategies.map { exp((it.second - maxCompressedEtv) / temperature) }
        val totalWeight = weights.sum()

        if (totalWeight == 0.0) {
            return topStrategies.first().cell
        }

        val probabilities = weights.map { it / totalWeight }

        val weightLog = StringBuilder("Weight Distribution:\n")
        weightLog.append("Idx | ETV(s) | Probability\n")
        topStrategies.forEachIndexed { index, strategy ->
            weightLog.append(String.format(" %-2d | %-6.1f | %-6.3f\n", strategy.cell, strategy.etv, probabilities[index]))
        }
        logger.debug(weightLog.toString())

        val random = Random.nextDouble()
        var cumulative = 0.0
        for (i in scaledStrategies.indices) {
            cumulative += probabilities[i]
            if (random < cumulative) {
                return scaledStrategies[i].first.cell
            }
        }

        return topStrategies.last().cell
    }

    /** 计算一个格子的等效时间价值(ETV)，是整个决策模型的核心。 */
    private fun calculateEquivalentTimeValue(
        cellIndex: Int,
        boardState: Array<PlayerState>,
        baselineTime: Float,
        rhythmAdvantage: Double,
        useOpponentModel: Boolean
    ): Double {
        val grid = gridModels[cellIndex]
        val isFallBehind = rhythmAdvantage < -1.75
        // 1. 直接时间价值：比平均节奏快多少/慢多少
        val directTimeValue = (baselineTime - grid.expectedTime).toDouble()

        // 如果有四连，直接给一个高修正
        var directLineValue = 0.0
        if (isWinningMove(cellIndex, PlayerState.AI, boardState)) {
            directLineValue += min(baselineTime * 4, 200.0f) - rhythmAdvantage * baselineTime
        }
        if (isWinningMove(cellIndex, PlayerState.HUMAN, boardState)) {
            directLineValue += min(baselineTime * 5, 200.0f) + rhythmAdvantage * baselineTime
        }

        // 2. 战略时间价值
        val positionalTimeValue = getPositionalTimeValue(cellIndex, baselineTime)
        // 是防守更重要，还是进攻？
        var defenseWeight = if (useOpponentModel) {
            when (style) {
                1 -> 0.9
                2 -> 1.5
                else -> defenseWeight
            }
        } else {
            defenseWeight
        }
        if (isFallBehind) {
            defenseWeight *= 0.5
        }

        val gamePhaseModifier = calculateGamePhaseModifier(boardState)

        val offensiveLeverage = calculateTotalLeverage(cellIndex, PlayerState.AI, boardState, baselineTime, useOpponentModel)
        val defensiveLeverage =
            calculateTotalLeverage(cellIndex, PlayerState.HUMAN, boardState, baselineTime, useOpponentModel) * defenseWeight

        // 归一化，计算该格总时间价值
        val strategicTimeValue = (offensiveLeverage + defensiveLeverage) / (1.0 + defenseWeight) * 2.0 + positionalTimeValue

        return directTimeValue + (strategicTimeValue * gamePhaseModifier) + directLineValue
    }

    /** 累加一个落子在所有相关线路上的时间杠杆。 */
    private fun calculateTotalLeverage(
        cellIndex: Int,
        player: PlayerState,
        boardState: Array<PlayerState>,
        baselineTime: Float,
        useOpponentModel: Boolean
    ): Double {
        val tempBoard = boardState.clone().apply { this[cellIndex] = player }
        var totalLeverage = 0.0
        boardLines.filter { cellIndex in it }.forEach { line ->
            totalLeverage += calculateLineLeverage(line, player, tempBoard, baselineTime, useOpponentModel)
        }
        return totalLeverage
    }

    /**
     * 【核心函数】计算单条线路上的时间杠杆。
     * 它评估通过投资这条线，能迫使对手未来多花多少时间来应对我们设下的“高价难题”。
     */
    private fun calculateLineLeverage(
        line: List<Int>,
        player: PlayerState,
        boardState: Array<PlayerState>,
        baselineTime: Float,
        useOpponentModel: Boolean
    ): Double {
        val opponent = if (player == PlayerState.AI) PlayerState.HUMAN else PlayerState.AI
        // 1. 可行性筛选：如果线路上已有对手棋子，我方无法连五，进攻杠杆为0。
        if (line.any { boardState[it] == opponent }) return 0.0

        val myPiecesCount = line.count { boardState[it] == player }
        // 2. 潜力筛选：至少要有2个子才能形成有方向的威胁。
        if (myPiecesCount < 2 || myPiecesCount > 4) return 0.0

        val emptyCellsIndices = line.filter { boardState[it] == PlayerState.EMPTY }
        if (emptyCellsIndices.isEmpty()) return 0.0

        // 3. 陷阱分析：分离简单格和困难格。
        val difficultyMultiplier = getDifficultyMultiplier()
        val emptyCellModels = emptyCellsIndices.map { gridModels[it] }
        val hardTargets = emptyCellModels.filter { it.expectedTime > baselineTime * difficultyMultiplier }

        var isSuitable = true

        // 如果没有困难格作为“难题”，则无法形成时间陷阱。
        if (hardTargets.isEmpty()) isSuitable = false

        hardTargets.sortedByDescending { it.expectedTime }
        // 连线太难，难以折现
        if (!hardTargets.isEmpty() && hardTargets[0].expectedTime > baselineTime * 7) isSuitable = false

        // 但是连线行为本身还是有一定意义的
        if (!isSuitable) {
            return when (myPiecesCount) {
                2 -> line2Bonus
                3 -> line3Bonus
                4 -> line4Bonus
                else -> 0.0
            } * 0.75
        }

        // 4. 杠杆计算：计算难题的成本，并与基准节奏比较。
        val costOfHardProblem = if (useOpponentModel) {
            val opponentTExpectedProvider: (Int) -> Double = { index ->
                room.spells!![index].fastest.toDouble() / opponentProfile.avgEfficiency
            }
            hardTargets.minOf { opponentTExpectedProvider(it.index) }
        } else {
            hardTargets.minOf { it.expectedTime.toDouble() }
        }

        var timeLeverage = max(0.0, costOfHardProblem - baselineTime)
        // 考虑折现问题
        // 4连折现效率很高，除非太难
        timeLeverage = when {
            timeLeverage < baselineTime * 2 -> timeLeverage
            timeLeverage < baselineTime * 5 -> 3.34 * baselineTime - timeLeverage * 0.67
            else -> 0.0
        }
        // 3连，一难一简单，中等折现效率
        // 否则看两张的总连线效率，难连线的就难折现
        if (myPiecesCount == 3) {
            if (hardTargets.size == 1) {
                timeLeverage *= 0.6
            } else {
                val t0 = hardTargets[0].expectedTime.toDouble() / baselineTime
                val t1 = hardTargets[0].expectedTime.toDouble() / baselineTime
                timeLeverage *= max(0.9 - 0.02 * (t0 * t0 + t1 * t1), 0.1)
            }
        }
        // 2连类似，但折现率更低
        if (myPiecesCount == 2) {
            if (hardTargets.size == 1) {
                timeLeverage *= 0.3
            } else {
                var t = 0.0
                hardTargets.forEach { it ->
                    t += (it.expectedTime.toDouble() / baselineTime) *
                        (it.expectedTime.toDouble() / baselineTime)
                }
                timeLeverage *= max(0.9 - 0.02 * t, 0.1) * 0.5
            }
        }
        // 仍然给连线行为一个基础值
        timeLeverage += when (myPiecesCount) {
            2 -> line2Bonus
            3 -> line3Bonus
            4 -> line4Bonus
            else -> 0.0
        }

        // 5. 全局反向思维：检查这个陷阱是否顺应对手的发展。
        // 比如：对手拿下这个格子，反而会形成更麻烦的4连
        if (timeLeverage > 0 && useOpponentModel) {
            val easiestHardCellIndex = hardTargets.minByOrNull { it.expectedTime }?.index
            if (easiestHardCellIndex != null) {
                // 计算对手拿下此格后的“全局”总收益。
                val opponentTotalGainFromCell = calculateTotalPotentialForCell(
                    easiestHardCellIndex,
                    opponent,
                    boardState, // 使用落子后的棋盘状态进行评估
                    baselineTime
                )

                // 如果对手的全局收益超过了我方收益，说明这是个糟糕的陷阱。
                if (opponentTotalGainFromCell > timeLeverage) {
                    timeLeverage *= 0.2 // 大幅削弱该陷阱的价值。
                }
            }
        }

        return timeLeverage
    }

    /**
     * 【辅助函数】计算一个玩家在某个特定空格子上落子后，能获得的“全局”总线路潜力值。
     * 这是对一个“点”的战略价值的全面评估，会累加所有穿过该点的线路潜力。
     */
    private fun calculateTotalPotentialForCell(
        cellIndex: Int,
        player: PlayerState,
        boardState: Array<PlayerState>,
        baselineTime: Float
    ): Double {
        var totalPotential = 0.0
        val tempBoard = boardState.clone().apply { this[cellIndex] = player }

        // 遍历所有穿过该格子的有效线路
        boardLines.filter { cellIndex in it }.forEach { line ->
            // 对每一条线，都计算其潜力值。
            // 关键：此处调用时，useOpponentModel设为false，以避免无限递归。
            // 我们只关心该玩家自己的直接潜力，而不是对手的反制。
            totalPotential += calculateLineLeverage(
                line,
                player,
                tempBoard,
                baselineTime,
                false // Prevent recursive opponent modeling
            )
        }
        return totalPotential
    }

    /**
     * 计算双方回合数差异
     *
     */
    private fun calculateRhythmAdvantage(boardState: Array<PlayerState>, baselineTime: Float): Double {
        // 1. 直接根据比分差计算基础值
        val aiScore = boardState.count { it == PlayerState.AI }
        val humanScore = boardState.count { it == PlayerState.HUMAN }
        val scoreAdvantage = (aiScore - humanScore).toDouble()

        // 2. 计算真正的小数部分差异
        val now = getCorrectedTime()
        val cdTimeMs = room.actualCdTime[aiPlayerIndex] // 使用AI的实际CD时间
        val opponentCdTimeMs = room.actualCdTime[humanPlayerIndex] // 使用对手的实际CD时间
        val turnDurationMs = (baselineTime * 1000 + cdTimeMs).toDouble()

        // 根据上次收卡时间，计算出下次行动开始的理论时间
        val aiNextActionTime = room.lastGetTime[aiPlayerIndex] + cdTimeMs
        val opponentNextActionTime = room.lastGetTime[humanPlayerIndex] + opponentCdTimeMs

        // **BUG FIX**: Determine the "Ready Time" - the earliest realistic moment a player can act.
        // A player's readiness cannot be in the past. If cooldown is over, they are ready NOW.
        val aiReadyTime = max(now, aiNextActionTime)
        val opponentReadyTime = max(now, opponentNextActionTime)

        // 计算下次行动开始时间的差异
        val timeDiff = (opponentReadyTime - aiReadyTime).toDouble()

        val initiativeAdvantage = if (turnDurationMs > 0) timeDiff / turnDurationMs else 0.0

        // 3. 不确定性惩罚（因为不知道对手的真实状态）
        var uncertaintyPenalty = 0.0
        // This condition remains correct: penalty applies only when opponent is NOT in cooldown.
        if (now > opponentNextActionTime) {
            val opponentSilentTime = now - opponentNextActionTime
            // The longer the opponent is silent, the higher the chance they are about to finish.
            uncertaintyPenalty = (opponentSilentTime / (baselineTime * 1000.0)).coerceIn(0.0, 0.9)
        }

        // 计算出真实的差异。
        // 如双方比分相同，但我方刚刚完成，对方已经很久没有行动，那我方可以认为实际落后0.9个轮次
        val fractionalAdvantage = (initiativeAdvantage - uncertaintyPenalty).coerceIn(-0.99, 0.99)

        logger.debug("RhythmAdvantage: score: $scoreAdvantage, initiative: $initiativeAdvantage," +
            " uncertainty: $uncertaintyPenalty, final_fractional: $fractionalAdvantage")

        return scoreAdvantage + fractionalAdvantage
    }

    /** 检查是否应该使用换卡。 */
    private fun shouldAbandon(): Boolean {
        return false
        /*
        if (strategyLevel < 3 || remainingAbandons <= 0 || currentState != AIState.ATTEMPTING) return false
        val task = currentTask ?: return false
        val boardState = getBoardState() ?: return false

        // 情况一：存在性威胁
        for (i in gridModels.indices) {
            if (boardState[i] == PlayerState.EMPTY && i != task.targetIndex) {
                if (isWinningMove(i, PlayerState.HUMAN, boardState)) {
                    val myRemainingTime = (task.startTimeMs + task.durationMs) - getCorrectedTime()
                    // 快打完了，换掉太亏
                    if (myRemainingTime > abandonPenaltyMs / 1000 * 1.5) {
                        return false
                    }
                    logger.info("ABANDON triggered: Urgent winning/losing cell $i appeared.")
                    return true
                }
            }
        }

        return false
         */
    }

    /** 执行放弃操作。 */
    private fun performAbandon() {
        val task = currentTask ?: return
        val reason = "AI actively abandoned cell ${task.targetIndex}. Remaining charges: ${remainingAbandons - 1}"
        remainingAbandons--

        cancelCurrentSelection("Abandoned")
        applyPreemptionPenalty(reason)

        // 换卡次数-1
        room.changeCardCount[1] -= 1
    }

    /** 统一的取消选择逻辑，供放弃和失败时调用。 */
    private fun cancelCurrentSelection(reason: String) {
        val task = currentTask ?: return

        logger.info("Cancelling selection on cell ${task.targetIndex}. Reason: $reason")
        val cellStatus = room.spellStatus?.get(task.targetIndex)
        if (cellStatus == SpellStatus.BOTH_SELECT) {
            room.spellStatus?.set(task.targetIndex, SpellStatus.LEFT_SELECT)
            room.type.pushSpells(room, task.targetIndex, "AI_Cancel")
        } else if (cellStatus == SpellStatus.RIGHT_SELECT) {
            room.spellStatus?.set(task.targetIndex, SpellStatus.NONE)
            room.type.pushSpells(room, task.targetIndex, "AI_Cancel")
        }
    }

    /** 在对手选择不可见时，预测其最可能的目标。 */
    private fun predictOpponentTarget(boardState: Array<PlayerState>): Int {
        var bestOpponentCell = -1
        var maxOpponentETV = -Double.MAX_VALUE
        val baselineTime = calculateBaselineTime(boardState)

        for (i in gridModels.indices) {
            if (boardState[i] == PlayerState.EMPTY) {
                val opponentBoardState = boardState.map {
                    when (it) {
                        PlayerState.AI -> PlayerState.HUMAN
                        PlayerState.HUMAN -> PlayerState.AI
                        else -> PlayerState.EMPTY
                    }
                }.toTypedArray()
                val opponentETV = calculateEquivalentTimeValue(i, opponentBoardState, baselineTime,
                    -calculateRhythmAdvantage(getBoardState(), baselineTime), true)
                if (opponentETV > maxOpponentETV) {
                    maxOpponentETV = opponentETV
                    bestOpponentCell = i
                }
            }
        }
        return bestOpponentCell
    }

    /** 获取当前棋盘状态的简单表示。 */
    private fun getBoardState(): Array<PlayerState> = room.spellStatus!!.map {
        when (it) {
            SpellStatus.RIGHT_GET -> PlayerState.AI
            SpellStatus.LEFT_GET -> PlayerState.HUMAN
            else -> PlayerState.EMPTY
        }
    }.toTypedArray()

    /** 计算当前棋局的平均节奏（基准时间）。 */
    private fun calculateBaselineTime(boardState: Array<PlayerState>): Float = gridModels
        .filter { boardState[it.index] == PlayerState.EMPTY }
        .sortedBy { it.expectedTime }
        .take(5)
        .map { it.expectedTime.toDouble() }
        .average().toFloat().takeIf { !it.isNaN() } ?: 20.0f

    /** 根据AI等级获取“困难格”的定义乘数。即超过 */
    private fun getDifficultyMultiplier(): Float {
        return 1.6f
    }

    /** 获取一个格子的初始位置价值（以时间为单位）。 */
    private fun getPositionalTimeValue(index: Int, baselineTime: Float): Double {
        val linesThroughCell = boardLines.count { index in it }
        val multiplier = when (linesThroughCell) {
            4 -> 0.25
            3 -> 0.12
            else -> 0.0
        }
        return baselineTime * multiplier
    }

    /** 检查一个落子是否能直接形成五连。 */
    private fun isWinningMove(cellIndex: Int, player: PlayerState, boardState: Array<PlayerState>): Boolean {
        val tempBoard = boardState.clone().apply { this[cellIndex] = player }
        return boardLines.filter { cellIndex in it }.any { line ->
            line.all { tempBoard[it] == player }
        }
    }

    /** 检查在争夺中我方是否可能获胜。 */
    private fun isWinnableContention(cellIndex: Int, isOpponentVisible: Boolean, safetyMargin: Double): Boolean {
        val myETA = gridModels[cellIndex].expectedTime * 1000
        val opponentIdealTime = room.spells!![cellIndex].fastest.times(1000).toDouble()
        val opponentExpectedTime = opponentIdealTime / opponentProfile.avgEfficiency
        val opponentTimeSpent = if (isOpponentVisible) getCorrectedTime() - opponentProfile.lastSelectionTimeMs else 0
        val opponentETA = opponentExpectedTime - opponentTimeSpent
        return myETA < opponentETA * safetyMargin
    }

    /** 计算AI当前是否拥有“先手”优势。 */
    private fun calculateInitiativeState(): InitiativeState {
        val now = getCorrectedTime()
        val cdTimeMs = room.actualCdTime[aiPlayerIndex] // 使用AI的实际CD时间
        val opponentCdTimeMs = room.actualCdTime[humanPlayerIndex] // 使用对手的实际CD时间
        val myNextActionTime = room.lastGetTime[aiPlayerIndex] + cdTimeMs
        val opponentNextActionTime = room.lastGetTime[humanPlayerIndex] + opponentCdTimeMs

        if (now in myNextActionTime..<opponentNextActionTime) return InitiativeState.CLEAR
        if (now in opponentNextActionTime..<myNextActionTime) return InitiativeState.DANGEROUS
        return InitiativeState.NEUTRAL
    }

    /** 根据棋子总数计算游戏阶段修正因子。 */
    private fun calculateGamePhaseModifier(boardState: Array<PlayerState>): Double {
        val totalPieces = boardState.count { it != PlayerState.EMPTY }
        val area = room.boardArea
        val ratio = totalPieces.toFloat() / area
        return when {
            ratio <= 0.16 -> 0.4 // 初局，优先考虑占位置
            ratio <= 0.32 -> 0.7
            ratio <= 0.72 -> 1.0 // 中盘，构筑连线
            ratio <= 1.0 -> 0.4 // 抢分
            else -> 0.0
        }
    }

    /**
     * 应用因目标被抢占或主动放弃而导致的时间惩罚。
     * 这会模拟真实玩家重新评估局势和切换目标所需的时间。
     * @param reason 记录惩罚原因的日志信息。
     */
    private fun applyPreemptionPenalty(reason: String) {
        logger.warn("AI is incurring a time penalty. Reason: $reason")

        // 清理当前无效的任务
        currentTask = null

        // 将状态切换到冷却，以强制执行等待
        currentState = AIState.COOLDOWN

        // 核心：设置一个虚拟的“上次收卡时间”，使得AI必须等待 abandonPenaltyMs 才能再次行动。
        // 这个公式通过将“上次收卡时间”设置在 (当前逻辑时间 + 惩罚时间 - 标准CD) 之前，
        // 来巧妙地让 runCooldownLogic 的判断在 abandonPenaltyMs 之后才为真。
        val cdTimeMs = room.actualCdTime[aiPlayerIndex] // 使用AI的实际CD时间
        room.lastGetTime[aiPlayerIndex] = getCorrectedTime() - cdTimeMs + abandonPenaltyMs
    }
}
