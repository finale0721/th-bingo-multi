package org.tfcc.bingo.message

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class RoomConfig(
    /** 房间名 */
    val rid: String,
    /** 1-标准赛，2-BP赛，3-link赛 */
    val type: Int,
    /** 游戏总时间（不含倒计时），单位：分 */
    @SerialName("game_time")
    val gameTime: Int,
    /** 倒计时，单位：秒 */
    val countdown: Int,
    /** 含有哪些作品 */
    var games: Array<String>,
    /** 含有哪些游戏难度，也就是L卡和EX卡 */
    var ranks: Array<String>,
    /** 需要胜利的局数，例如2表示bo3，空表示1 */
    @SerialName("need_win")
    val needWin: Int?,
    /** 难度（影响不同星级的卡的分布），1对应E，2对应N，3对应L，4对应OD，5对应ODP，6对应自定义 */
    val difficulty: Int?,
    /** 选卡cd，收卡后要多少秒才能选下一张卡，空表示0 */
    @SerialName("cd_time")
    val cdTime: Int?,
    /** 左侧选手（A）的CD修正值，单位：秒 */
    @SerialName("cd_modifier_a")
    val cdModifierA: Int?,
    /** 右侧选手（B）的CD修正值，单位：秒 */
    @SerialName("cd_modifier_b")
    val cdModifierB: Int?,
    /** 是否为团体赛 */
    @SerialName("reserved_type")
    val reservedType: Int? = null,
    /** 盲盒设置（对标准/BP生效） */
    @SerialName("blind_setting")
    var blindSetting: Int,
    /** 题库版本 */
    @SerialName("spell_version")
    var spellCardVersion: Int,
    /** 特殊模式：双重盘面 */
    @SerialName("dual_board")
    var dualBoard: Int,
    /** 双重盘面设定 */
    @SerialName("portal_count")
    var portalCount: Int,
    /** 盲盒揭示等级 */
    @SerialName("blind_reveal_level")
    var blindRevealLevel: Int,
    /** 双重盘面差异度 */
    @SerialName("diff_level")
    var diffLevel: Int,
    /** 是否启用AI陪练 */
    @SerialName("use_ai")
    val useAI: Boolean,
    /** AI策略难度 */
    @SerialName("ai_strategy_level")
    val aiStrategyLevel: Int, // 1:初级, 2:中级, 3:高级
    /** AI决策风格 */
    @SerialName("ai_style")
    val aiStyle: Int, // 0:默认, 1:进攻型, 2:防守型
    /** AI底力 */
    @SerialName("ai_base_power")
    val aiBasePower: Float,
    /** AI熟练度 */
    @SerialName("ai_experience")
    val aiExperience: Float,
    /** AI选卡温度 */
    @SerialName("ai_temperature")
    val aiTemperature: Float,
    /** 游戏权重 */
    @SerialName("game_weight")
    val gameWeight: HashMap<String, Int>,
    /** AI作品修正 */
    @SerialName("ai_preference")
    val aiPreference: HashMap<String, Int>,
    /** 自定义等级数量 */
    @SerialName("custom_level_count")
    val customLevelCount: Array<Int>,
    /** 棋盘边长，4x4/5x5/6x6 */
    @SerialName("board_size")
    val boardSize: Int = 5,
    /** 6x6额外连线数量 */
    @SerialName("extra_line_count")
    val extraLineCount: Int = 0,
    @SerialName("hidden_select_threshold_a")
    val hiddenSelectThresholdA: Int? = null,
    @SerialName("hidden_select_threshold_b")
    val hiddenSelectThresholdB: Int? = null,
    /** Link赛等级系数D */
    @SerialName("link_level_coefficient")
    val linkLevelCoefficient: Double = 2.0,
    /** Link赛时长系数E，乘以fastest字段 */
    @SerialName("link_fastest_coefficient")
    val linkFastestCoefficient: Double = 1.0,
    /** Link赛连接规则，4=四向，8=八向 */
    @SerialName("link_connectivity")
    val linkConnectivity: Int = 8,
    @SerialName("link_disabled_idx")
    val linkDisabledIdx: Array<Int> = emptyArray(),
    @SerialName("link_start_a")
    val linkStartA: Int = 0,
    @SerialName("link_end_a")
    val linkEndA: Int = boardSize * boardSize - 1,
    @SerialName("link_start_b")
    val linkStartB: Int = boardSize - 1,
    @SerialName("link_end_b")
    val linkEndB: Int = boardSize * (boardSize - 1),
    @SerialName("custom_card_pool_enabled")
    val customCardPoolEnabled: Boolean = false,
    /** 保底数量（BP后抽卡时每个保的作品至少出现在盘面上的张数），0为关闭 */
    @SerialName("pick_guarantee_count")
    val pickGuaranteeCount: Int = 0,
) {
    @Throws(HandlerException::class)
    fun validate() {
        rid.isNotEmpty() || throw HandlerException("房间名不能为空")
        type in 1..3 || throw HandlerException("不支持的游戏类型")
        gameTime in 1..1440 || throw HandlerException("游戏时间的数值不正确")
        countdown in 0..86400 || throw HandlerException("倒计时的数值不正确")
        games.size < 100 || throw HandlerException("选择的作品数太多")
        ranks.size <= 6 || throw HandlerException("选择的难度数太多")
        needWin == null || needWin in 1..99 || throw HandlerException("需要胜场的数值不正确")
        difficulty == null || difficulty in 1..6 || throw HandlerException("难度设置不正确")
        cdTime == null || cdTime in 0..1440 || throw HandlerException("选卡cd的数值不正确")
        cdModifierA == null || cdModifierA in -1440..2880 || throw HandlerException("左侧选手CD修正值范围应为-1440~2880")
        cdModifierB == null || cdModifierB in -1440..2880 || throw HandlerException("右侧选手CD修正值范围应为-1440~2880")
        blindSetting in 1..3 || throw HandlerException("盲盒模式设置不正确")
        spellCardVersion in 1..10 || throw HandlerException("题库版本选择不正确")
        dualBoard in 0..1 || throw HandlerException("双重模式设置不正确")
        blindRevealLevel in 0..4 || throw HandlerException("盲盒揭示等级应在0~4之间")
        diffLevel in -1..5 || throw HandlerException("盘面差异度等级应在-1~5之间")
        aiStrategyLevel in 1..3 || throw HandlerException("AI策略难度设置范围应为1~3")
        aiStyle in 0..2 || throw HandlerException("AI决策风格设置范围应为0~2")
        aiBasePower < 10.1f || throw HandlerException("AI底力设置范围应为1~10")
        aiExperience < 10.1f || throw HandlerException("AI熟练度设置范围应为1~10")
        gameWeight.values.all { it in -2..2 } || throw HandlerException("游戏权重范围为-2~2")
        aiPreference.values.all { it in -2..2 } || throw HandlerException("AI作品修正数值范围应为-2~2")
        customLevelCount.size == 11 || throw HandlerException("自定义等级数据格式错误")
        customLevelCount.all { it in 0..(boardSize * boardSize) } ||
            throw HandlerException("自定义等级数量数值范围应为0~${boardSize * boardSize}")
        boardSize in 4..6 || throw HandlerException("棋盘尺寸应为4~6")
        pickGuaranteeCount in 0..(boardSize * boardSize * 20 / 100) ||
            throw HandlerException("保底数量范围应为0~${boardSize * boardSize * 20 / 100}")
        (type != 2 || boardSize == 5) || throw HandlerException("BP赛仅支持5x5棋盘")
        (type != 3 || blindSetting == 1) || throw HandlerException("Link赛不支持盲盒模式")
        (type != 3 || dualBoard == 0) || throw HandlerException("Link赛不支持双盘模式")
        (type != 3 || difficulty in 1..3) || throw HandlerException("Link赛仅支持低/中/高难度")
        portalCount in 1..(boardSize * boardSize) || throw HandlerException("传送门数量应在1~${boardSize * boardSize}之间")
        (extraLineCount == 0 || (boardSize == 6 && type == 1)) || throw HandlerException("额外连线仅支持6x6标准赛")
        extraLineCount in 0..4 || throw HandlerException("额外连线数量应为0~4")
        hiddenSelectThresholdA == null || hiddenSelectThresholdA in 1..(boardSize * boardSize) ||
            throw HandlerException("左侧隐藏阈值应在1~${boardSize * boardSize}之间")
        hiddenSelectThresholdB == null || hiddenSelectThresholdB in 1..(boardSize * boardSize) ||
            throw HandlerException("右侧隐藏阈值应在1~${boardSize * boardSize}之间")
        linkLevelCoefficient in 0.0..100.0 || throw HandlerException("Link赛等级系数范围应为0~100")
        linkFastestCoefficient in 0.0..10.0 || throw HandlerException("Link赛时长系数范围应为0~10")
        linkConnectivity == 4 || linkConnectivity == 8 || throw HandlerException("Link赛连接规则只能为四向或八向")
        val boardArea = boardSize * boardSize
        val linkEndpoints = listOf(linkStartA, linkEndA, linkStartB, linkEndB)
        linkEndpoints.all { it in 0 until boardArea } || throw HandlerException("Link赛起点/终点超出棋盘范围")
        linkStartA != linkEndA || throw HandlerException("Link赛左侧起点和终点不能相同")
        linkStartB != linkEndB || throw HandlerException("Link赛右侧起点和终点不能相同")
        linkDisabledIdx.all { it in 0 until boardArea } || throw HandlerException("Link赛禁用格超出棋盘范围")
        linkDisabledIdx.distinct().size == linkDisabledIdx.size || throw HandlerException("Link赛禁用格不能重复")
        linkDisabledIdx.none { it in linkEndpoints } || throw HandlerException("Link赛禁用格不能覆盖起点或终点")
        if (type == 3) {
            linkReachable(linkStartA, linkEndA, linkDisabledIdx.toSet()) || throw HandlerException("Link赛左侧路线不可达")
            linkReachable(linkStartB, linkEndB, linkDisabledIdx.toSet()) || throw HandlerException("Link赛右侧路线不可达")
        }
    }

    private fun linkReachable(start: Int, end: Int, disabled: Set<Int>): Boolean {
        val queue: java.util.Queue<Int> = java.util.LinkedList()
        val seen = mutableSetOf(start)
        queue.add(start)
        while (queue.isNotEmpty()) {
            val cur = queue.remove()
            if (cur == end) return true
            for (next in linkNeighborIndices(cur)) {
                if (next !in disabled && seen.add(next)) queue.add(next)
            }
        }
        return false
    }

    private fun linkNeighborIndices(index: Int): List<Int> {
        val row = index / boardSize
        val col = index % boardSize
        val offsets = if (linkConnectivity == 4) {
            listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
        } else {
            listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1, -1 to -1, -1 to 1, 1 to -1, 1 to 1)
        }
        return offsets.mapNotNull { (dr, dc) ->
            val nr = row + dr
            val nc = col + dc
            if (nr in 0 until boardSize && nc in 0 until boardSize) nr * boardSize + nc else null
        }
    }

    operator fun plus(config: RoomConfigNullable): RoomConfig {
        return RoomConfig(
            rid = config.rid,
            type = config.type ?: type,
            gameTime = config.gameTime ?: gameTime,
            countdown = config.countdown ?: countdown,
            games = config.games ?: games,
            ranks = config.ranks ?: ranks,
            needWin = config.needWin ?: needWin,
            difficulty = config.difficulty ?: difficulty,
            cdTime = config.cdTime ?: cdTime,
            reservedType = config.reservedType ?: reservedType,
            blindSetting = config.blindSetting ?: blindSetting,
            spellCardVersion = config.spellCardVersion ?: spellCardVersion,
            dualBoard = config.dualBoard ?: dualBoard,
            portalCount = config.portalCount ?: portalCount,
            blindRevealLevel = config.blindRevealLevel ?: blindRevealLevel,
            diffLevel = config.diffLevel ?: diffLevel,
            useAI = config.useAI ?: useAI,
            aiStrategyLevel = config.aiStrategyLevel ?: aiStrategyLevel,
            aiStyle = config.aiStyle ?: aiStyle,
            aiBasePower = config.aiBasePower ?: aiBasePower,
            aiExperience = config.aiExperience ?: aiExperience,
            gameWeight = config.gameWeight ?: gameWeight,
            aiPreference = config.aiPreference ?: aiPreference,
            customLevelCount = config.customLevelCount ?: customLevelCount,
            aiTemperature = config.aiTemperature ?: aiTemperature,
            cdModifierA = config.cdModifierA ?: cdModifierA,
            cdModifierB = config.cdModifierB ?: cdModifierB,
            boardSize = config.boardSize ?: boardSize,
            extraLineCount = config.extraLineCount ?: extraLineCount,
            hiddenSelectThresholdA = config.hiddenSelectThresholdA ?: hiddenSelectThresholdA,
            hiddenSelectThresholdB = config.hiddenSelectThresholdB ?: hiddenSelectThresholdB,
            linkLevelCoefficient = config.linkLevelCoefficient ?: linkLevelCoefficient,
            linkFastestCoefficient = config.linkFastestCoefficient ?: linkFastestCoefficient,
            linkConnectivity = config.linkConnectivity ?: linkConnectivity,
            linkDisabledIdx = config.linkDisabledIdx ?: linkDisabledIdx,
            linkStartA = config.linkStartA ?: linkStartA,
            linkEndA = config.linkEndA ?: linkEndA,
            linkStartB = config.linkStartB ?: linkStartB,
            linkEndB = config.linkEndB ?: linkEndB,
            customCardPoolEnabled = config.customCardPoolEnabled ?: customCardPoolEnabled,
            pickGuaranteeCount = config.pickGuaranteeCount ?: pickGuaranteeCount
        )
    }
}

