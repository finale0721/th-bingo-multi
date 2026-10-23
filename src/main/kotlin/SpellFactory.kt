package org.tfcc.bingo

import org.tfcc.bingo.message.HandlerException
import java.util.*
import java.util.concurrent.ThreadLocalRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * Identifies the difficulty/spell-generation mode for a game.
 * Each mode determines how star arrays are built and which SpellConfig draw method is used.
 */
enum class DifficultyMode {
    /** Standard: E/N/L with 3-level profiles (1/2/3★ + high-level 4/5★) */
    NORMAL,

    /** OD/ODP: extended 7-level profiles with upgrade logic (1-7★) */
    OD,

    /** Custom: user-defined star counts with fixed high-level layout option */
    CUSTOM,

    /** BP: fixed 3-star at specific positions, rest 1/2★ */
    BP,

    /** BPOD/ODPBP: BP with OD-style upgrade logic */
    BP_OD,

    /** Link: fixed corner/center positions */
    LINK,
}

object SpellFactory {
    // ---- Board-level star placement ----

    /**
     * Generate high-level (4/5-star) placement positions using BoardSpec.
     * Ensures exactly one high-star per row and column.
     * Odd boards place the center cell; even boards place two cells in the center 2x2.
     */
    fun highLevelPositions(board: BoardSpec, rand: Random): IntArray {
        val size = board.size
        val perm = (0 until size).toMutableList()
        perm.shuffle(rand)

        if (size % 2 == 1) {
            val mid = size / 2
            val centerCol = mid
            val currentColAtMid = perm[mid]
            val indexOfCenterCol = perm.indexOf(centerCol)
            perm[mid] = centerCol
            perm[indexOfCenterCol] = currentColAtMid
        } else {
            val mid1 = size / 2 - 1
            val mid2 = size / 2
            if (perm[mid1] != mid1 && perm[mid1] != mid2) {
                val target = if (perm[mid2] == mid1 || perm[mid2] == mid2) perm[mid2] else mid1
                val idx = perm.indexOf(target)
                val tmp = perm[mid1]
                perm[mid1] = target
                perm[idx] = tmp
            }
            if (perm[mid2] != mid1 && perm[mid2] != mid2) {
                val otherCenterCol = if (perm[mid1] == mid1) mid2 else mid1
                val idx = perm.indexOf(otherCenterCol)
                val tmp = perm[mid2]
                perm[mid2] = otherCenterCol
                perm[idx] = tmp
            }
        }

        val positions = IntArray(size) { row -> board.index(row, perm[row]) }
        validateHighLevelPositions(board, positions)
        return positions
    }

    private fun validateHighLevelPositions(board: BoardSpec, positions: IntArray) {
        val size = board.size
        if (positions.size != size || positions.toSet().size != size) {
            throw HandlerException("4/5星符卡数量不足")
        }

        val rows = BooleanArray(size)
        val cols = BooleanArray(size)
        for (position in positions) {
            if (!board.isValidIndex(position)) {
                throw HandlerException("4/5星符卡数量不足")
            }
            val row = board.row(position)
            val col = board.col(position)
            if (rows[row] || cols[col]) {
                throw HandlerException("4/5星符卡数量不足")
            }
            rows[row] = true
            cols[col] = true
        }

        if (!rows.all { it } || !cols.all { it }) {
            throw HandlerException("4/5星符卡数量不足")
        }

        if (size % 2 == 1) {
            if (board.index(size / 2, size / 2) !in positions) {
                throw HandlerException("4/5星符卡数量不足")
            }
        } else {
            val mid1 = size / 2 - 1
            val mid2 = size / 2
            val centerCount = positions.count {
                val row = board.row(it)
                val col = board.col(it)
                (row == mid1 || row == mid2) && (col == mid1 || col == mid2)
            }
            if (centerCount != 2) {
                throw HandlerException("4/5星符卡数量不足")
            }
        }
    }

