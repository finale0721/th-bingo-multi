package org.tfcc.bingo.standard

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.tfcc.bingo.BlindRevealProfile
import org.tfcc.bingo.Player
import org.tfcc.bingo.Room
import org.tfcc.bingo.Spell
import org.tfcc.bingo.encode
import org.tfcc.bingo.message.HandlerException
import org.tfcc.bingo.push
import org.tfcc.bingo.team.RoomMember
import org.tfcc.bingo.team.Team
import kotlin.math.roundToInt
import kotlin.random.asKotlinRandom

/**
 * 标准赛唯一业务实现。所有多人选卡/收卡/换卡/导播操作都在这里，
 * 状态由 [StandardState] 唯一决定，客户端只接收按查看者裁剪后的 [CellView]。
 */
object StandardGame {

    // ------------------------------------------------------------------
    // 开局
    // ------------------------------------------------------------------

    fun createState(room: Room): StandardState {
        val dual = room.roomConfig.dualBoard > 0
        val standard = if (dual) 1 else room.roomConfig.captureStandard.coerceIn(1, 2)
        val state = StandardState(room.boardSpec, standard, dual)
        if (room.boardSpec.size == 6 && room.roomConfig.extraLineCount > 0) {
            val rand = java.util.concurrent.ThreadLocalRandom.current().asKotlinRandom()
            try {
                state.extraLines = room.boardSpec.generateExtraLines(room.roomConfig.extraLineCount, rand)
            } catch (e: IllegalStateException) {
                throw HandlerException("额外连线生成失败：${e.message}")
            }
        }
        room.standardState = state
        return state
    }

    /** 盲盒生成：模式1 / 模式2 */
    fun generateBlind(room: Room) {
        val state = room.standardState ?: return
        val setting = room.roomConfig.blindSetting
        if (setting <= 1) return
        val area = state.board.area
        val all = (0 until area).toMutableList()
        val rand = java.util.concurrent.ThreadLocalRandom.current().asKotlinRandom()
        all.shuffle(rand)

        if (setting == 2) {
            generateBlindMode1(room, state, all, rand)
        } else {
            val profile = BlindRevealProfile.normal(3, state.board.size, room.roomConfig.blindRevealLevel)
            var idx = 0
            repeat(profile.first.coerceIn(0, area)) {
                state.visibility[all[idx++]] = CellVisibility.Mode2Game
            }
            repeat(profile.second.coerceIn(0, area)) {
                state.visibility[all[idx++]] = CellVisibility.Mode2GameStage
            }
            repeat(profile.both.coerceIn(0, area)) {
                state.visibility[all[idx++]] = CellVisibility.Mode2Full
            }
            for (i in idx until area) state.visibility[all[i]] = CellVisibility.Mode2Hidden
        }
    }

    /**
     * 模式1 分配：公共 / 队内 / 自己，数量相等优先。
     *
     * N=盘面格数，L=揭示等级，A=round(N*0.2*L)，P=参与者数（空队伍虚拟 2 席）。
     */
    private fun generateBlindMode1(room: Room, state: StandardState, shuffled: MutableList<Int>, rand: kotlin.random.Random) {
        val n = state.board.area
        val level = room.roomConfig.blindRevealLevel.coerceIn(0, 4)
        val revealed = (n * 0.2 * level).roundToInt().coerceIn(0, n)

        val participants = mutableListOf<String>()
        // 导播只负责管理，不是参与者；即使旧房间残留 host-in-members 也不能污染 P
        val host = room.host
        for (team in Team.entries) {
            room.membersOf(team)
                .filter { host == null || it.player !== host }
                .forEach { participants.add(it.name) }
        }
        // 练习房 B 队视为 2 名永不操作的虚拟参与者
        if (room.roomConfig.practice && room.teamSize(Team.B) == 0) {
            participants.add("#virtual-b-1")
            participants.add("#virtual-b-2")
        }
        val p = participants.size

        // 累计阈值：自己 ≥50%A，自己+队内 ≥75%A，剩余为公共。
        // 按整轮分配（每人一张 / 每队一张），达到或超过阈值后进入下一类别。
        val selfTarget = (revealed * 0.5).roundToInt()
        val teamTarget = (revealed * 0.75).roundToInt()
        var selfTotal = 0
        var teamTotal = 0
        while (p > 0 && selfTotal < selfTarget && selfTotal + p <= revealed) {
            selfTotal += p
        }
        while (p > 0 && selfTotal + teamTotal < teamTarget && selfTotal + teamTotal + 2 <= revealed) {
            teamTotal += 2
        }
        val perSelf = if (p > 0) selfTotal / p else 0
        val perTeam = teamTotal / 2
        val publicTotal = revealed - selfTotal - teamTotal

        state.visibility.fill(CellVisibility.Hidden)
        var cursor = 0

        // 1. 自己专属：按参与者轮转，每人 perSelf 张
        if (perSelf > 0) {
            for (i in 0 until selfTotal) {
                val who = participants[i % p]
                state.visibility[shuffled[cursor++]] = CellVisibility.SelfOnly(who)
            }
        }
        // 2. 队内专属：A/B 各 perTeam 张
        if (perTeam > 0) {
            for (i in 0 until perTeam) {
                state.visibility[shuffled[cursor++]] = CellVisibility.TeamOnly(Team.A.index)
            }
            for (i in 0 until perTeam) {
                state.visibility[shuffled[cursor++]] = CellVisibility.TeamOnly(Team.B.index)
            }
        }
        // 3. 公共
        for (i in 0 until publicTotal) {
            state.visibility[shuffled[cursor++]] = CellVisibility.Public
        }
        // 剩余保持 Hidden
    }

