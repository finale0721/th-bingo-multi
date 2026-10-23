package org.tfcc.bingo.message

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class LinkData {
    @SerialName("link_idx_a")
    val linkIdxA = ArrayList<Int>()

    @SerialName("link_idx_b")
    val linkIdxB = ArrayList<Int>()

    @SerialName("start_ms_a")
    var startMsA = 0L

    @SerialName("end_ms_a")
    var endMsA = 0L

    @SerialName("event_a")
    var eventA = 0

    @SerialName("start_ms_b")
    var startMsB = 0L

    @SerialName("end_ms_b")
    var endMsB = 0L

    @SerialName("event_b")
    var eventB = 0

    @SerialName("route_confirmed_a")
    var routeConfirmedA = false

    @SerialName("route_confirmed_b")
    var routeConfirmedB = false

    @SerialName("current_step_a")
    var currentStepA = 0

    @SerialName("current_step_b")
    var currentStepB = 0

    @SerialName("status_a")
    var statusA = ArrayList<Int>()

    @SerialName("status_b")
    var statusB = ArrayList<Int>()

    @SerialName("last_get_time_a")
    var lastGetTimeA = 0L

    @SerialName("last_get_time_b")
    var lastGetTimeB = 0L

    @SerialName("skip_used_a")
    var skipUsedA = 0

    @SerialName("skip_used_b")
    var skipUsedB = 0

    @SerialName("skipped_idx_a")
    var skippedIdxA = ArrayList<Int>()

    @SerialName("skipped_idx_b")
    var skippedIdxB = ArrayList<Int>()

    @SerialName("score_a")
    var scoreA = 0.0

    @SerialName("score_b")
    var scoreB = 0.0

    @SerialName("disabled_idx")
    var disabledIdx = ArrayList<Int>()

    @SerialName("takeover_player_index")
    var takeoverPlayerIndex: Int = -1

    fun ensureStatusSize(area: Int) {
        while (statusA.size < area) statusA.add(0)
        while (statusB.size < area) statusB.add(0)
        while (statusA.size > area) statusA.removeAt(statusA.lastIndex)
        while (statusB.size > area) statusB.removeAt(statusB.lastIndex)
    }

    fun copy(): LinkData {
        val cloned = LinkData()
        cloned.linkIdxA.addAll(linkIdxA)
        cloned.linkIdxB.addAll(linkIdxB)
        cloned.startMsA = startMsA
        cloned.endMsA = endMsA
        cloned.eventA = eventA
        cloned.startMsB = startMsB
        cloned.endMsB = endMsB
        cloned.eventB = eventB
        cloned.routeConfirmedA = routeConfirmedA
        cloned.routeConfirmedB = routeConfirmedB
        cloned.currentStepA = currentStepA
        cloned.currentStepB = currentStepB
        cloned.statusA.addAll(statusA)
        cloned.statusB.addAll(statusB)
        cloned.lastGetTimeA = lastGetTimeA
        cloned.lastGetTimeB = lastGetTimeB
        cloned.skipUsedA = skipUsedA
        cloned.skipUsedB = skipUsedB
        cloned.skippedIdxA.addAll(skippedIdxA)
        cloned.skippedIdxB.addAll(skippedIdxB)
        cloned.scoreA = scoreA
        cloned.scoreB = scoreB
        cloned.disabledIdx.addAll(disabledIdx)
        cloned.takeoverPlayerIndex = takeoverPlayerIndex
        return cloned
    }
}