    /**
     * Assign star values to board positions.
     * High-level positions get stars from [highStars]; remaining positions get stars from [lowStars].
     */
    private fun assignStarsToBoard(
        board: BoardSpec,
        highPositions: IntArray,
        highStars: IntArray,
        lowStars: IntArray,
    ): IntArray {
        val stars = IntArray(board.area)
        var lowIdx = 0
        var hiIdx = 0
        val highSet = highPositions.toSet()
        for (i in 0 until board.area) {
            stars[i] = if (i in highSet) highStars[hiIdx++] else lowStars[lowIdx++]
        }
        return stars
    }

    private fun usesFixedHighLevelLayout(mode: DifficultyMode, customSettings: IntArray?): Boolean {
        return mode == DifficultyMode.NORMAL ||
            mode == DifficultyMode.OD ||
            (mode == DifficultyMode.CUSTOM && customSettings?.getOrNull(5) == 1)
    }

    private fun fixedHighLevelIndices(
        mode: DifficultyMode,
        stars: IntArray,
        board: BoardSpec,
        customSettings: IntArray?,
    ): Set<Int> {
        if (!usesFixedHighLevelLayout(mode, customSettings)) return emptySet()
        return fixedHighLevelIndices(stars, board)
    }

    fun fixedHighLevelIndices(stars: IntArray, board: BoardSpec): Set<Int> {
        if (stars.size != board.area) throw HandlerException("4/5星符卡数量不足")

        val size = board.size
        val selected = IntArray(size) { -1 }
        val usedCols = BooleanArray(size)
        val rowOrder = if (size % 2 == 0) {
            val mid1 = size / 2 - 1
            val mid2 = size / 2
            listOf(mid1, mid2) + (0 until size).filter { it != mid1 && it != mid2 }
        } else {
            val mid = size / 2
            listOf(mid) + (0 until size).filter { it != mid }
        }

        fun candidates(row: Int): List<Int> {
            val cols = (0 until size).filter { col ->
                val star = stars[board.index(row, col)]
                star == 4 || star == 5
            }
            return when {
                size % 2 == 1 && row == size / 2 -> cols.filter { it == size / 2 }
                size % 2 == 0 && (row == size / 2 - 1 || row == size / 2) ->
                    cols.filter { it == size / 2 - 1 || it == size / 2 }
                else -> cols
            }
        }

        fun search(orderIndex: Int): Boolean {
            if (orderIndex == rowOrder.size) {
                val positions = IntArray(size) { row -> board.index(row, selected[row]) }
                validateHighLevelPositions(board, positions)
                return true
            }

            val row = rowOrder[orderIndex]
            for (col in candidates(row)) {
                if (usedCols[col]) continue
                selected[row] = col
                usedCols[col] = true
                if (search(orderIndex + 1)) return true
                usedCols[col] = false
                selected[row] = -1
            }
            return false
        }

        if (!search(0)) throw HandlerException("4/5星符卡数量不足")
        return selected.mapIndexed { row, col -> board.index(row, col) }.toSet()
    }

    // ---- Star array builders by mode ----

    private fun normalizeProfileCounts(counts: IntArray): IntArray {
        return when (counts.size) {
            3 -> intArrayOf(counts[0], counts[1], counts[2], 0, 0, 0, 0)
            7 -> counts
            else -> throw HandlerException("难度配置格式错误")
        }
    }

    private fun profileStars(counts: IntArray, expectedTotal: Int): IntArray {
        val da = normalizeProfileCounts(counts)
        if (da.sum() != expectedTotal) {
            throw HandlerException("难度配置数量与棋盘尺寸不匹配")
        }
        return IntArray(da[0]) { 1 } + IntArray(da[1]) { 2 } +
            IntArray(da[2]) { 3 } + IntArray(da[3]) { 4 } +
            IntArray(da[4]) { 5 } + IntArray(da[5]) { 6 } +
            IntArray(da[6]) { 7 }
    }

