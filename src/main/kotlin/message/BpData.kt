package org.tfcc.bingo.message

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.tfcc.bingo.BoardSpec

/**
 * @param banPick 0-选，1-ban
 */
@Serializable
class BpData(
    @SerialName("whose_turn")
    var whoseTurn: Int,
    @SerialName("ban_pick")
    var banPick: Int,
) {
    @Transient
    var round: Int = 0

    @Transient
    var lessThan4 = false

    /** 左边玩家符卡失败次数 */
    @SerialName("spell_failed_count_a")
    var spellFailedCountA = intArrayOf()

    /** 右边玩家符卡失败次数 */
    @SerialName("spell_failed_count_b")
    var spellFailedCountB = intArrayOf()

    companion object {
        fun create(board: BoardSpec, whoseTurn: Int, banPick: Int): BpData {
            val data = BpData(whoseTurn, banPick)
            data.spellFailedCountA = IntArray(board.area)
            data.spellFailedCountB = IntArray(board.area)
            return data
        }
    }
}