    // ------------------------------------------------------------------
    // 选卡 / 收卡
    // ------------------------------------------------------------------

    fun select(room: Room, member: RoomMember, index: Int, board: Int) {
        val state = room.standardState ?: throw HandlerException("游戏还没开始")
        room.started || throw HandlerException("游戏还没开始")
        validateBoard(state, board)
        val now = checkTime(room, member)
        val countsDown = now < room.startMs + room.roomConfig.countdown * 1000L

        val team = member.team
        val cell = state.cell(board, index)

        // 已确认选卡后不能再次选择（包括同一格）；切换目标请使用换卡
        member.selectedIndex == -1 || throw HandlerException("已经选过符卡")

        val fullyCaptured = state.fullyCapturedBy(index) != null
        fullyCaptured && throw HandlerException("该格已被收取")
        if (state.dualBoard && cell.capturedByTeamOnBoard(team.index)) {
            throw HandlerException("本队已收取该面")
        }

        if (countsDown) {
            val opponentSelected = cell.selectedBy.any { name ->
                val t = teamOf(room, name)
                t != null && t != team
            }
            opponentSelected && throw HandlerException("倒计时阶段不能抢卡")
        }

        // CD
        if (member.actualCdTime > 0) {
            val nextCanSelect = member.lastGetTime + member.actualCdTime - 1000L
            val remain = nextCanSelect - now
            remain > 0 && throw HandlerException("还有${remain / 1000 + 1}秒才能选卡")
        }

        // 同队互斥：只有本队可选格不足时，才允许多人选同一格
        val teammatesOnCell = cell.selectedBy.count { it != member.name && teamOf(room, it) == team }
        if (teammatesOnCell > 0) {
            val available = availableForTeam(room, state, team, board)
            available < room.teamSize(team) || throw HandlerException("该格已被队友选择")
        }

        member.selectedIndex = index
        member.selectedBoard = board
        cell.selectedBy.add(member.name)
        markSelectionRevealed(room, state, index, team)
        pushMySelection(member, index, board)
        pushCell(room, board, index)

        // 换卡后又选回换卡前的格子：立即返还次数，结束待结算
        if (member.changePending && board == member.changeOriginBoard && index == member.changeOriginIndex) {
            room.changeCardCount[team.index] += 1
            member.changePending = false
            member.changeOriginBoard = -1
            member.changeOriginIndex = -1
            pushChangeState(member)
            room.push(
                "push_update_change_card_count", JsonObject(
                    mapOf(
                        "team" to JsonPrimitive(team.index),
                        "count" to JsonPrimitive(room.changeCardCount[team.index]),
                        "reason" to JsonPrimitive("refund")
                    )
                )
            )
            room.gameLogger?.logStandardAction(
                room = room,
                actor = member.player,
                op = "refund",
                team = team.index,
                board = board,
                spellIndex = index,
                spell = spellAt(room, board, index)
            )
        }

        pushSelectLog(room, member, index, board, "select")
    }