    /**
     * Build a star array for NORMAL mode using 7-level DifficultyProfile.
     */
    @Throws(HandlerException::class)
    private fun buildNormalStarArray(difficulty: Difficulty, boardSize: Int): IntArray {
        val rand = ThreadLocalRandom.current().asKotlinRandom()
        val board = BoardSpec(boardSize)
        val highCount = board.size
        val lvCount = when (difficulty) {
            Difficulty.E -> DifficultyProfile.E.counts(boardSize)
            Difficulty.N -> DifficultyProfile.N.counts(boardSize)
            Difficulty.L -> DifficultyProfile.L.counts(boardSize)
            else -> DifficultyProfile.scaleCounts(difficulty.value, board.area - highCount)
        }

        val lowStars = profileStars(lvCount, board.area - highCount)
        lowStars.shuffle(rand)
        val highStars = IntArray(highCount - 1) { 4 } + IntArray(1) { 5 }
        highStars.shuffle(rand)

        return assignStarsToBoard(board, highLevelPositions(board, rand), highStars, lowStars)
    }

    /**
     * Build a star array for OD mode (7-level profiles with upgrade placeholders).
     * Low positions: 1-3★ base + 4-7★ from profile. High positions: 4/5★.
     * Star values 6 and 7 are placeholders that trigger upgrade logic in SpellConfig.getOD().
     */
    @Throws(HandlerException::class)
    private fun buildODStarArray(difficulty: Int, boardSize: Int): IntArray {
        val profile = when (difficulty) {
            4 -> DifficultyProfile.OD
            5 -> DifficultyProfile.ODP
            else -> DifficultyProfile.LDefault
        }
        val rand = ThreadLocalRandom.current().asKotlinRandom()
        val board = BoardSpec(boardSize)
        val highCount = board.size

        val lowStars = profileStars(profile.counts(boardSize), board.area - highCount)
        lowStars.shuffle(rand)
        val highStars = IntArray(highCount - 1) { 4 } + IntArray(1) { 5 }
        highStars.shuffle(rand)

        return assignStarsToBoard(board, highLevelPositions(board, rand), highStars, lowStars)
    }

    /**
     * Build a star array for CUSTOM mode.
     * s = 11-element array from client custom config.
     * s[0..4] = counts for stars 1-5, s[5] = fixed high-level layout flag, s[6] = downgrade flag,
     * s[7..8] = counts for high-level 4/5★, s[9] = ex position mode, s[10] = ex position count.
     */
    @Throws(HandlerException::class)
    private fun buildCustomStarArray(s: IntArray, boardSize: Int): IntArray {
        val board = BoardSpec(boardSize)
        val boardArea = board.area
        val highCount = board.size

        if (s[0] + s[1] + s[2] + s[3] + s[4] != boardArea) {
            return buildNormalStarArray(Difficulty.L, boardSize)
        }

        val rand = ThreadLocalRandom.current().asKotlinRandom()

        if (s[5] == 1) {
            if (s[3] + s[4] < highCount) {
                throw HandlerException("4/5星符卡数量不足")
            }
            val s7 = if (s[7] + s[8] != highCount) {
                highCount - 1
            } else {
                s[7]
            }
            val s8 = if (s[7] + s[8] != highCount) {
                1
            } else {
                s[8]
            }
            val highStars = IntArray(s7) { 4 } + IntArray(s8) { 5 }
            highStars.shuffle(rand)

            val diff = if (s[6] == 1) {
                intArrayOf(s[0], s[1], s[2], 0, 0, s[3] - s7, s[4] - s8)
            } else {
                intArrayOf(s[0], s[1], s[2], s[3] - s7, s[4] - s8, 0, 0)
            }
            val lowStars = IntArray(diff[0]) { 1 } + IntArray(diff[1]) { 2 } +
                IntArray(diff[2]) { 3 } + IntArray(diff[3]) { 4 } +
                IntArray(diff[4]) { 5 } + IntArray(diff[5]) { 6 } +
                IntArray(diff[6]) { 7 }
            lowStars.shuffle(rand)

            return assignStarsToBoard(board, highLevelPositions(board, rand), highStars, lowStars)
        } else {
            val diff = if (s[6] == 1) {
                intArrayOf(s[0], s[1], s[2], 0, 0, s[3], s[4])
            } else {
                intArrayOf(s[0], s[1], s[2], s[3], s[4], 0, 0)
            }
            val allStars = IntArray(diff[0]) { 1 } + IntArray(diff[1]) { 2 } +
                IntArray(diff[2]) { 3 } + IntArray(diff[3]) { 4 } +
                IntArray(diff[4]) { 5 } + IntArray(diff[5]) { 6 } +
                IntArray(diff[6]) { 7 }
            allStars.shuffle(rand)
            return allStars
        }
    }

