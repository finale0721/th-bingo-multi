package org.tfcc.bingo.message

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.tfcc.bingo.BoardSpec

@Serializable
class NormalData {

    /** 玩家是处于盘A还是盘B */
    @SerialName("which_board_a")
    var whichBoardA = 0

    @SerialName("which_board_b")
    var whichBoardB = 0

    /** A盘面的传送门下标 */
    @SerialName("is_portal_a")
    var isPortalA = emptyArray<Int>()

    @SerialName("is_portal_b")
    var isPortalB = emptyArray<Int>()

    // 0x1: Left在A面收取 0x2: Left, B; Right = Left << 4
    @SerialName("get_on_which_board")
    var getOnWhichBoard = emptyArray<Int>()

    /** 6x6 extra winning lines */
    @SerialName("extra_lines")
    var extraLines: List<List<Int>> = emptyList()

    companion object {
        fun create(board: BoardSpec): NormalData {
            val data = NormalData()
            data.isPortalA = Array(board.area) { 0 }
            data.isPortalB = Array(board.area) { 0 }
            data.getOnWhichBoard = Array(board.area) { 0 }
            return data
        }
    }
}