    fun finish(room: Room, member: RoomMember, index: Int) {
        val state = room.standardState ?: throw HandlerException("游戏还没开始")
        room.started || throw HandlerException("游戏还没开始")
        member.selectedIndex == index || throw HandlerException("你还未选卡")
        val board = member.selectedBoard
        validateBoard(state, board)
        val now = checkTime(room, member)
        now >= room.startMs + room.roomConfig.countdown * 1000L || throw HandlerException("倒计时还没结束")

        val cell = state.cell(board, index)
        state.fullyCapturedBy(index) == null || throw HandlerException("该格已被收取")
        if (state.dualBoard && cell.capturedByTeamOnBoard(member.team.index)) {
            throw HandlerException("本队已收取该面")
        }

        val team = member.team
        val victims: List<String>
        val reachedStandard: Boolean

        if (state.dualBoard) {
            cell.setCapturedOnBoard(team.index, true)
            reachedStandard = state.fullyCapturedBy(index) != null
            victims = if (reachedStandard) {
                // 完整收取：两面所有仍选择该格的人都是受害者
                pendingVictims(state, index, member.name)
            } else {
                // 半收：同队同面同格的其他队友目标被这一面收走，同样按抢卡处理
                sameBoardTeamVictims(room, cell, member.name, team)
            }
        } else {
            val count = cell.captureCountOf(team.index) + 1
            cell.setCaptureCount(team.index, count)
            reachedStandard = count >= state.captureStandard
            victims = if (reachedStandard) cell.selectedBy.filter { it != member.name } else emptyList()
            if (reachedStandard) cell.capturedBy = team.index
        }

        // 清除收取者自己的选择
        cell.selectedBy.remove(member.name)
        member.selectedIndex = -1
        member.selectedBoard = 0
        member.lastGetTime = now

        if (reachedStandard) {
            clearCellSelections(room, state, index)
        } else if (victims.isNotEmpty()) {
            clearSelectionsOnBoard(room, state, board, index, victims)
        }

        // 换卡结算
        val changeReason = settleChange(room, member, board, index, now)

        // 推送
        pushMySelection(member, -1, 0)
        pushMyCapture(member, index, board)
        pushCell(room, board, index)
        if (state.dualBoard && reachedStandard) pushCell(room, 1 - board, index)

        if (changeReason != null) {
            room.push(
                "push_update_change_card_count", JsonObject(
                    mapOf(
                        "team" to JsonPrimitive(team.index),
                        "count" to JsonPrimitive(room.changeCardCount[team.index]),
                        "reason" to JsonPrimitive(changeReason)
                    )
                )
            )
            if (changeReason == "refund") {
                room.gameLogger?.logStandardAction(
                    room = room,
                    actor = member.player,
                    op = "refund",
                    team = team.index,
                    board = board,
                    spellIndex = index,
                    spell = spellAt(room, board, index)
                )
            }
        }

        if (reachedStandard) {
            pushGrabAlerts(room, team, index, board, victims, member.name)
            pushCaptureLog(room, member, index, board, team, "capture_complete", victims)
        } else {
            // 半收时若同面同格还有队友在选，同样按抢卡处理
            if (victims.isNotEmpty()) pushGrabAlerts(room, team, index, board, victims, member.name)
            pushCaptureLog(room, member, index, board, team, "capture_partial", victims)
        }
    }

    // ------------------------------------------------------------------
    // 换卡
    // ------------------------------------------------------------------

    fun changeCard(room: Room, member: RoomMember) {
        val state = room.standardState ?: throw HandlerException("游戏还没开始")
        room.started || throw HandlerException("游戏还没开始")
        room.pauseBeginMs == 0L || throw HandlerException("暂停中，不能操作")
        System.currentTimeMillis() >= room.startMs + room.roomConfig.countdown * 1000L ||
            throw HandlerException("倒计时阶段不能换卡")
        member.selectedIndex != -1 || throw HandlerException("你还未选卡")
        val team = member.team
        val board = member.selectedBoard
        val index = member.selectedIndex

        if (member.changePending) {
            // 待结算期间：有选择即可再次点击换卡，只取消这次选择，不消耗次数，也不改变原换卡
            state.cell(board, index).selectedBy.remove(member.name)
            member.selectedIndex = -1
            member.selectedBoard = 0
            pushMySelection(member, -1, 0)
            pushCell(room, board, index)
            pushSelectLog(room, member, index, board, "cancel")
            return
        }

        room.changeCardCount[team.index] > 0 || throw HandlerException("没有换卡次数了")
        room.changeCardCount[team.index] -= 1
        member.changePending = true
        member.changeOriginBoard = board
        member.changeOriginIndex = index
        state.cell(board, index).selectedBy.remove(member.name)
        member.selectedIndex = -1
        member.selectedBoard = 0

        room.push(
            "push_update_change_card_count", JsonObject(
                mapOf(
                    "team" to JsonPrimitive(team.index),
                    "count" to JsonPrimitive(room.changeCardCount[team.index]),
                    "reason" to JsonPrimitive("change")
                )
            )
        )
        pushMySelection(member, -1, 0)
        pushChangeState(member)
        pushCell(room, board, index)
        room.gameLogger?.logStandardAction(
            room = room,
            actor = member.player,
            op = "change",
            team = team.index,
            board = board,
            spellIndex = index,
            spell = spellAt(room, board, index)
        )
        // 操作记录里不打印「使用换卡」本身；只记录换卡造成的取消选择
        pushSelectLog(room, member, index, board, "cancel")
    }

    /** @return "refund" / "consume" / null（没有待结算换卡） */
    private fun settleChange(room: Room, member: RoomMember, board: Int, index: Int, now: Long): String? {
        if (!member.changePending) return null
        val same = board == member.changeOriginBoard && index == member.changeOriginIndex
        val reason = if (same) {
            room.changeCardCount[member.team.index] += 1
            "refund"
        } else {
            "consume"
        }
        member.changePending = false
        member.changeOriginBoard = -1
        member.changeOriginIndex = -1
        pushChangeState(member)
        return reason
    }

    // ------------------------------------------------------------------
    // 导播 / 无导播操作
    // ------------------------------------------------------------------