    /**
     * Build a star array for BP mode: 3★ at fixed row-center positions, rest 1/2★.
     */
    @Throws(HandlerException::class)
    private fun buildBPStarArray(lv1Count: Int): IntArray {
        val rand = ThreadLocalRandom.current().asKotlinRandom()
        val lv2Count = 20 - lv1Count
        val idx = intArrayOf(0, 1, 3, 4)
        val star12 = IntArray(lv1Count) { 1 } + IntArray(lv2Count) { 2 }
        idx.shuffle(rand)
        star12.shuffle(rand)
        var j = 0
        val idx3 = arrayOf(idx[0], 5 + idx[1], 12, 15 + idx[2], 20 + idx[3])
        return IntArray(25) { i -> if (i in idx3) 3 else star12[j++] }
    }

    /**
     * Build a star array for BPOD mode: 3★ at fixed positions, star=13 placeholders for upgrade, rest 1/2★.
     */
    @Throws(HandlerException::class)
    private fun buildBPODStarArray(difficulty: Int): IntArray {
        val rand = ThreadLocalRandom.current().asKotlinRandom()
        val da = when (difficulty) {
            4 -> Difficulty.ODBP.value
            5 -> Difficulty.ODPBP.value
            else -> Difficulty.LBPDefault.value
        }
        val star12 = IntArray(da[0]) { 1 } + IntArray(da[1]) { 2 } + IntArray(da[3]) { 13 }
        val idx = intArrayOf(0, 1, 3, 4)
        idx.shuffle(rand)
        star12.shuffle(rand)
        var j = 0
        val idx3 = arrayOf(idx[0], 5 + idx[1], 12, 15 + idx[2], 20 + idx[3])
        return IntArray(25) { i -> if (i in idx3) 3 else star12[j++] }
    }

    /**
     * Build a star array for LINK mode.
     *
     * Start points are forced to 1-star and consume the level-1 quota
     * (cascading to higher levels when the quota is exhausted).
     *
     * When custom start/end points are set the 4/5-star placement uses a
     * balanced, center-first ordering similar to even-sized boards so that
     * displaced high stars stay close to the middle and are evenly spread.
     */
    @Throws(HandlerException::class)
    fun buildLinkStarArray(difficulty: Difficulty, boardSize: Int, starts: Set<Int>? = null): IntArray {
        val lvCount = difficulty.value
        val rand = ThreadLocalRandom.current().asKotlinRandom()
        val board = BoardSpec(boardSize)
        val area = board.area
        val defaultStarts = setOf(board.index(0, 0), board.index(0, board.size - 1))
        val startSet = starts
            ?.filter { board.isValidIndex(it) }
            ?.toSet()
            ?: defaultStarts
        val highCount = board.size

        val fixed = mutableMapOf<Int, Int>()
        startSet.forEach { fixed[it] = 1 }

        // Start points consume the level-1 quota, cascading to level 2 / 3.
        val adjustedLv = lvCount.copyOf()
        var deduct = startSet.size
        for (i in adjustedLv.indices) {
            val take = minOf(adjustedLv[i], deduct)
            adjustedLv[i] -= take
            deduct -= take
            if (deduct == 0) break
        }

        val isCustomStarts = starts != null && startSet != defaultStarts
        val highPriority = (if (isCustomStarts) {
            linkCustomStartHighPriority(board, rand)
        } else {
            linkHighPriority(board, rand)
        }).filter { it !in startSet }
        val fivePos = highPriority.firstOrNull() ?: (0 until area).first { it !in startSet }
        fixed[fivePos] = 5
        val centerHighLimit = if (board.size % 2 == 0) 3 else Int.MAX_VALUE
        val centerSet = board.centerIndices().toSet()
        var centerHighCount = if (fivePos in centerSet) 1 else 0
        for (position in highPriority) {
            if (fixed.size - startSet.size >= highCount) break
            if (position == fivePos || position in fixed) continue
            if (position in centerSet && centerHighCount >= centerHighLimit) continue
            fixed[position] = 4
            if (position in centerSet) centerHighCount++
        }

        val remainingCount = area - fixed.size
        val lowStars = (IntArray(adjustedLv[0]) { 1 } + IntArray(adjustedLv[1]) { 2 } + IntArray(adjustedLv[2]) { 3 })
            .toMutableList()
        while (lowStars.size < remainingCount) lowStars.add(3)
        lowStars.shuffle(rand)
        var j = 0
        return IntArray(area) { i ->
            fixed[i] ?: lowStars[j++]
        }
    }

