package org.tfcc.bingo.message

import io.netty.channel.ChannelHandlerContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.tfcc.bingo.*
import org.tfcc.bingo.standard.CellView
import org.tfcc.bingo.standard.StandardGame

object GetAllSpellsHandler : RequestHandler {
    @Throws(HandlerException::class)
    override fun handle(ctx: ChannelHandlerContext, player: Player, data: JsonElement?): JsonElement {
        val room = player.room ?: throw HandlerException("不在房间里")
        room.started || throw HandlerException("游戏还未开始")
        room.standardState != null || throw HandlerException("游戏还未开始")
        val member = room.memberOf(player)
        var leftTime = 0L
        var status = 0
        var leftCdTime = 0L
        var now = System.currentTimeMillis()
        val countdown = room.roomConfig.countdown * 1000L
        val gameTime = room.roomConfig.gameTime * 60000L
        if (room.started) {
            if (now < room.startMs + countdown) {
                status = 1 // 倒计时
                leftTime = room.startMs + countdown - now
            } else if (now < room.startMs + countdown + gameTime + room.totalPauseMs) {
                status = 2 // 进行中
                if (room.pauseBeginMs > 0) {
                    status = 3 // 暂停中
                    now = room.pauseBeginMs
                }
                leftTime = room.startMs + countdown + gameTime + room.totalPauseMs - now
                if (member != null) {
                    leftCdTime = (member.actualCdTime - (now - member.lastGetTime)).coerceAtLeast(0L)
                }
            } else {
                status = 4 // 已结束
            }
        }
        return AllSpellsResponse(
            spells = room.spells!!,
            spells2 = room.spells2,
            cells = StandardGame.cellViews(room, 0, member),
            cells2 = if (room.roomConfig.dualBoard > 0) StandardGame.cellViews(room, 1, member) else null,
            mySelectedIndex = member?.selectedIndex ?: -1,
            mySelectedBoard = member?.selectedBoard ?: 0,
            myTeam = member?.team?.index,
            isCaptain = member?.captain ?: false,
            captureStandard = room.standardState!!.captureStandard,
            changeCardCount = room.changeCardCount.toList(),
            myChangePending = member?.changePending ?: false,
            myChangeOriginIndex = member?.changeOriginIndex ?: -1,
            myChangeOriginBoard = member?.changeOriginBoard ?: -1,
            leftTime = leftTime,
            status = status,
            leftCdTime = leftCdTime,
            extraLines = room.standardState!!.extraLines,
            roomConfig = room.roomConfig,
        ).encode()
    }
}

@Serializable
class AllSpellsResponse(
    val spells: Array<Spell>,
    val spells2: Array<Spell>?,
    val cells: List<CellView>,
    @SerialName("cells2")
    val cells2: List<CellView>?,
    @SerialName("my_selected_index")
    val mySelectedIndex: Int,
    @SerialName("my_selected_board")
    val mySelectedBoard: Int,
    @SerialName("my_team")
    val myTeam: Int?,
    @SerialName("is_captain")
    val isCaptain: Boolean,
    @SerialName("capture_standard")
    val captureStandard: Int,
    /** A/B 队共享换卡次数 */
    @SerialName("change_card_count")
    val changeCardCount: List<Int>,
    @SerialName("my_change_pending")
    val myChangePending: Boolean,
    @SerialName("my_change_origin_index")
    val myChangeOriginIndex: Int,
    @SerialName("my_change_origin_board")
    val myChangeOriginBoard: Int,
    /** 倒计时剩余时间，单位：毫秒 */
    @SerialName("left_time")
    val leftTime: Long,
    /** 0-未开始，1-赛前倒计时中，2-开始，3-暂停中，4-结束 */
    val status: Int,
    /** 选卡cd剩余时间，单位：毫秒 */
    @SerialName("left_cd_time")
    val leftCdTime: Long,
    @SerialName("extra_lines")
    val extraLines: List<List<Int>>,
    @SerialName("room_config")
    val roomConfig: RoomConfig,
)