    fun updateCellState(room: Room, actor: Player, index: Int, op: String, name: String?, teamIndex: Int?, board: Int) {
        val state = room.standardState ?: throw HandlerException("游戏还没开始")
        room.started || throw HandlerException("游戏还没开始")
        if (room.host != null) {
            room.host === actor || throw HandlerException("没有权限")
        } else {
            room.memberOf(actor) != null || throw HandlerException("没有权限")
        }
        validateBoard(state, board)
        when (op) {
            "toggle_select" -> directorToggleSelect(room, state, index, board, name, actor)
            "capture_once" -> directorCapture(room, state, index, board, teamIndex, once = true, actor = actor)
            "set_captured" -> directorCapture(room, state, index, board, teamIndex, once = false, actor = actor)
            "clear" -> directorClear(room, state, index, actor)
            else -> throw HandlerException("未知操作")
        }
    }

    /** 导播房记录导播名（无色）；无导播房记录实际操作者 */
    private fun actionActorName(room: Room, actor: Player): String = room.host?.name ?: actor.name

    /** 导播本人返回 -1（无色）；无导播房返回操作者队伍 */
    private fun actionActorTeam(room: Room, actor: Player): Int =
        if (room.host != null) -1 else (room.memberOf(actor)?.team?.index ?: -1)

    private fun directorToggleSelect(room: Room, state: StandardState, index: Int, board: Int, name: String?, actor: Player) {
        name != null || throw HandlerException("缺少玩家名")
        val member = room.members.firstOrNull { it.name == name }
            ?: throw HandlerException("找不到该成员")

        // 状态覆盖：目标格与当前选择相同则保持；否则取消原选择并直接换到目标格
        val isSame = member.selectedIndex == index && member.selectedBoard == board
        if (isSame) return
        val oldBoard = member.selectedBoard
        val oldIndex = member.selectedIndex
        if (oldIndex >= 0) {
            state.cell(oldBoard, oldIndex).selectedBy.remove(member.name)
        }
        val cell = state.cell(board, index)
        // 先更新成员状态，再推送旧格；否则旧格视图仍会带着 my_selected=true
        member.selectedIndex = index
        member.selectedBoard = board
        cell.selectedBy.add(member.name)
        markSelectionRevealed(room, state, index, member.team)
        if (oldIndex >= 0) pushCell(room, oldBoard, oldIndex)
        pushMySelection(member, index, board)
        pushCell(room, board, index)
        pushDirectorSelectLog(room, member, index, board, "director_select", actor)
    }

    private fun directorCapture(
        room: Room,
        state: StandardState,
        index: Int,
        board: Int,
        teamIndex: Int?,
        once: Boolean,
        actor: Player
    ) {
        val team = Team.of(teamIndex) ?: throw HandlerException("缺少队伍")
        val cell = state.cell(board, index)

        val reached: Boolean
        val victims: List<String>
        if (state.dualBoard) {
            if (once) {
                state.fullyCapturedBy(index) == null || throw HandlerException("该格已被收取")
                !cell.capturedByTeamOnBoard(team.index) || throw HandlerException("该队伍已收取该面")
                cell.setCapturedOnBoard(team.index, true)
                reached = state.fullyCapturedBy(index) != null
                victims = if (reached) {
                    pendingVictims(state, index, null)
                } else {
                    sameBoardTeamVictims(room, cell, null, team)
                }
            } else {
                // 收取 = 整个逻辑格覆盖为该队已收；两面都置位，并清掉对方的收取标记
                for (b in 0..1) {
                    val boardCell = state.cell(b, index)
                    boardCell.setCapturedOnBoard(team.index, true)
                    boardCell.setCapturedOnBoard(1 - team.index, false)
                }
                reached = true
                victims = pendingVictims(state, index, null)
            }
        } else {
            val count: Int
            if (once) {
                state.fullyCapturedBy(index) == null || throw HandlerException("该格已被收取")
                cell.captureCountOf(team.index) < state.captureStandard || throw HandlerException("该队伍已收取")
                count = cell.captureCountOf(team.index) + 1
            } else {
                // 收取 = 整个格子覆盖为该队已收；清掉对方的中间次数
                cell.setCaptureCount(1 - team.index, 0)
                count = state.captureStandard
            }
            cell.setCaptureCount(team.index, count)
            reached = count >= state.captureStandard
            victims = if (reached) cell.selectedBy.toList() else emptyList()
            if (reached) cell.capturedBy = team.index
        }

        if (reached) {
            clearCellSelections(room, state, index)
        } else if (victims.isNotEmpty()) {
            clearSelectionsOnBoard(room, state, board, index, victims)
        }

        val op = if (once) "director_capture" else "director_set"
        room.gameLogger?.logStandardAction(
            room = room,
            actor = null,
            op = op,
            team = team.index,
            board = board,
            spellIndex = index,
            spell = spellAt(room, board, index),
            victims = victims
        )
        pushCell(room, board, index)
        if (state.dualBoard && reached) pushCell(room, 1 - board, index)
        if (victims.isNotEmpty()) {
            pushGrabAlerts(room, team, index, board, victims, actionActorName(room, actor), actionActorTeam(room, actor))
        }
        pushDirectorCaptureLog(room, team, index, board, op, victims, actor)
    }

