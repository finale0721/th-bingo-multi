package org.tfcc.bingo.message

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class RoomConfig(
    /** 房间名 */
    val rid: String,
    /** 1-标准赛（多人版只支持标准赛） */
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
    /** A 队的 CD 修正值，单位：秒 */
    @SerialName("cd_modifier_a")
    val cdModifierA: Int?,
    /** B 队的 CD 修正值，单位：秒 */
    @SerialName("cd_modifier_b")
    val cdModifierB: Int?,
    /** 是否为团体赛（标准赛不再参与逻辑，仅保留字段） */
    @SerialName("reserved_type")
    val reservedType: Int? = null,
    /** 盲盒设置 */
    @SerialName("blind_setting")
    var blindSetting: Int,
    /** 题库版本 */
    @SerialName("spell_version")
    var spellCardVersion: Int,
    /** 特殊模式：双重盘面 */
    @SerialName("dual_board")
    var dualBoard: Int,
    /** 盲盒揭示等级 */
    @SerialName("blind_reveal_level")
    var blindRevealLevel: Int,
    /** 双重盘面差异度 */
    @SerialName("diff_level")
    var diffLevel: Int,
    /** 游戏权重 */
    @SerialName("game_weight")
    val gameWeight: HashMap<String, Int>,
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
    /** Link赛兼容字段（休眠，不再接线） */
    @SerialName("link_level_coefficient")
    val linkLevelCoefficient: Double = 2.0,
    @SerialName("link_fastest_coefficient")
    val linkFastestCoefficient: Double = 1.0,
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
    /** 收卡标准：1/2（双盘固定 1 次，本字段忽略） */
    @SerialName("capture_standard")
    val captureStandard: Int = 1,
    /** 双盘是否复制同一盘面 */
    @SerialName("copy_board")
    val copyBoard: Boolean = false,
    /** 单队练习房：只允许 A 队成员，禁用作品BP */
    val practice: Boolean = false,
) {
    @Throws(HandlerException::class)
    fun validate() {
        rid.isNotEmpty() || throw HandlerException("房间名不能为空")
        type == 1 || throw HandlerException("暂不支持的游戏类型")
        gameTime in 1..1440 || throw HandlerException("游戏时间的数值不正确")
        countdown in 0..86400 || throw HandlerException("倒计时的数值不正确")
        games.size < 100 || throw HandlerException("选择的作品数太多")
        ranks.size <= 6 || throw HandlerException("选择的难度数太多")
        needWin == null || needWin in 1..99 || throw HandlerException("需要胜场的数值不正确")
        difficulty == null || difficulty in 1..6 || throw HandlerException("难度设置不正确")
        cdTime == null || cdTime in 0..1440 || throw HandlerException("选卡cd的数值不正确")
        cdModifierA == null || cdModifierA in -1440..2880 || throw HandlerException("A队CD修正值范围应为-1440~2880")
        cdModifierB == null || cdModifierB in -1440..2880 || throw HandlerException("B队CD修正值范围应为-1440~2880")
        blindSetting in 1..3 || throw HandlerException("盲盒模式设置不正确")
        spellCardVersion in 1..10 || throw HandlerException("题库版本选择不正确")
        dualBoard in 0..1 || throw HandlerException("双重模式设置不正确")
        blindRevealLevel in 0..4 || throw HandlerException("盲盒揭示等级应在0~4之间")
        diffLevel in -1..5 || throw HandlerException("盘面差异度等级应在-1~5之间")
        gameWeight.values.all { it in -2..2 } || throw HandlerException("游戏权重范围为-2~2")
        customLevelCount.size == 11 || throw HandlerException("自定义等级数据格式错误")
        customLevelCount.all { it in 0..(boardSize * boardSize) } ||
            throw HandlerException("自定义等级数量数值范围应为0~${boardSize * boardSize}")
        boardSize in 4..6 || throw HandlerException("棋盘尺寸应为4~6")
        captureStandard in 1..2 || throw HandlerException("收卡标准只能为1或2")
        pickGuaranteeCount in 0..(boardSize * boardSize * 20 / 100) ||
            throw HandlerException("保底数量范围应为0~${boardSize * boardSize * 20 / 100}")
        (extraLineCount == 0 || boardSize == 6) || throw HandlerException("额外连线仅支持6x6")
        extraLineCount in 0..4 || throw HandlerException("额外连线数量应为0~4")
        hiddenSelectThresholdA == null || hiddenSelectThresholdA in 1..(boardSize * boardSize) ||
            throw HandlerException("A队隐藏阈值应在1~${boardSize * boardSize}之间")
        hiddenSelectThresholdB == null || hiddenSelectThresholdB in 1..(boardSize * boardSize) ||
            throw HandlerException("B队隐藏阈值应在1~${boardSize * boardSize}之间")
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
            blindRevealLevel = config.blindRevealLevel ?: blindRevealLevel,
            diffLevel = config.diffLevel ?: diffLevel,
            gameWeight = config.gameWeight ?: gameWeight,
            customLevelCount = config.customLevelCount ?: customLevelCount,
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
            pickGuaranteeCount = config.pickGuaranteeCount ?: pickGuaranteeCount,
            captureStandard = config.captureStandard ?: captureStandard,
            copyBoard = config.copyBoard ?: copyBoard,
            practice = config.practice ?: practice,
        )
    }
}

