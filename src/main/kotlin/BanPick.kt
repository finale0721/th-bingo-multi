package org.tfcc.bingo

import org.tfcc.bingo.message.BanPickInfo
import org.tfcc.bingo.message.HandlerException

/**
* * -1：双方同时选择是否强制BanEX（两人都选完了立即决议，没选完之前一直可以修改）
* * 1：A保1（可保ex）
* * 2：B保1（可保ex）
* * 3：A保1（不可保ex）
* * 4：B保1（不可保ex）
* * 5：Aban1
* * 6：Bban1
* * 7：Bban1
* * 8：Aban1
* * 9：Aban1
* * 10：Bban1
* * 11：同时选择是否开启ex（两人都选完了立即结束，没选完之前一直可以修改）
* * 9999：结束
*
* 流程顺序：
* * -1（双方都强制）->9999（直接结束BP阶段：无保无ban，EX关闭，全部作品都可能出现）
* * -1（A强制）->1（A自动PASS）->2->5（A自动PASS）->6->7->8（A自动PASS）->9（A自动PASS）->10->9999
* * -1（B强制）->1->2（B自动PASS）->5->6（B自动PASS）->7（B自动PASS）->8->9->10（B自动PASS）->9999
* * 1->2（如果都保ex）->3->4->5->6->7->8->9->10->9999
* * 1->2（A保ex）->6->7->10->9999
* * 1->2（B保ex）->5->8->9->9999
* * 1->2（都没保ex）->5->6->7->8->9->10->11->9999
*
* @param whoFirst 0-左边玩家先，1-右边玩家先
*/
class BanPick(private val whoFirst: Int) {
    var phase = -1
        private set
    private val pick = arrayOf(arrayListOf<String>(), arrayListOf())
    private val ban = arrayOf(arrayListOf<String>(), arrayListOf())
    private var openEx = arrayOf(0, 0)
    private var forceBanEx = arrayOf(0, 0)

    /**
     * @param playerIndex 左边玩家0，右边玩家1，不是玩家-1
     */
    fun toPb(playerIndex: Int) = BanPickInfo(
        whoFirst,
        phase,
        if (phase > 4 || playerIndex != 1) pick[0]
        else if (phase > 2) pick[0].filter { it == "EX" }
        else emptyList(),
        ban[0],
        if (phase > 4 || playerIndex != 0) pick[1]
        else if (phase > 2) pick[1].filter { it == "EX" }
        else emptyList(),
        ban[1],
        if (phase > 4) openEx[0] else 0,
        if (phase > 4) openEx[1] else 0,
        visibleForceBanEx(0, playerIndex),
        visibleForceBanEx(1, playerIndex),
    ).let { if (whoFirst == 0) it else !it }

    /** 投票阶段只有本人能看到自己的投票，决议后双方可见 */
    private fun visibleForceBanEx(actor: Int, recipientIndex: Int): Int {
        if (phase != -1) return forceBanEx[actor]
        val recipientActor = if (whoFirst == 0) recipientIndex else 1 - recipientIndex
        return if (recipientActor == actor) forceBanEx[actor] else 0
    }

    fun notifyAll(room: Room) {
        room.host?.push("push_ban_pick", toPb(-1).encode())
        for ((idx, player) in room.players.withIndex()) {
            player?.push("push_ban_pick", toPb(idx).encode())
        }
        for (player in room.watchers) {
            player.push("push_ban_pick", toPb(-1).encode())
        }
    }