    private fun linkHighPriority(board: BoardSpec, rand: Random): List<Int> {
        if (board.size == 5) {
            val legacy = listOf(12, 6, 8, 16, 18)
            val rest = (0 until board.area)
                .filter { it !in legacy }
                .sortedWith(compareBy<Int> { centerDistance2(board, it) }.thenBy { rand.nextInt() })
            return legacy + rest
        }
        if (board.size % 2 == 0) return linkEvenHighPriority(board, rand)
        return (0 until board.area)
            .sortedWith(compareBy<Int> { centerDistance2(board, it) }.thenBy { rand.nextInt() })
    }

    /**
     * Balanced high-priority ordering used when custom start points may
     * displace normal 4/5-star positions.  Mirrors even-board logic:
     * center first, then inner ring (sector-balanced for 7×7+), then outer ring.
     */
    private fun linkCustomStartHighPriority(board: BoardSpec, rand: Random): List<Int> {
        if (board.size % 2 == 0) return linkEvenHighPriority(board, rand)
        val center = board.centerIndices().shuffled(rand)
        val centerSet = center.toSet()
        val innerRing = board.innerIndices().filter { it !in centerSet }
        val balancedRing = if (board.size >= 7) {
            balancedLinkRing(board, innerRing, rand)
        } else {
            innerRing.sortedWith(compareBy<Int> { centerDistance2(board, it) }.thenBy { rand.nextInt() })
        }
        val outer = (0 until board.area)
            .filter { it !in centerSet && it !in innerRing }
            .sortedWith(compareBy<Int> { centerDistance2(board, it) }.thenBy { rand.nextInt() })
        return center + balancedRing + outer
    }

    private fun linkEvenHighPriority(board: BoardSpec, rand: Random): List<Int> {
        val center = board.centerIndices().shuffled(rand)
        val centerPick = center.take(3)
        val restCenter = center.drop(3)
        val innerRing = board.innerIndices()
            .filter { it !in center }
        val balancedRing = if (board.size == 6) {
            balancedLinkRing(board, innerRing, rand)
        } else {
            innerRing.sortedWith(compareBy<Int> { centerDistance2(board, it) }.thenBy { rand.nextInt() })
        }
        val outer = (0 until board.area)
            .filter { it !in center && it !in innerRing }
            .sortedWith(compareBy<Int> { centerDistance2(board, it) }.thenBy { rand.nextInt() })
        return centerPick + balancedRing + restCenter + outer
    }