@Serializable
class RoomConfigNullable(
    val rid: String,
    val type: Int? = null,
    @SerialName("game_time")
    val gameTime: Int? = null,
    val countdown: Int? = null,
    val games: Array<String>? = null,
    val ranks: Array<String>? = null,
    @SerialName("need_win")
    val needWin: Int? = null,
    val difficulty: Int? = null,
    @SerialName("cd_time")
    val cdTime: Int? = null,
    @SerialName("cd_modifier_a")
    val cdModifierA: Int? = null,
    @SerialName("cd_modifier_b")
    val cdModifierB: Int? = null,
    @SerialName("reserved_type")
    val reservedType: Int? = null,
    @SerialName("blind_setting")
    var blindSetting: Int? = null,
    @SerialName("spell_version")
    var spellCardVersion: Int? = null,
    @SerialName("dual_board")
    var dualBoard: Int? = null,
    @SerialName("blind_reveal_level")
    var blindRevealLevel: Int? = null,
    @SerialName("diff_level")
    var diffLevel: Int? = null,
    @SerialName("game_weight")
    val gameWeight: HashMap<String, Int>? = null,
    @SerialName("custom_level_count")
    val customLevelCount: Array<Int>? = null,
    @SerialName("board_size")
    val boardSize: Int? = null,
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
    @SerialName("pick_guarantee_count")
    val pickGuaranteeCount: Int? = null,
    @SerialName("capture_standard")
    val captureStandard: Int? = null,
    @SerialName("copy_board")
    val copyBoard: Boolean? = null,
    val practice: Boolean? = null,
) {
    @Throws(HandlerException::class)
    fun validate() {
        rid.isNotEmpty() || throw HandlerException("房间名不能为空")
        type == null || type == 1 || throw HandlerException("暂不支持的游戏类型")
        gameTime == null || gameTime in 1..1440 || throw HandlerException("游戏时间的数值不正确")
        countdown == null || countdown in 0..86400 || throw HandlerException("倒计时的数值不正确")
        games == null || games.size < 100 || throw HandlerException("选择的作品数太多")
        ranks == null || ranks.size <= 6 || throw HandlerException("选择的难度数太多")
        needWin == null || needWin in 1..99 || throw HandlerException("需要胜场的数值不正确")
        difficulty == null || difficulty in 1..6 || throw HandlerException("难度设置不正确")
        cdTime == null || cdTime in 0..1440 || throw HandlerException("选卡cd的数值不正确")
        cdModifierA == null || cdModifierA in -1440..2880 || throw HandlerException("A队CD修正值范围应为-1440~2880")
        cdModifierB == null || cdModifierB in -1440..2880 || throw HandlerException("B队CD修正值范围应为-1440~2880")
        blindSetting == null || blindSetting in 1..3 || throw HandlerException("盲盒模式设置不正确")
        spellCardVersion == null || spellCardVersion in 1..10 || throw HandlerException("题库版本选择不正确")
        dualBoard == null || dualBoard in 0..1 || throw HandlerException("双重模式设置不正确")
        blindRevealLevel == null || blindRevealLevel in 0..4 || throw HandlerException("盲盒揭示等级应在0~4之间")
        diffLevel == null || diffLevel in -1..5 || throw HandlerException("盘面差异度等级应在-1~5之间")
        gameWeight == null || gameWeight.values.all { it in -2..2 } || throw HandlerException("游戏权重范围为-2~2")
        customLevelCount == null || customLevelCount.size == 11 || throw HandlerException("自定义等级数据格式错误")
        customLevelCount == null || boardSize == null || customLevelCount.all { it in 0..(boardSize * boardSize) } ||
            throw HandlerException("自定义等级数量数值范围应为0~${boardSize * boardSize}")
        boardSize == null || boardSize in 4..6 || throw HandlerException("棋盘尺寸应为4~6")
        captureStandard == null || captureStandard in 1..2 || throw HandlerException("收卡标准只能为1或2")
        pickGuaranteeCount == null || boardSize == null || pickGuaranteeCount in 0..(boardSize * boardSize * 20 / 100) ||
            throw HandlerException("保底数量范围应为0~${boardSize * boardSize * 20 / 100}")
        extraLineCount == null || extraLineCount in 0..4 || throw HandlerException("额外连线数量应为0~4")
        hiddenSelectThresholdA == null || boardSize == null || hiddenSelectThresholdA in 1..(boardSize * boardSize) ||
            throw HandlerException("A队隐藏阈值应在1~${boardSize * boardSize}之间")
        hiddenSelectThresholdB == null || boardSize == null || hiddenSelectThresholdB in 1..(boardSize * boardSize) ||
            throw HandlerException("B队隐藏阈值应在1~${boardSize * boardSize}之间")
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