    fun onChoose(index: Int, selection: String): Int {
        if (index != 0 && index != 1) throw HandlerException("服务器内部错误")
        val playerIndex = if (whoFirst == 0) index else 1 - index
        if (phase == -1) {
            forceBanEx[playerIndex] = parseVote(selection)
            if (forceBanEx[0] != 0 && forceBanEx[1] != 0) {
                // 双方都强制：无人愿意参与BP，直接结束BP阶段（无保无ban，EX关闭）
                phase = if (forceBanEx[0] == 1 && forceBanEx[1] == 1) 9999 else 1
                autoPass()
            }
            return phase
        }
        if (phase == 11) {
            openEx[playerIndex] = parseVote(selection)
            if (openEx[0] != 0 && openEx[1] != 0) phase = 9999
            return phase
        }
        // 幂等：网络重试/连点重发的请求静默视为已生效，不报错、不重复记录
        if (selection.isNotEmpty() &&
            (selection in pick[playerIndex] || selection in ban[0] || selection in ban[1])
        ) return phase
        if (phase !in turn[playerIndex]) throw HandlerException("没有轮到你")
        if (selection.isNotEmpty()) {
            if (selection == "EX" && (forceBanEx[0] == 1 || forceBanEx[1] == 1))
                throw HandlerException("本局已强制BanEX")
            if (phase != 1 && phase != 2 && selection == "EX") throw HandlerException("不能选择EX")
            if (selection !in games && selection != "EX") throw HandlerException("参数错误")
        }
        if (phase <= 4) {
            pick[playerIndex].add(selection)
            if (selection == "EX") openEx = arrayOf(1, 1)
        } else {
            if (selection.isNotEmpty() && (selection in pick[0] || selection in pick[1]))
                throw HandlerException("已经保了的作品，不能ban")
            ban[playerIndex].add(selection)
        }
        phase = nextPhase(phase)
        autoPass()
        return phase
    }

    private fun parseVote(selection: String): Int = try {
        selection.toInt().also {
            if (it != -1 && it != 1) throw HandlerException("参数错误")
        }
    } catch (e: NumberFormatException) {
        throw HandlerException("参数错误：${e.message}")
    }

    private fun nextPhase(p: Int): Int = when (p) {
        1 -> 2
        2 -> if ("EX" in pick[0] && "EX" in pick[1]) 3 else if ("EX" in pick[0]) 6 else 5
        3 -> 4
        4 -> 5
        5 -> if ("EX" !in pick[0] && "EX" in pick[1]) 8 else 6
        6 -> 7
        7 -> if ("EX" in pick[0] && "EX" !in pick[1]) 10 else 8
        8 -> 9
        9 -> if ("EX" !in pick[0] && "EX" in pick[1]) 9999 else 10
        10 -> if (forceBanEx[0] == 1 || forceBanEx[1] == 1 || "EX" in pick[0] || "EX" in pick[1]) 9999 else 11
        else -> throw HandlerException("服务器内部错误")
    }

    /**
     * 强制BanEX的玩家剩余环节全部自动PASS（追加空选，与手动跳过一致）。
     * 双方都强制时不进入这里：投票决议时已直接结束BP（phase 9999）。
     */
    private fun autoPass() {
        while (phase in 1..10) {
            val actor = if (phase in turn[0]) 0 else 1
            if (forceBanEx[actor] != 1) break
            if (phase <= 4) pick[actor].add("") else ban[actor].add("")
            phase = nextPhase(phase)
        }
    }

    fun getGamesAndRanks(): Pair<Array<String>, Array<String>> {
        val games = games - ban[0].toSet() - ban[1].toSet()
        val ranks = if (openEx[0] == 1 && openEx[1] == 1) arrayOf("L", "EX", "PH") else arrayOf("L")
        return games.toTypedArray() to ranks
    }

    /**
     * 保底需求：作品码 -> 需要出现在盘面上的数量。
     * 每个保的作品都只计N张，双方保同一作品时仍为N张；EX与PASS（空选）不计入。
     */
    fun pickRequirements(count: Int): Map<String, Int> = (pick[0] + pick[1])
        .filter { it.isNotEmpty() && it != "EX" }
        .distinct()
        .associateWith { count }

    companion object {
        private val turn = arrayOf(listOf(1, 3, 5, 8, 9), listOf(2, 4, 6, 7, 10))
        private val games = listOf("6", "7", "8", "10", "11", "12", "13", "14", "15", "16", "17", "18")
    }
}