    private fun balancedLinkRing(board: BoardSpec, indices: List<Int>, rand: Random): List<Int> {
        val midLow = board.size / 2 - 1
        val midHigh = board.size / 2
        fun sector(index: Int): Int {
            val row = board.row(index)
            val col = board.col(index)
            return when {
                row < midLow -> 0
                col > midHigh -> 1
                row > midHigh -> 2
                else -> 3
            }
        }
        val groups = indices.groupBy(::sector).mapValues { (_, value) ->
            value.sortedWith(compareBy<Int> { centerDistance2(board, it) }.thenBy { rand.nextInt() }).toMutableList()
        }
        val result = mutableListOf<Int>()
        var sector = rand.nextInt(4)
        while (groups.values.any { it.isNotEmpty() }) {
            repeat(4) {
                val group = groups[sector]
                if (!group.isNullOrEmpty()) result.add(group.removeAt(0))
                sector = (sector + 1) % 4
            }
        }
        return result
    }

    private fun centerDistance2(board: BoardSpec, index: Int): Int {
        val scaledCenter = board.size - 1
        val dr = board.row(index) * 2 - scaledCenter
        val dc = board.col(index) * 2 - scaledCenter
        return dr * dr + dc * dc
    }

    // ---- EX position helpers ----

    private fun ranksToExPos(ranks: Array<String>?, rand: Random, board: BoardSpec): IntArray {
        if (ranks != null && ranks.all { it == "L" })
            return intArrayOf()
        val idx = (0 until board.size).toMutableList()
        idx.shuffle(rand)
        return IntArray(board.size) { i -> board.index(i, idx[i]) }
    }

    private fun ranksToExPosCustom(ranks: Array<String>?, rand: Random, s: IntArray, board: BoardSpec): IntArray {
        if (ranks != null && ranks.all { it == "L" })
            return intArrayOf()
        if (s[9] == 1) {
            val idx = (0 until board.size).toMutableList()
            idx.shuffle(rand)
            return IntArray(board.size) { i -> board.index(i, idx[i]) }
        } else {
            if (s[10] > board.area || s[10] < 0) return intArrayOf()
            val index = IntArray(board.area) { i -> i }
            index.shuffle(rand)
            return index.sliceArray(0 until s[10])
        }
    }

    // ---- Unified public API ----

    /**
     * Build a star array for the given mode. All star array construction flows through this method.
     */
    @Throws(HandlerException::class)
    fun buildStarArray(
        mode: DifficultyMode,
        difficulty: Int? = null,
        difficultyObj: Difficulty? = null,
        boardSize: Int = 5,
        bpLv1Count: Int = 5,
        customSettings: IntArray? = null,
    ): IntArray = when (mode) {
        DifficultyMode.NORMAL -> buildNormalStarArray(
            difficultyObj ?: when (difficulty) {
                1 -> Difficulty.E
                2 -> Difficulty.N
                3 -> Difficulty.L
                else -> Difficulty.L
            }, boardSize
        )
        DifficultyMode.OD -> buildODStarArray(difficulty ?: 4, boardSize)
        DifficultyMode.CUSTOM -> buildCustomStarArray(
            customSettings ?: throw HandlerException("自定义等级数据格式错误"),
            boardSize
        )
        DifficultyMode.BP -> buildBPStarArray(bpLv1Count)
        DifficultyMode.BP_OD -> buildBPODStarArray(difficulty ?: 4)
        DifficultyMode.LINK -> buildLinkStarArray(
            difficultyObj ?: when (difficulty) {
                1 -> Difficulty.E
                2 -> Difficulty.N
                3 -> Difficulty.L
                else -> Difficulty.L
            },
            boardSize
        )
    }