    private fun directorClear(room: Room, state: StandardState, index: Int, actor: Player) {
        // 置空 = 清空该格所有状态，包括选择造成的永久揭示
        state.revealedToA[index] = false
        state.revealedToB[index] = false
        val boards = if (state.dualBoard) listOf(0, 1) else listOf(0)
        for (b in boards) {
            val cell = state.cell(b, index)
            cell.captureCountA = 0
            cell.captureCountB = 0
            cell.capturedBy = null
            cell.capturedByA = false
            cell.capturedByB = false
            for (name in cell.selectedBy) {
                val m = room.members.firstOrNull { it.name == name }
                if (m != null) {
                    m.selectedIndex = -1
                    m.selectedBoard = 0
                    pushMySelection(m, -1, 0)
                }
            }
            cell.selectedBy.clear()
        }
        for (b in boards) pushCell(room, b, index)
        room.gameLogger?.logStandardAction(
            room = room,
            actor = null,
            op = "director_clear",
            team = null,
            board = 0,
            spellIndex = index,
            spell = spellAt(room, 0, index)
        )
        pushLogPublic(
            room, JsonObject(
                mapOf(
                    "actor" to JsonPrimitive(actionActorName(room, actor)),
                    "actor_team" to JsonPrimitive(actionActorTeam(room, actor)),
                    "team" to JsonPrimitive(-1),
                    "op" to JsonPrimitive("director_clear"),
                    "index" to JsonPrimitive(index),
                    "board" to JsonPrimitive(0)
                )
            )
        )
    }

    // ------------------------------------------------------------------
    // 视图
    // ------------------------------------------------------------------

    fun cellViews(room: Room, board: Int, viewer: RoomMember?): List<CellView> {
        val state = room.standardState ?: return emptyList()
        return (0 until state.board.area).map { cellView(room, state, board, it, viewer) }
    }

    fun cellView(room: Room, board: Int, index: Int, viewer: RoomMember?): CellView? {
        val state = room.standardState ?: return null
        return cellView(room, state, board, index, viewer)
    }

    private fun cellView(room: Room, state: StandardState, board: Int, index: Int, viewer: RoomMember?): CellView {
        val cell = state.cell(board, index)
        val allNames = cell.selectedBy
        val visibleNames = if (viewer == null) allNames.toList() else allNames.filter { canSeeSelection(room, viewer, it) }
        val selectedByA = visibleNames.filter { teamOf(room, it) == Team.A }
        val selectedByB = visibleNames.filter { teamOf(room, it) == Team.B }

        val capturedByA: Boolean
        val capturedByB: Boolean
        val countA: Int?
        val countB: Int?
        if (state.dualBoard) {
            capturedByA = cell.capturedByA
            capturedByB = cell.capturedByB
            countA = null
            countB = null
        } else {
            capturedByA = cell.capturedBy == Team.A.index
            capturedByB = cell.capturedBy == Team.B.index
            // 部分收取结果对双方公开；隐藏的只有对方的选卡
            countA = cell.captureCountA
            countB = cell.captureCountB
        }

        val mySelected = viewer != null && viewer.selectedIndex == index && viewer.selectedBoard == board
        val seeAnySelection = if (viewer == null) {
            allNames.isNotEmpty() || (state.cells2?.get(index)?.selectedBy?.isNotEmpty() == true)
        } else {
            allNames.any { canSeeSelection(room, viewer, it) } ||
                (state.cells2?.get(index)?.selectedBy?.any { canSeeSelection(room, viewer, it) } == true)
        }

        // 选择历史揭示：一旦选择过，卡面永久公开；对手是否可见由选择时的隐藏规则决定
        val selectionRevealed = when {
            viewer == null -> state.revealedToA[index] || state.revealedToB[index]
            viewer.team == Team.A -> state.revealedToA[index]
            else -> state.revealedToB[index]
        }
        val contentReveal = when {
            state.anyCaptured(index) -> 4
            selectionRevealed -> 4
            seeAnySelection -> 4
            else -> baseReveal(room, state.visibility[index], viewer)
        }

        return CellView(
            index = index,
            selectedByA = selectedByA,
            selectedByB = selectedByB,
            captureCountA = countA,
            captureCountB = countB,
            capturedByA = capturedByA,
            capturedByB = capturedByB,
            fullyCapturedBy = state.fullyCapturedBy(index),
            mySelected = mySelected,
            contentReveal = contentReveal
        )
    }

