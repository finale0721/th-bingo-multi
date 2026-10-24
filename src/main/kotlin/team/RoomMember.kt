package org.tfcc.bingo.team

import org.tfcc.bingo.Player

/**
 * 房间成员。选手与观众的区别由“是否在 room.members 中”表示。
 *
 * 下列字段是单局运行态，换局（initStatus）时重置。
 */
class RoomMember(
    val player: Player,
    var team: Team,
    var captain: Boolean = false,
) {
    val name: String
        get() = player.name

    /** 已确认选中的盘面；单盘恒为 0 */
    var selectedBoard: Int = 0

    /** 已确认选中的格子；-1 表示没有选卡 */
    var selectedIndex: Int = -1

    /** 上次收卡时间戳 */
    var lastGetTime: Long = 0L

    /** 本局实际 CD（基础 CD + 队伍修正），单位毫秒 */
    var actualCdTime: Long = 0L

    /** 换卡预扣尚未结算 */
    var changePending: Boolean = false
    var changeOriginBoard: Int = -1
    var changeOriginIndex: Int = -1

    /** 换局时清空单局运行态，保留队伍与队长身份 */
    fun resetRoundState() {
        selectedBoard = 0
        selectedIndex = -1
        lastGetTime = 0L
        actualCdTime = 0L
        changePending = false
        changeOriginBoard = -1
        changeOriginIndex = -1
    }
}