    /**
     * Draw spells for the given mode using a pre-built star array.
     */
    @Throws(HandlerException::class)
    fun drawSpellsWithStar(
        mode: DifficultyMode,
        spellCardVersion: Int,
        games: Array<String>,
        ranks: Array<String>?,
        stars: IntArray,
        boardSize: Int = 5,
        customSettings: IntArray? = null,
    ): Array<Spell> {
        val rand = ThreadLocalRandom.current().asKotlinRandom()
        val board = BoardSpec(boardSize)
        val exPos = if (mode == DifficultyMode.CUSTOM) {
            ranksToExPosCustom(
                ranks,
                rand,
                customSettings ?: throw HandlerException("自定义等级数据格式错误"),
                board
            )
        } else {
            ranksToExPos(ranks, rand, board)
        }
        val priorityIndices = fixedHighLevelIndices(mode, stars, board, customSettings)

        return when (mode) {
            DifficultyMode.NORMAL,
            DifficultyMode.CUSTOM -> SpellConfig.getWithHighLevelDowngrade(
                SpellConfig.NORMAL_GAME, spellCardVersion, games, ranks, exPos, stars, rand, priorityIndices
            )
            DifficultyMode.LINK -> SpellConfig.get(
                SpellConfig.NORMAL_GAME, spellCardVersion, games, ranks, exPos, stars, rand, priorityIndices
            )
            DifficultyMode.OD -> SpellConfig.getOD(
                SpellConfig.NORMAL_GAME, spellCardVersion, games, ranks, exPos, stars, rand, priorityIndices
            )
            DifficultyMode.BP,
            DifficultyMode.BP_OD -> SpellConfig.getBPOD(
                SpellConfig.BP_GAME, spellCardVersion, games, ranks, exPos, stars, rand
            )
        }
    }

    /**
     * Build star array and draw spells in one call.
     */
    @Throws(HandlerException::class)
    fun drawSpells(
        mode: DifficultyMode,
        spellCardVersion: Int,
        games: Array<String>,
        ranks: Array<String>?,
        difficulty: Int? = null,
        boardSize: Int = 5,
        difficultyObj: Difficulty? = null,
        bpLv1Count: Int = 5,
        customSettings: IntArray? = null,
    ): Array<Spell> {
        val stars = buildStarArray(
            mode, difficulty, difficultyObj = difficultyObj,
            boardSize = boardSize,
            bpLv1Count = bpLv1Count,
            customSettings = customSettings,
        )
        return drawSpellsWithStar(mode, spellCardVersion, games, ranks, stars, boardSize, customSettings)
    }

    fun drawCustomPoolSpellsWithStar(
        customPool: Array<Spell>,
        games: Array<String>,
        ranks: Array<String>?,
        stars: IntArray,
        boardSize: Int = 5,
        mode: DifficultyMode = DifficultyMode.NORMAL,
        customSettings: IntArray? = null,
    ): Array<Spell> {
        val rand = ThreadLocalRandom.current().asKotlinRandom()
        val board = BoardSpec(boardSize)
        val exPos = if (mode == DifficultyMode.CUSTOM) {
            ranksToExPosCustom(
                ranks, rand,
                customSettings ?: throw HandlerException("自定义等级数据格式错误"),
                board
            )
        } else {
            ranksToExPos(ranks, rand, board)
        }
        val priorityIndices = fixedHighLevelIndices(mode, stars, board, customSettings)

        return when (mode) {
            DifficultyMode.NORMAL,
            DifficultyMode.CUSTOM,
            DifficultyMode.OD -> SpellConfig.getFromCustomPool(
                customPool, games, ranks, exPos, stars, rand, priorityIndices,
                upgrades = SpellConfig.OD_UPGRADES,
                priorityStars = setOf(4, 5),
            )
            DifficultyMode.LINK -> SpellConfig.getFromCustomPool(
                customPool, games, ranks, exPos, stars, rand, priorityIndices,
            )
            DifficultyMode.BP,
            DifficultyMode.BP_OD -> SpellConfig.getFromCustomPool(
                customPool, games, ranks, exPos, stars, rand, priorityIndices,
                upgrades = SpellConfig.BPOD_UPGRADES,
                priorityStars = setOf(3),
                maxWeightStar = 4,
                exDrawStar = 4,
            )
        }
    }

    // ---- Legacy public API (delegates to unified methods) ----
    // Kept for backward compatibility during transition; RoomType* will be updated next.

    @Throws(HandlerException::class)
    fun randSpellsBP(spellCardVersion: Int, games: Array<String>, ranks: Array<String>?, lv1Count: Int): Array<Spell> =
        drawSpellsWithStar(
            DifficultyMode.BP, spellCardVersion, games, ranks,
            buildStarArray(DifficultyMode.BP, bpLv1Count = lv1Count),
        )

