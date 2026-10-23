package org.tfcc.bingo

class BoardSpec(val size: Int) {
    val area: Int = size * size

    init {
        require(size in 4..6) { "Board size must be 4, 5, or 6, got $size" }
    }

    fun row(index: Int): Int = index / size

    fun col(index: Int): Int = index % size

    fun index(row: Int, col: Int): Int = row * size + col

    fun isValidIndex(index: Int): Boolean = index in 0 until area

    fun rows(): List<List<Int>> = (0 until size).map { r ->
        (0 until size).map { c -> index(r, c) }
    }

    fun cols(): List<List<Int>> = (0 until size).map { c ->
        (0 until size).map { r -> index(r, c) }
    }

    fun diagonals(): List<List<Int>> {
        if (size < 5) return emptyList()
        val mainDiag = (0 until size).map { index(it, it) }
        val antiDiag = (0 until size).map { index(it, size - 1 - it) }
        return listOf(mainDiag, antiDiag)
    }

    fun winningLines(extraLines: List<List<Int>> = emptyList()): List<List<Int>> {
        val lines = mutableListOf<List<Int>>()
        lines.addAll(rows())
        lines.addAll(cols())
        lines.addAll(diagonals())
        lines.addAll(extraLines)
        return lines
    }

    /**
     * Generate random extra winning lines for 6x6 boards.
     * Each line is an 8-connected path of exactly [size] cells.
     * Lines must not overlap each other, and each line must not share more than 3 cells
     * with any base winning line (row/col/diag) when possible.
     */
    fun generateExtraLines(count: Int, rand: kotlin.random.Random): List<List<Int>> {
        if (count <= 0 || size != 6) return emptyList()
        val baseLines = rows() + cols() + diagonals()
        val result = mutableListOf<List<Int>>()
        val usedCells = mutableSetOf<Int>()
        val maxCandidates = 500

        for (lineNum in 0 until count) {
            var best: List<Int>? = null
            var bestOverlap = Int.MAX_VALUE
            for (retry in 0 until maxCandidates) {
                val path = generateRandomPath(rand, usedCells) ?: continue
                val overlapWithBase = path.maxOverlapWith(baseLines)
                if (overlapWithBase < bestOverlap) {
                    best = path
                    bestOverlap = overlapWithBase
                }
                if (overlapWithBase <= 3) {
                    best = path
                    break
                }
            }
            if (best != null) {
                result.add(best)
                usedCells.addAll(best)
            } else {
                throw IllegalStateException(
                    "Failed to generate extra line ${lineNum + 1}/$count after $maxCandidates candidates"
                )
            }
        }
        return result
    }

    private fun List<Int>.maxOverlapWith(lines: List<List<Int>>): Int {
        val cells = toSet()
        return lines.maxOfOrNull { line -> line.count { it in cells } } ?: 0
    }

    /**
     * Generate a random 8-connected path of length [size] that does not use any cell in [exclude].
     * Uses a random walk with backtracking.
     */
    private fun generateRandomPath(rand: kotlin.random.Random, exclude: Set<Int>): List<Int>? {
        val allIndices = (0 until area).filter { it !in exclude }
        if (allIndices.isEmpty()) return null
        val start = allIndices.random(rand)
        val path = mutableListOf(start)
        val visited = mutableSetOf(start)

        fun walk(): Boolean {
            if (path.size == size) return true
            val current = path.last()
            val neighbors = neighbors8(current)
                .filter { it !in visited && it !in exclude }
                .shuffled(rand)
            for (n in neighbors) {
                path.add(n)
                visited.add(n)
                if (walk()) return true
                path.removeAt(path.lastIndex)
                visited.remove(n)
            }
            return false
        }

        return if (walk()) path else null
    }

    fun outerRingIndices(): List<Int> {
        val indices = mutableListOf<Int>()
        for (c in 0 until size) {
            indices.add(index(0, c))
            if (size > 1) indices.add(index(size - 1, c))
        }
        for (r in 1 until size - 1) {
            indices.add(index(r, 0))
            indices.add(index(r, size - 1))
        }
        return indices
    }

    fun innerIndices(): List<Int> {
        val outer = outerRingIndices().toSet()
        return (0 until area).filter { it !in outer }
    }

    fun centerIndices(): List<Int> {
        return if (size % 2 == 1) {
            val mid = size / 2
            listOf(index(mid, mid))
        } else {
            val mid1 = size / 2 - 1
            val mid2 = size / 2
            listOf(index(mid1, mid1), index(mid1, mid2), index(mid2, mid1), index(mid2, mid2))
        }
    }

    fun neighbors4(index: Int): List<Int> {
        val r = row(index)
        val c = col(index)
        val result = mutableListOf<Int>()
        if (r > 0) result.add(this.index(r - 1, c))
        if (r < size - 1) result.add(this.index(r + 1, c))
        if (c > 0) result.add(this.index(r, c - 1))
        if (c < size - 1) result.add(this.index(r, c + 1))
        return result
    }

    fun neighbors8(index: Int): List<Int> {
        val r = row(index)
        val c = col(index)
        val result = mutableListOf<Int>()
        for (dr in -1..1) {
            for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val nr = r + dr
                val nc = c + dc
                if (nr in 0 until size && nc in 0 until size) {
                    result.add(this.index(nr, nc))
                }
            }
        }
        return result
    }

    companion object {
        val DEFAULT = BoardSpec(5)
    }
}