    private fun baseReveal(room: Room, visibility: CellVisibility, viewer: RoomMember?): Int {
        return when (visibility) {
            CellVisibility.Public -> 4
            CellVisibility.Hidden -> 0
            is CellVisibility.TeamOnly -> if (viewer == null || viewer.team.index == visibility.team) 4 else 0
            is CellVisibility.SelfOnly -> if (viewer == null || viewer.name == visibility.name) 4 else 0
            CellVisibility.Mode2Hidden -> 0
            CellVisibility.Mode2Game -> 1
            CellVisibility.Mode2GameStage -> 2
            CellVisibility.Mode2Star -> 3
            CellVisibility.Mode2Full -> 4
        }
    }

    /**
     * 记录一次选择造成的永久揭示。
     *
     * 选择方队伍始终可见；对方是否可见取决于**选择发生时**该队的隐藏阈值状态。
     */
    private fun markSelectionRevealed(room: Room, state: StandardState, index: Int, team: Team) {
        if (team == Team.A) {
            state.revealedToA[index] = true
            if (!hideSelectionsOf(room, Team.A)) state.revealedToB[index] = true
        } else {
            state.revealedToB[index] = true
            if (!hideSelectionsOf(room, Team.B)) state.revealedToA[index] = true
        }
    }

    /** 查看者能否看到某个成员在该格上的选择 */
    private fun canSeeSelection(room: Room, viewer: RoomMember, name: String): Boolean {
        val owner = room.members.firstOrNull { it.name == name } ?: return false
        if (owner.team == viewer.team) return true
        return !hideSelectionsOf(room, owner.team)
    }

    fun hideSelectionsOf(room: Room, team: Team): Boolean {
        return room.teamScore(team) >= hiddenSelectThreshold(room, team)
    }

    private fun hiddenSelectThreshold(room: Room, team: Team): Int {
        val configure = if (team == Team.A) room.roomConfig.hiddenSelectThresholdA
        else room.roomConfig.hiddenSelectThresholdB
        return configure ?: defaultHiddenSelectThreshold(room.boardSpec.size)
    }