    @Throws(HandlerException::class)
    fun randSpellsBPOD(spellCardVersion: Int, games: Array<String>, ranks: Array<String>?, difficulty: Int): Array<Spell> =
        drawSpells(
            DifficultyMode.BP_OD, spellCardVersion, games, ranks, difficulty
        )

    @Throws(HandlerException::class)
    fun randSpells(
        spellCardVersion: Int,
        games: Array<String>,
        ranks: Array<String>?,
        difficulty: Difficulty,
        boardSize: Int = 5
    ): Array<Spell> = drawSpellsWithStar(
        DifficultyMode.NORMAL, spellCardVersion, games, ranks,
        buildStarArray(
            DifficultyMode.NORMAL,
            difficultyObj = difficulty,
            boardSize = boardSize,
        ),
        boardSize,
    )

    @Throws(HandlerException::class)
    fun randSpellsStarArray(difficulty: Difficulty, boardSize: Int = 5): IntArray = buildStarArray(
        DifficultyMode.NORMAL,
        difficultyObj = difficulty,
        boardSize = boardSize,
    )

    @Throws(HandlerException::class)
    fun randSpellsWithStar(
        spellCardVersion: Int,
        games: Array<String>,
        ranks: Array<String>?,
        stars: IntArray,
        boardSize: Int = 5
    ): Array<Spell> = drawSpellsWithStar(
        DifficultyMode.NORMAL, spellCardVersion, games, ranks, stars, boardSize
    )

    @Throws(HandlerException::class)
    fun randSpellsOD(
        spellCardVersion: Int,
        games: Array<String>,
        ranks: Array<String>?,
        difficulty: Int,
        boardSize: Int = 5
    ): Array<Spell> = drawSpells(
        DifficultyMode.OD, spellCardVersion, games, ranks, difficulty, boardSize
    )

    @Throws(HandlerException::class)
    fun randSpellsODStarArray(difficulty: Int, boardSize: Int = 5): IntArray =
        buildStarArray(DifficultyMode.OD, difficulty, boardSize = boardSize)

    @Throws(HandlerException::class)
    fun randSpellsODWithStar(
        spellCardVersion: Int,
        games: Array<String>,
        ranks: Array<String>?,
        stars: IntArray,
        boardSize: Int = 5
    ): Array<Spell> = drawSpellsWithStar(
        DifficultyMode.OD, spellCardVersion, games, ranks, stars, boardSize
    )

    @Throws(HandlerException::class)
    fun randSpellsCustom(
        spellCardVersion: Int,
        games: Array<String>,
        ranks: Array<String>?,
        settings: IntArray,
        boardSize: Int = 5,
    ): Array<Spell> = drawSpells(
        DifficultyMode.CUSTOM, spellCardVersion, games, ranks,
        boardSize = boardSize,
        customSettings = settings,
    )

    @Throws(HandlerException::class)
    fun randSpellsCustomStarArray(s: IntArray, boardSize: Int = 5): IntArray {
        return buildStarArray(
            DifficultyMode.CUSTOM,
            boardSize = boardSize,
            customSettings = s,
        )
    }

    @Throws(HandlerException::class)
    fun randSpellsCustomWithStar(
        spellCardVersion: Int,
        games: Array<String>,
        ranks: Array<String>?,
        stars: IntArray,
        settings: IntArray,
        boardSize: Int = 5,
    ): Array<Spell> = drawSpellsWithStar(
        DifficultyMode.CUSTOM, spellCardVersion, games, ranks, stars, boardSize, settings
    )

    @Throws(HandlerException::class)
    fun randSpellsLink(
        spellCardVersion: Int,
        games: Array<String>,
        ranks: Array<String>?,
        difficulty: Difficulty
    ): Array<Spell> = drawSpellsWithStar(
        DifficultyMode.LINK, spellCardVersion, games, ranks,
        buildStarArray(DifficultyMode.LINK, difficultyObj = difficulty),
    )
}