@Serializable
class RoomConfigNullable(
    /** 房间名 */
    val rid: String,
    /** 1-标准赛，2-BP赛，3-link赛 */
    val type: Int? = null,
    /** 游戏总时间（不含倒计时），单位：分 */
    @SerialName("game_time")
    val gameTime: Int? = null,
    /** 倒计时，单位：秒 */
    val countdown: Int? = null,
    /** 含有哪些作品 */
    val games: Array<String>? = null,
    /** 含有哪些游戏难度，也就是L卡和EX卡 */
    val ranks: Array<String>? = null,
    /** 需要胜利的局数，例如2表示bo3，空表示1 */
    @SerialName("need_win")
    val needWin: Int? = null,
    /** 难度（影响不同星级的卡的分布），1对应E，2对应N，3对应L，4对应OD，5对应ODP，6对应自定义 */
    val difficulty: Int? = null,
    /** 选卡cd，收卡后要多少秒才能选下一张卡，空表示0 */
    @SerialName("cd_time")
    val cdTime: Int? = null,
    /** 左侧选手（A）的CD修正值，单位：秒 */
    @SerialName("cd_modifier_a")
    val cdModifierA: Int? = null,
    /** 右侧选手（B）的CD修正值，单位：秒 */
    @SerialName("cd_modifier_b")
    val cdModifierB: Int? = null,
    /** 是否为团体赛 */
    @SerialName("reserved_type")
    val reservedType: Int? = null,
    /** 盲盒设置（暂时只对标准生效） */
    @SerialName("blind_setting")
    var blindSetting: Int? = null,
    /** 题库版本 */
    @SerialName("spell_version")
    var spellCardVersion: Int? = null,
    /** 特殊模式：双重盘面 */
    @SerialName("dual_board")
    var dualBoard: Int? = null,
    /** 双重盘面设定 */
    @SerialName("portal_count")
    var portalCount: Int? = null,
    /** 盲盒揭示等级 */
    @SerialName("blind_reveal_level")
    var blindRevealLevel: Int? = null,
    /** 双重盘面差异度 */
    @SerialName("diff_level")
    var diffLevel: Int? = null,
    /** 是否启用AI陪练 */
    @SerialName("use_ai")
    val useAI: Boolean? = null, // 0/1
    /** AI策略难度 */
    @SerialName("ai_strategy_level")
    val aiStrategyLevel: Int? = null, // 1:初级, 2:中级, 3:高级
    /** AI决策风格 */
    @SerialName("ai_style")
    val aiStyle: Int? = null, // 0:默认, 1:进攻型, 2:防守型
    /** AI底力 */
    @SerialName("ai_base_power")
    val aiBasePower: Float? = null,
    /** AI熟练度 */
    @SerialName("ai_experience")
    val aiExperience: Float? = null,
    /** AI选卡温度 */
    @SerialName("ai_temperature")
    val aiTemperature: Float? = null,
    /** 游戏权重 */
    @SerialName("game_weight")
    val gameWeight: HashMap<String, Int>? = null,
    /** AI作品修正 */
    @SerialName("ai_preference")
    val aiPreference: HashMap<String, Int>? = null,
    /** 自定义等级数量 */
    @SerialName("custom_level_count")
    val customLevelCount: Array<Int>? = null,
    /** 棋盘边长，4x4/5x5/6x6 */
    @SerialName("board_size")
    val boardSize: Int? = null,
    /** 6x6额外连线数量 */
    @SerialName("extra_line_count")
    val extraLineCount: Int? = null,
    @SerialName("hidden_select_threshold_a")
    val hiddenSelectThresholdA: Int? = null,
    @SerialName("hidden_select_threshold_b")
    val hiddenSelectThresholdB: Int? = null,
    @SerialName("link_level_coefficient")
    val linkLevelCoefficient: Double? = null,
    @SerialName("link_fastest_coefficient")
    val linkFastestCoefficient: Double? = null,
    @SerialName("link_connectivity")
    val linkConnectivity: Int? = null,
    @SerialName("link_disabled_idx")
    val linkDisabledIdx: Array<Int>? = null,
    @SerialName("link_start_a")
    val linkStartA: Int? = null,
    @SerialName("link_end_a")
    val linkEndA: Int? = null,
    @SerialName("link_start_b")
    val linkStartB: Int? = null,
    @SerialName("link_end_b")
    val linkEndB: Int? = null,
    @SerialName("custom_card_pool_enabled")
    val customCardPoolEnabled: Boolean? = null,
    /** 保底数量（BP后抽卡时每个保的作品至少出现在盘面上的张数），0为关闭 */
    @SerialName("pick_guarantee_count")
    val pickGuaranteeCount: Int? = null,
) {
    @Throws(HandlerException::class)
    fun validate() {
        rid.isNotEmpty() || throw HandlerException("房间名不能为空")
        type == null || type in 1..3 || throw HandlerException("不支持的游戏类型")
        gameTime == null || gameTime in 1..1440 || throw HandlerException("游戏时间的数值不正确")
        countdown == null || countdown in 0..86400 || throw HandlerException("倒计时的数值不正确")
        games == null || games.size < 100 || throw HandlerException("选择的作品数太多")
        ranks == null || ranks.size <= 6 || throw HandlerException("选择的难度数太多")
        needWin == null || needWin in 1..99 || throw HandlerException("需要胜场的数值不正确")
        difficulty == null || difficulty in 1..6 || throw HandlerException("难度设置不正确")
        cdTime == null || cdTime in 0..1440 || throw HandlerException("选卡cd的数值不正确")
        cdModifierA == null || cdModifierA in -1440..2880 || throw HandlerException("左侧选手CD修正值范围应为-1440~2880")
        cdModifierB == null || cdModifierB in -1440..2880 || throw HandlerException("右侧选手CD修正值范围应为-1440~2880")
        blindSetting == null || blindSetting in 1..3 || throw HandlerException("盲盒模式设置不正确")
        spellCardVersion == null || spellCardVersion in 1..10 || throw HandlerException("题库版本选择不正确")
        dualBoard == null || dualBoard in 0..1 || throw HandlerException("双重模式设置不正确")
        blindRevealLevel == null || blindRevealLevel in 0..4 || throw HandlerException("盲盒揭示等级应在0~4之间")
        diffLevel == null || diffLevel in -1..5 || throw HandlerException("盘面差异度等级应在-1~5之间")
        aiStrategyLevel == null || aiStrategyLevel in 1..3 || throw HandlerException("AI策略难度设置范围应为1~3")
        aiStyle == null || aiStyle in 0..2 || throw HandlerException("AI决策风格设置范围应为0~2")
        aiBasePower == null || aiBasePower < 10.1f || throw HandlerException("AI底力设置范围应为1~10")
        aiExperience == null || aiExperience < 10.1f || throw HandlerException("AI熟练度设置范围应为1~10")
        gameWeight == null || gameWeight.values.all { it in -2..2 } || throw HandlerException("游戏权重范围为-2~2")
        aiPreference == null || aiPreference.values.all { it in -2..2 } || throw HandlerException("AI作品修正数值范围应为-2~2")
        customLevelCount == null || customLevelCount.size == 11 || throw HandlerException("自定义等级数据格式错误")
        customLevelCount == null || boardSize == null || customLevelCount.all { it in 0..(boardSize * boardSize) } ||
            throw HandlerException("自定义等级数量数值范围应为0~${boardSize * boardSize}")
        boardSize == null || boardSize in 4..6 || throw HandlerException("棋盘尺寸应为4~6")
        pickGuaranteeCount == null || boardSize == null || pickGuaranteeCount in 0..(boardSize * boardSize * 20 / 100) ||
            throw HandlerException("保底数量范围应为0~${boardSize * boardSize * 20 / 100}")
        type == null || boardSize == null || type != 2 || boardSize == 5 || throw HandlerException("BP赛仅支持5x5棋盘")
        type == null || blindSetting == null || type != 3 || blindSetting == 1 ||
            throw HandlerException("Link赛不支持盲盒模式")
        type == null || dualBoard == null || type != 3 || dualBoard == 0 ||
            throw HandlerException("Link赛不支持双盘模式")
        type == null || difficulty == null || type != 3 || difficulty in 1..3 ||
            throw HandlerException("Link赛仅支持低/中/高难度")
        extraLineCount == null || extraLineCount in 0..4 || throw HandlerException("额外连线数量应为0~4")
        hiddenSelectThresholdA == null || boardSize == null || hiddenSelectThresholdA in 1..(boardSize * boardSize) ||
            throw HandlerException("左侧隐藏阈值应在1~${boardSize * boardSize}之间")
        hiddenSelectThresholdB == null || boardSize == null || hiddenSelectThresholdB in 1..(boardSize * boardSize) ||
            throw HandlerException("右侧隐藏阈值应在1~${boardSize * boardSize}之间")
        portalCount == null || boardSize == null || portalCount in 1..(boardSize * boardSize) ||
            throw HandlerException("传送门数量应在1~${boardSize * boardSize}之间")
        linkLevelCoefficient == null || linkLevelCoefficient in 0.0..100.0 ||
            throw HandlerException("Link赛等级系数范围应为0~100")
        linkFastestCoefficient == null || linkFastestCoefficient in 0.0..10.0 ||
            throw HandlerException("Link赛时长系数范围应为0~10")
        linkConnectivity == null || linkConnectivity == 4 || linkConnectivity == 8 ||
            throw HandlerException("Link赛连接规则只能为四向或八向")
        val boardArea = boardSize?.let { it * it }
        linkDisabledIdx == null || boardArea == null || linkDisabledIdx.all { it in 0 until boardArea } ||
            throw HandlerException("Link赛禁用格超出棋盘范围")
        linkDisabledIdx == null || linkDisabledIdx.distinct().size == linkDisabledIdx.size ||
            throw HandlerException("Link赛禁用格不能重复")
        listOf(linkStartA, linkEndA, linkStartB, linkEndB)
            .filterNotNull()
            .all { boardArea == null || it in 0 until boardArea } ||
            throw HandlerException("Link赛起点/终点超出棋盘范围")
    }
}
