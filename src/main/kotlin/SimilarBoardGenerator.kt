package org.tfcc.bingo

import java.util.concurrent.ThreadLocalRandom
import kotlin.math.abs
import kotlin.random.Random
import kotlin.random.asKotlinRandom

object SimilarBoardGenerator {
    private val V_MAP = intArrayOf(0, 1, 1, 2, 3, 4, 3, 4) // V(x) = V_MAP[x]

    // Tier index ranges (0-based) for diffLevel 1..4. diffLevel 5 always picks the largest diff candidate.
    private val TIER_RANGES = arrayOf(
        intArrayOf(100, 199),
        intArrayOf(200, 399),
        intArrayOf(400, 699),
        intArrayOf(700, 999),
    )

    private const val CANDIDATE_COUNT = 1000

    /**
     * Generate a board (star array) with specified difference level from matrixA.
     * @param matrixA The original star array
     * @param diffLevel -1 = random (just generate two random boards independently);
     *                   0 = same difficulty distribution as matrixA;
     *                   1..4 = tiered selection from 1000 sorted candidates;
     *                   5 = max difference candidate
     * @param board Board geometry spec
     * @return A star array with the same star distribution as matrixA
     */
    fun findMatrixB(
        matrixA: IntArray,
        diffLevel: Int,
        board: BoardSpec = BoardSpec(5),
        preserveFixedHighLevelLayout: Boolean = false,
    ): IntArray {
        val rand = ThreadLocalRandom.current().asKotlinRandom()

        if (diffLevel < 0) {
            // Random mode: just return a fresh random board with same star distribution
            return generateRandomBoard(matrixA, board, rand, preserveFixedHighLevelLayout)
        }

        if (diffLevel == 0) {
            return matrixA.copyOf()
        }

        val level = diffLevel.coerceIn(1, 5)

        // Generate CANDIDATE_COUNT random boards, compute difference, sort ascending
        val candidates = ArrayList<Pair<IntArray, Int>>(CANDIDATE_COUNT)
        for (i in 0 until CANDIDATE_COUNT) {
            val candidate = generateRandomBoard(matrixA, board, rand, preserveFixedHighLevelLayout)
            val diff = computeDifference(matrixA, candidate)
            candidates.add(Pair(candidate, diff))
        }
        candidates.sortBy { it.second }

        if (level == 5) {
            return candidates.last().first
        }

        // Pick a random index from the tier range
        val range = TIER_RANGES[level - 1]
        val idx = rand.nextInt(range[0], range[1] + 1)
        return candidates[idx].first
    }

    /**
     * Generate a random board preserving the same star-level distribution as matrixA.
     * Shuffles the star values randomly across all positions.
     */
    private fun generateRandomBoard(
        matrixA: IntArray,
        board: BoardSpec,
        rand: Random,
        preserveFixedHighLevelLayout: Boolean,
    ): IntArray {
        if (preserveFixedHighLevelLayout) {
            return generateFixedHighLevelBoard(matrixA, board, rand)
        }
        val shuffled = matrixA.copyOf()
        shuffled.shuffle(rand)
        return shuffled
    }

    private fun generateFixedHighLevelBoard(matrixA: IntArray, board: BoardSpec, rand: Random): IntArray {
        val fixedSource = SpellFactory.fixedHighLevelIndices(matrixA, board)
        val fixedStars = fixedSource.map { matrixA[it] }.toMutableList()
        fixedStars.shuffle(rand)

        val lowStars = matrixA.indices.filter { it !in fixedSource }
            .map { matrixA[it] }
            .toMutableList()
        lowStars.shuffle(rand)

        val result = IntArray(board.area)
        val fixedTarget = SpellFactory.highLevelPositions(board, rand).toSet()
        var fixedIndex = 0
        var lowIndex = 0
        for (i in 0 until board.area) {
            result[i] = if (i in fixedTarget) fixedStars[fixedIndex++] else lowStars[lowIndex++]
        }
        return result
    }

    /**
     * Compute total V_MAP difference between two star arrays.
     * Single-cell algorithm preserved from original.
     */
    private fun computeDifference(matrixA: IntArray, matrixB: IntArray): Int {
        var diff = 0
        for (i in matrixA.indices) {
            diff += abs(V_MAP[matrixA[i]] - V_MAP[matrixB[i]])
        }
        return diff
    }
}
