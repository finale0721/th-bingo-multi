package org.tfcc.bingo

import org.apache.logging.log4j.kotlin.logger
import org.tfcc.bingo.message.HandlerException
import kotlin.random.Random

/**
 * 保作品的保底生成：BP完成后抽卡时，保证双方保的具体作品（不含EX）在盘面上至少出现设定数量。
 *
 * 替换规则：保持星级与来源（L/EX/PH）不变，从本次抽卡的剩余卡池中取同(星级,来源)的保底作品卡，
 * 按洗牌后的下标序列逐个检定替换；多作品时随机顺序依次保底；单次生成最多尝试6次（含首次），
 * 仍无法满足则抛[FAIL_MESSAGE]，由外层抽卡循环走整体重新生成流程。
 */
object PickGuarantee {
    const val FAIL_MESSAGE = "保底生成失败"

    /** 单次生成内的最大尝试次数（首次1次+洗牌重试5次） */
    private const val MAX_ATTEMPTS = 6

    private val logger = logger()

    /**
     * @throws HandlerException 重试耗尽仍不满足保底要求时抛出，消息为[FAIL_MESSAGE]
     */
    fun apply(room: Room) {
        val count = room.roomConfig.pickGuaranteeCount
        if (count <= 0) return
        val bp = room.banPick ?: return
        if (bp.phase != 9999) return
        val original = room.spells ?: return
        val requirements = bp.pickRequirements(count)
        if (requirements.isEmpty()) return

        // 剩余卡池（不在盘面上的卡），替换为双向交换，池大小保持不变
        val pool = SpellConfig.getSpellLeftCache().values.flatMap { it.values }.flatten()
        val pending = HashMap<String, Int>()
        for ((game, need) in requirements) {
            val have = original.count { it.game == game }
            if (have >= need) continue
            val total = have + pool.count { it.game == game }
            if (total < need) {
                // 盘面+剩余池全部该作品都不够，无法达成，跳过以避免房间无法开局
                logger.warn("保底无法达成：作品 $game 共 $total 张，少于所需 $need 张，跳过")
                continue
            }
            pending[game] = need
        }
        if (pending.isEmpty()) return

        val rand = Random.Default
        repeat(MAX_ATTEMPTS) {
            val board = original.copyOf()
            val poolList = pool.toMutableList()
            val indices = board.indices.shuffled(rand)
            var ok = true
            for (game in pending.keys.shuffled(rand)) {
                val need = pending.getValue(game)
                var have = board.count { it.game == game }
                for (i in indices) {
                    if (have >= need) break
                    val cur = board[i]
                    if (cur.game == game) continue
                    if (cur.game in requirements) continue // 不夺走其他保底作品的卡
                    val candIndex = poolList.indexOfFirst {
                        it.game == game && it.star == cur.star && it.rank == cur.rank
                    }
                    if (candIndex < 0) continue
                    val candidate = poolList.removeAt(candIndex)
                    poolList.add(cur)
                    board[i] = candidate
                    have++
                }
                if (have < need) {
                    ok = false
                    break
                }
            }
            if (ok) {
                room.spells = board
                SpellConfig.commitRollCache(poolList)
                return
            }
        }
        throw HandlerException(FAIL_MESSAGE)
    }
}
