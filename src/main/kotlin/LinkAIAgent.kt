package org.tfcc.bingo

import org.apache.logging.log4j.kotlin.logger
import org.tfcc.bingo.SpellStatus.RIGHT_SELECT
import org.tfcc.bingo.message.HandlerException
import java.util.PriorityQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class LinkAIAgent(private val room: Room) {
    private val logger = logger()
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val aiPlayerIndex = 1

    private data class AttemptTask(
        val index: Int,
        val startMs: Long,
        val durationMs: Long,
        val success: Boolean,
    )

    private lateinit var gridModels: List<AICardModel>
    private var currentTask: AttemptTask? = null

    @Volatile
    private var speedrunRequested = false

    private var wasTakeoverActive = false

    fun start() {
        wasTakeoverActive = false
        initializeGridModels()
        executor.scheduleAtFixedRate(this::tick, 0, 500, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        executor.shutdownNow()
    }

    fun speedrun() {
        speedrunRequested = true
        if (!executor.isShutdown) executor.execute(this::tick)
    }

    private fun tick() {
        try {
            if (!room.started || room.linkData == null || room.spells == null) return
            if (room.phase == 1) {
                val takeoverActive = room.linkData!!.takeoverPlayerIndex == aiPlayerIndex
                if (takeoverActive) {
                    wasTakeoverActive = true
                    return
                }
                if (wasTakeoverActive) {
                    wasTakeoverActive = false
                    onTakeoverReleased()
                    return
                }
                ensureRoute()
                return
            }
            if (room.phase != 2 || linkFinished()) return
            if (speedrunRequested) {
                speedrunRequested = false
                finishRemainingImmediately()
                return
            }
            runStep()
        } catch (e: HandlerException) {
            logger.warn("Link AI tick failed: ${e.message}")
        } catch (e: Throwable) {
            logger.error("Exception in Link AI tick", e)
        }
    }

    private fun ensureRoute() {
        val linkData = room.linkData ?: return
        if (linkData.routeConfirmedB) return
        val route = findBestRoute()
        val currentRoute = linkData.linkIdxB
        route.drop(currentRoute.size).forEach { RoomTypeLink.appendRoute(room, aiPlayerIndex, it) }
        RoomTypeLink.confirmRoute(room, aiPlayerIndex, true)
    }

    private fun onTakeoverReleased() {
        val linkData = room.linkData ?: return
        linkData.routeConfirmedB = false
        val route = linkData.linkIdxB
        if (route.isEmpty()) {
            ensureRoute()
            return
        }
        val end = room.roomConfig.linkEndB
        val disabled = linkData.disabledIdx.toSet() + room.roomConfig.linkDisabledIdx.toSet()

        var lastReachableIdx = -1
        for (i in route.indices) {
            val blocked = disabled + route.subList(0, i).toSet()
            if (isReachable(route[i], end, blocked)) {
                lastReachableIdx = i
            }
        }

        if (lastReachableIdx < 0) {
            route.clear()
            RoomTypeLink.recalculateScoresAndPush(room, logSpeedrun = false)
            ensureRoute()
            return
        }

        val prefix = ArrayList(route.subList(0, lastReachableIdx + 1))
        val start = route[lastReachableIdx]
        val blocked = disabled + prefix.subList(0, prefix.size - 1).toSet()
        val bestFromHere = findBestRoute(start, end, blocked)

        route.clear()
        route.addAll(prefix.subList(0, prefix.size - 1))
        route.addAll(bestFromHere)

        RoomTypeLink.recalculateScoresAndPush(room, logSpeedrun = false)
        RoomTypeLink.confirmRoute(room, aiPlayerIndex, true)
    }

    private fun runStep() {
        val linkData = room.linkData ?: return
        val route = linkData.linkIdxB
        if (linkData.currentStepB >= route.size) return
        val idx = route[linkData.currentStepB]
        if (linkData.statusB[idx] != RIGHT_SELECT.value) {
            val cdRemain = linkData.lastGetTimeB + room.actualCdTime[aiPlayerIndex] - System.currentTimeMillis()
            if (cdRemain > 1000L) return
            RoomTypeLink.selectNext(room, aiPlayerIndex)
            return
        }

        val task = currentTask
        if (task == null || task.index != idx) {
            currentTask = newAttempt(idx)
            return
        }
        if (System.currentTimeMillis() < task.startMs + task.durationMs) return

        currentTask = null
        if (task.success) {
            RoomTypeLink.finishSelected(room, aiPlayerIndex, skip = false, expectedIndex = idx)
        } else if (canSkipAfterFailure(idx)) {
            RoomTypeLink.finishSelected(room, aiPlayerIndex, skip = true, expectedIndex = idx)
        } else {
            currentTask = newAttempt(idx)
        }
    }

    private fun finishRemainingImmediately() {
        val linkData = room.linkData ?: return
        val route = linkData.linkIdxB
        var virtualUsedMs = activeUsedMs()
        var virtualNow = linkData.startMsB.takeIf { it > 0L }?.plus(virtualUsedMs) ?: System.currentTimeMillis()
        while (linkData.currentStepB < route.size) {
            val idx = route[linkData.currentStepB]
            linkData.statusB[idx] = RIGHT_SELECT.value
            room.gameLogger?.logLinkAction(room, aiPlayerIndex, "link_next_card", idx, virtualNow)
            var elapsedOnCell = 0L
            while (true) {
                val task = newAttempt(idx, virtualStartMs = 0L)
                elapsedOnCell += task.durationMs
                virtualNow += task.durationMs
                if (task.success) {
                    virtualUsedMs += elapsedOnCell
                    finishVirtualSelected(idx, skip = false, virtualNow = virtualNow)
                    break
                }
                if (canSkipAfterVirtualFailure(idx, elapsedOnCell)) {
                    virtualUsedMs += elapsedOnCell
                    finishVirtualSelected(idx, skip = true, virtualNow = virtualNow)
                    break
                }
            }
        }
        val start = linkData.startMsB.takeIf { it > 0L } ?: System.currentTimeMillis()
        linkData.startMsB = start
        val totalCd = (route.size - 1).coerceAtLeast(0) * room.actualCdTime[aiPlayerIndex].coerceAtLeast(0L)
        linkData.endMsB = start + virtualUsedMs + totalCd
        linkData.lastGetTimeB = linkData.endMsB
        linkData.eventB = 3
        currentTask = null
        RoomTypeLink.recalculateScoresAndPush(room, logSpeedrun = false)
    }

    private fun finishVirtualSelected(idx: Int, skip: Boolean, virtualNow: Long) {
        val linkData = room.linkData ?: return
        val route = linkData.linkIdxB
        val currentStep = linkData.currentStepB
        if (currentStep >= route.size || route[currentStep] != idx) return
        if (skip) {
            val allowed = skipLimit()
            linkData.skipUsedB = (linkData.skipUsedB + 1).coerceAtMost(allowed)
            linkData.skippedIdxB.add(idx)
        } else {
            linkData.skippedIdxB.remove(idx)
        }
        linkData.statusB[idx] = SpellStatus.RIGHT_GET.value
        linkData.currentStepB = currentStep + 1
        linkData.lastGetTimeB = virtualNow
        if (linkData.currentStepB >= route.size) {
            linkData.eventB = 3
            linkData.endMsB = virtualNow
        }
        RoomTypeLink.recalculateScoresAndPush(room, logSpeedrun = false)
        room.gameLogger?.logLinkAction(
            room,
            aiPlayerIndex,
            if (skip) "link_skip_card" else "link_finish_card",
            idx,
            virtualNow,
        )
    }

    private fun newAttempt(index: Int, virtualStartMs: Long? = null): AttemptTask {
        val model = gridModels[index]
        val success = Random.nextFloat() < model.successRate
        val duration = ((if (success) model.successTime else model.failureTime) * 1000).toLong().coerceAtLeast(1L)
        return AttemptTask(index, virtualStartMs ?: System.currentTimeMillis(), duration, success)
    }

    private fun canSkipAfterFailure(index: Int): Boolean {
        val linkData = room.linkData ?: return false
        if (linkData.skipUsedB >= skipLimit()) return false
        val startedAt = linkData.lastGetTimeB
        val waitMs = ((room.spells!![index].star + 1) * 60_000L)
        return System.currentTimeMillis() - startedAt - room.actualCdTime[aiPlayerIndex] >= waitMs
    }

    private fun canSkipAfterVirtualFailure(index: Int, elapsedOnCell: Long): Boolean {
        val linkData = room.linkData ?: return false
        if (linkData.skipUsedB >= skipLimit()) return false
        val waitMs = ((room.spells!![index].star + 1) * 60_000L)
        return elapsedOnCell >= waitMs
    }

    private fun skipLimit(): Int = if ((room.linkData?.linkIdxB?.size ?: 0) > 10) 2 else 1

    private fun linkFinished(): Boolean = room.linkData?.eventB == 3

    private fun activeUsedMs(): Long {
        val linkData = room.linkData ?: return 0L
        if (linkData.startMsB <= 0L) return 0L
        val now = System.currentTimeMillis()
        val elapsed = (now - linkData.startMsB).coerceAtLeast(0L)
        val step = linkData.currentStepB
        val cd = room.actualCdTime[aiPlayerIndex].coerceAtLeast(0L)
        val completedCd = ((step - 1).coerceAtLeast(0)) * cd
        val currentCd = if (step <= 0 || linkData.lastGetTimeB <= 0L) 0L else (now - linkData.lastGetTimeB).coerceIn(0L, cd)
        return (elapsed - completedCd - currentCd).coerceAtLeast(0L)
    }

    private fun initializeGridModels() {
        gridModels = AICardModelFactory.create(room)
    }

    private fun cellValue(index: Int): Double {
        val spell = room.spells!![index]
        val model = gridModels[index]
        return spell.star * room.roomConfig.linkLevelCoefficient +
            spell.fastest * room.roomConfig.linkFastestCoefficient -
            model.expectedTime
    }

    private fun findBestRoute(): List<Int> {
        val start = room.roomConfig.linkStartB
        val end = room.roomConfig.linkEndB
        val disabled = room.linkData?.disabledIdx?.toSet() ?: room.roomConfig.linkDisabledIdx.toSet()
        return findBestRoute(start, end, disabled)
    }

    private fun findBestRoute(start: Int, end: Int, disabled: Set<Int>): List<Int> {
        data class Candidate(val path: List<Int>, val score: Double)
        var beam = listOf(Candidate(listOf(start), cellValue(start)))
        val finished = mutableListOf<Candidate>()
        val maxBeam = 700
        repeat(room.boardArea) {
            val nextBeam = mutableListOf<Candidate>()
            for (candidate in beam) {
                val current = candidate.path.last()
                if (current == end) {
                    finished.add(candidate)
                    continue
                }
                for (next in neighbors(current)) {
                    if (next in disabled || next in candidate.path) continue
                    if (!isReachable(next, end, disabled + candidate.path)) continue
                    nextBeam.add(Candidate(candidate.path + next, candidate.score + cellValue(next)))
                }
            }
            if (nextBeam.isEmpty()) return@repeat
            beam = nextBeam.sortedByDescending { it.score }.take(maxBeam)
        }
        return finished.maxByOrNull { it.score }?.path ?: shortestPath(start, end, disabled)
    }

    private fun neighbors(index: Int): List<Int> {
        val board = room.boardSpec
        val row = board.row(index)
        val col = board.col(index)
        val offsets = if (room.roomConfig.linkConnectivity == 4) {
            listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
        } else {
            listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1, -1 to -1, -1 to 1, 1 to -1, 1 to 1)
        }
        return offsets.mapNotNull { (dr, dc) ->
            val nr = row + dr
            val nc = col + dc
            if (nr in 0 until board.size && nc in 0 until board.size) board.index(nr, nc) else null
        }
    }

    private fun isReachable(start: Int, end: Int, blocked: Set<Int>): Boolean {
        val queue: PriorityQueue<Int> = PriorityQueue()
        val seen = mutableSetOf(start)
        queue.add(start)
        while (queue.isNotEmpty()) {
            val current = queue.remove()
            if (current == end) return true
            for (next in neighbors(current)) {
                if (next !in blocked && seen.add(next)) queue.add(next)
            }
        }
        return false
    }

    private fun shortestPath(start: Int, end: Int, disabled: Set<Int>): List<Int> {
        val queue: java.util.ArrayDeque<Int> = java.util.ArrayDeque()
        val prev = HashMap<Int, Int>()
        val seen = mutableSetOf(start)
        queue.add(start)
        while (!queue.isEmpty()) {
            val current = queue.remove()
            if (current == end) {
                val path = ArrayList<Int>()
                var p = current
                path.add(p)
                while (p != start) {
                    p = prev[p] ?: return listOf(start)
                    path.add(p)
                }
                path.reverse()
                return path
            }
            for (next in neighbors(current)) {
                if (next in disabled || !seen.add(next)) continue
                prev[next] = current
                queue.add(next)
            }
        }
        return listOf(start)
    }
}