    private fun defaultHiddenSelectThreshold(boardSize: Int): Int = when (boardSize) {
        4 -> 3
        6 -> 7
        else -> 5
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun validateBoard(state: StandardState, board: Int) {
        if (state.dualBoard) {
            board in 0..1 || throw HandlerException("盘面参数错误")
        } else {
            board == 0 || throw HandlerException("盘面参数错误")
        }
    }

    private fun checkTime(room: Room, member: RoomMember): Long {
        room.pauseBeginMs == 0L || throw HandlerException("暂停中，不能操作")
        val now = System.currentTimeMillis()
        val gameTime = room.roomConfig.gameTime.toLong() * 60000L
        val countdown = room.roomConfig.countdown.toLong() * 1000L
        val hostBypass = room.host != null && room.host === member.player
        if (room.startMs <= now - gameTime - countdown - room.totalPauseMs &&
            !hostBypass && !isDraw(room)
        ) {
            throw HandlerException("游戏时间到")
        }
        return now
    }

    private fun isDraw(room: Room): Boolean {
        val state = room.standardState ?: return true
        return room.teamScore(Team.A) == room.teamScore(Team.B)
    }

    private fun availableForTeam(room: Room, state: StandardState, team: Team, board: Int): Int {
        val cells = state.boardCells(board)
        var count = 0
        for (index in cells.indices) {
            val cell = cells[index]
            val fully = state.fullyCapturedBy(index) != null
            val teamCaptured = state.dualBoard && cell.capturedByTeamOnBoard(team.index)
            val teamSelected = cell.selectedBy.any { teamOf(room, it) == team }
            if (!fully && !teamCaptured && !teamSelected) count++
        }
        return count
    }

    private fun teamOf(room: Room, name: String): Team? = room.members.firstOrNull { it.name == name }?.team

    /** 双盘完整收取：两面所有仍选择该格的人都是受害者 */
    private fun pendingVictims(state: StandardState, index: Int, capturer: String?): List<String> {
        val names = LinkedHashSet<String>()
        names.addAll(state.cells[index].selectedBy)
        state.cells2?.get(index)?.selectedBy?.let { names.addAll(it) }
        if (capturer != null) names.remove(capturer)
        return names.toList()
    }

    /**
     * 双盘半收：同队在同一面、同一格上的其他选择。
     *
     * 该队收下这一面后，这些队友在这一面已无法再收取，目标被同队收走，
     * 因此同样按「抢卡」处理（不看抢卡者是谁）。
     */
    private fun sameBoardTeamVictims(room: Room, cell: CellState, capturer: String?, team: Team): List<String> {
        return cell.selectedBy.filter { it != capturer && teamOf(room, it) == team }
    }

    /** 清除某一面上的指定选择 */
    private fun clearSelectionsOnBoard(room: Room, state: StandardState, board: Int, index: Int, names: List<String>) {
        val cell = state.cell(board, index)
        for (name in names) {
            if (!cell.selectedBy.remove(name)) continue
            val m = room.members.firstOrNull { it.name == name } ?: continue
            m.selectedIndex = -1
            m.selectedBoard = 0
            pushMySelection(m, -1, 0)
        }
    }

    /** 完整收取后清除该逻辑格两面的所有选择 */
    private fun clearCellSelections(room: Room, state: StandardState, index: Int) {
        val boards = if (state.dualBoard) listOf(0, 1) else listOf(0)
        for (b in boards) {
            clearSelectionsOnBoard(room, state, b, index, state.cell(b, index).selectedBy.toList())
        }
    }

    private fun spellAt(room: Room, board: Int, index: Int): Spell? =
        if (board == 1) room.spells2?.getOrNull(index) else room.spells?.getOrNull(index)

    // ------------------------------------------------------------------
    // 推送
    // ------------------------------------------------------------------

    private fun pushCell(room: Room, board: Int, index: Int) {
        for (member in room.members) {
            val view = cellView(room, board, index, member) ?: continue
            member.player.push("push_cell_update", JsonObject(
                mapOf(
                    "board" to JsonPrimitive(board),
                    "cell" to view.encode()
                )
            ))
        }
        val spectatorView = cellView(room, board, index, null) ?: return
        val payload = JsonObject(
            mapOf(
                "board" to JsonPrimitive(board),
                "cell" to spectatorView.encode()
            )
        )
        room.host?.push("push_cell_update", payload)
        room.watchers.forEach { it.push("push_cell_update", payload) }
    }

    private fun pushMySelection(member: RoomMember, index: Int, board: Int) {
        member.player.push(
            "push_my_selection",
            JsonObject(
                mapOf(
                    "index" to JsonPrimitive(index),
                    "board" to JsonPrimitive(board)
                )
            )
        )
    }

    private fun pushMyCapture(member: RoomMember, index: Int, board: Int) {
        val leftCd = (member.lastGetTime + member.actualCdTime - System.currentTimeMillis()).coerceAtLeast(0L)
        member.player.push(
            "push_my_capture",
            JsonObject(
                mapOf(
                    "index" to JsonPrimitive(index),
                    "board" to JsonPrimitive(board),
                    "left_cd_time" to JsonPrimitive(leftCd)
                )
            )
        )
    }

    private fun pushChangeState(member: RoomMember) {
        member.player.push(
            "push_change_state",
            JsonObject(
                mapOf(
                    "pending" to JsonPrimitive(member.changePending),
                    "origin_index" to JsonPrimitive(member.changeOriginIndex),
                    "origin_board" to JsonPrimitive(member.changeOriginBoard)
                )
            )
        )
    }

    private fun pushGrabAlerts(
        room: Room,
        team: Team,
        index: Int,
        board: Int,
        victims: List<String>,
        actorName: String,
        actorTeam: Int? = null
    ) {
        for (name in victims) {
            val victim = room.members.firstOrNull { it.name == name } ?: continue
            victim.player.push(
                "push_grab_alert",
                JsonObject(
                    mapOf(
                        "index" to JsonPrimitive(index),
                        "board" to JsonPrimitive(board),
                        "by" to JsonPrimitive(actorName)
                    )
                )
            )
            // 受害者自己的操作记录：XX抢了你选的符卡（公开日志只发给导播/观众）
            victim.player.push(
                "push_action_log",
                JsonObject(
                    mapOf(
                        "actor" to JsonPrimitive(actorName),
                        "actor_team" to JsonPrimitive(actorTeam ?: team.index),
                        "team" to JsonPrimitive(team.index),
                        "op" to JsonPrimitive("grab_victim"),
                        "index" to JsonPrimitive(index),
                        "board" to JsonPrimitive(board)
                    )
                )
            )
            room.gameLogger?.logStandardAction(
                room = room,
                actor = null,
                op = "grab_victim",
                team = team.index,
                board = board,
                spellIndex = index,
                spell = spellAt(room, board, index),
                affectedName = name,
                victims = listOf(name)
            )
        }
        if (victims.isNotEmpty()) {
            val payload = JsonObject(
                mapOf(
                    "actor" to JsonPrimitive(actorName),
                    "actor_team" to JsonPrimitive(actorTeam ?: team.index),
                    "team" to JsonPrimitive(team.index),
                    "op" to JsonPrimitive("grab_public"),
                    "index" to JsonPrimitive(index),
                    "board" to JsonPrimitive(board),
                    "victims" to JsonArray(victims.map { JsonPrimitive(it) })
                )
            )
            room.host?.push("push_action_log", payload)
            room.watchers.forEach { it.push("push_action_log", payload) }
            room.gameLogger?.logStandardAction(
                room = room,
                actor = null,
                op = "grab_public",
                team = team.index,
                board = board,
                spellIndex = index,
                spell = spellAt(room, board, index),
                victims = victims
            )
        }
    }

    private fun pushSelectLog(room: Room, member: RoomMember, index: Int, board: Int, op: String) {
        val team = member.team
        val log = JsonObject(
            mapOf(
                "actor" to JsonPrimitive(member.name),
                "team" to JsonPrimitive(team.index),
                "op" to JsonPrimitive(op),
                "index" to JsonPrimitive(index),
                "board" to JsonPrimitive(board)
            )
        )
        pushLogToTeam(room, team, log, revealToOpponent = !hideSelectionsOf(room, team))
        room.gameLogger?.logStandardAction(
            room = room,
            actor = member.player,
            op = op,
            team = team.index,
            board = board,
            spellIndex = index,
            spell = spellAt(room, board, index)
        )
    }

    private fun pushDirectorSelectLog(room: Room, target: RoomMember, index: Int, board: Int, op: String, actor: Player) {
        val log = JsonObject(
            mapOf(
                "actor" to JsonPrimitive(actionActorName(room, actor)),
                "actor_team" to JsonPrimitive(actionActorTeam(room, actor)),
                "team" to JsonPrimitive(target.team.index),
                "op" to JsonPrimitive(op),
                "index" to JsonPrimitive(index),
                "board" to JsonPrimitive(board),
                "name" to JsonPrimitive(target.name)
            )
        )
        pushLogToTeam(room, target.team, log, revealToOpponent = !hideSelectionsOf(room, target.team))
        room.gameLogger?.logStandardAction(
            room = room,
            actor = null,
            op = op,
            team = target.team.index,
            board = board,
            spellIndex = index,
            spell = spellAt(room, board, index),
            affectedName = target.name
        )
    }

    private fun pushCaptureLog(
        room: Room,
        member: RoomMember,
        index: Int,
        board: Int,
        team: Team,
        op: String,
        victims: List<String>
    ) {
        val state = room.standardState ?: return
        val log = JsonObject(
            mapOf(
                "actor" to JsonPrimitive(member.name),
                "team" to JsonPrimitive(team.index),
                "op" to JsonPrimitive(op),
                "index" to JsonPrimitive(index),
                "board" to JsonPrimitive(board),
                "capture_count" to JsonPrimitive(
                    if (state.dualBoard) 1 else state.cells[index].captureCountOf(team.index)
                ),
                "capture_standard" to JsonPrimitive(state.captureStandard),
                "victims" to JsonArray(victims.map { JsonPrimitive(it) })
            )
        )
        val revealToOpponent = op == "capture_complete" || state.captureStandard <= 1
        pushLogToTeam(room, team, log, revealToOpponent, grabVictims = victims)
        room.gameLogger?.logStandardAction(
            room = room,
            actor = member.player,
            op = op,
            team = team.index,
            board = board,
            spellIndex = index,
            spell = spellAt(room, board, index),
            victims = victims
        )
    }

    private fun pushDirectorCaptureLog(
        room: Room,
        team: Team,
        index: Int,
        board: Int,
        op: String,
        victims: List<String>,
        actor: Player
    ) {
        val state = room.standardState ?: return
        val log = JsonObject(
            mapOf(
                "actor" to JsonPrimitive(actionActorName(room, actor)),
                "actor_team" to JsonPrimitive(actionActorTeam(room, actor)),
                "team" to JsonPrimitive(team.index),
                "op" to JsonPrimitive(op),
                "index" to JsonPrimitive(index),
                "board" to JsonPrimitive(board),
                "capture_count" to JsonPrimitive(
                    if (state.dualBoard) 1 else state.cells[index].captureCountOf(team.index)
                ),
                "capture_standard" to JsonPrimitive(state.captureStandard),
                "victims" to JsonArray(victims.map { JsonPrimitive(it) })
            )
        )
        val reached = victims.isNotEmpty() ||
            (if (state.dualBoard) state.fullyCapturedBy(index) != null else state.cells[index].capturedBy != null)
        pushLogToTeam(room, team, log, revealToOpponent = reached || state.captureStandard <= 1)
    }

    private fun pushLogPublic(room: Room, log: JsonObject) {
        room.host?.push("push_action_log", log)
        room.members.forEach { it.player.push("push_action_log", log) }
        room.watchers.forEach { it.push("push_action_log", log) }
    }

    /**
     * 操作日志可见性：看不到就不下发整条记录。
     * 导播/观众永远可见；操作方队伍全员可见；对方按 revealToOpponent 决定。
     * grabVictims 非空时，本次收取已产生抢卡日志：已收到抢卡日志的人
     * （受害者、导播、观众）不再重复收到收取日志。
     */
    private fun pushLogToTeam(
        room: Room,
        team: Team,
        log: JsonObject,
        revealToOpponent: Boolean,
        grabVictims: List<String> = emptyList()
    ) {
        if (grabVictims.isEmpty()) {
            room.host?.push("push_action_log", log)
            room.watchers.forEach { it.push("push_action_log", log) }
        }
        for (member in room.members) {
            if (member.name in grabVictims) continue
            if (member.team == team || revealToOpponent) {
                member.player.push("push_action_log", log)
            }
        }
    }
}
