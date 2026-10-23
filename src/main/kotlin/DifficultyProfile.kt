package org.tfcc.bingo

data class DifficultyProfile(
    val name: String,
    val countsBySize: Map<Int, IntArray>,
) {
    fun counts(boardSize: Int): IntArray {
        return countsBySize[boardSize]
            ?: countsBySize[5]!!.let { base ->
                // Scale low-star counts: total low = boardArea - highCount (highCount = boardSize)
                scaleCounts(base, boardSize * boardSize - boardSize)
            }
    }

    companion object {
        fun scaleCounts(base: IntArray, targetTotal: Int): IntArray {
            val baseTotal = base.sum()
            if (baseTotal == targetTotal) return base.copyOf()
            val result = IntArray(base.size)
            var remaining = targetTotal
            for (i in base.indices) {
                if (i == base.lastIndex) {
                    result[i] = remaining
                } else {
                    val scaled = (base[i].toDouble() * targetTotal / baseTotal).toInt()
                    result[i] = scaled.coerceIn(0, remaining)
                    remaining -= result[i]
                }
            }
            return result
        }

        val E = DifficultyProfile(
            "E",
            mapOf(
                4 to intArrayOf(8, 3, 1, 0, 0, 0, 0), // 8 3 1 3 1
                5 to intArrayOf(12, 6, 2, 0, 0, 0, 0), // 12 6 2 4 1
                6 to intArrayOf(15, 10, 5, 0, 0, 0, 0), // 15 10 5 5 1
            )
        )

        val N = DifficultyProfile(
            "N",
            mapOf(
                4 to intArrayOf(3, 6, 3, 0, 0, 0, 0), // 3 6 3 3 1
                5 to intArrayOf(6, 8, 6, 0, 0, 0, 0), // 6 8 6 4 1
                6 to intArrayOf(9, 11, 9, 0, 0, 1, 0), // 9 11 9 6 1
            )
        )

        val L = DifficultyProfile(
            "L",
            mapOf(
                4 to intArrayOf(1, 3, 8, 0, 0, 0, 0), // 1 3 8 3 1
                5 to intArrayOf(2, 6, 12, 0, 0, 0, 0), // 2 6 12 4 1
                6 to intArrayOf(4, 9, 16, 0, 0, 1, 0), // 4 9 16 6 1
            )
        )

        val OD = DifficultyProfile(
            "OD",
            mapOf(
                4 to intArrayOf(1, 2, 5, 0, 0, 3, 1), // 1 2 5 6 2
                5 to intArrayOf(1, 4, 8, 0, 0, 5, 2), // 1 4 8 9 3
                6 to intArrayOf(2, 7, 10, 0, 0, 7, 4), // 2 7 10 12 5
            )
        )

        val ODP = DifficultyProfile(
            "ODP",
            mapOf(
                4 to intArrayOf(0, 0, 4, 0, 0, 5, 3), // 0 0 4 8 4
                5 to intArrayOf(0, 0, 6, 0, 0, 9, 5), // 0 0 6 13 6
                6 to intArrayOf(0, 0, 10, 0, 0, 11, 9), // 0 0 10 16 10
            )
        )

        val LDefault = DifficultyProfile(
            "LDefault",
            mapOf(
                4 to intArrayOf(1, 3, 8, 0, 0, 0, 0), // 1 3 8 3 1
                5 to intArrayOf(2, 6, 12, 0, 0, 0, 0), // 2 6 12 4 1
                6 to intArrayOf(4, 9, 16, 0, 0, 1, 0), // 4 9 16 6 1
            )
        )

        val ODBP = DifficultyProfile(
            "ODBP",
            mapOf(
                5 to intArrayOf(3, 12, 0, 5),
            )
        )

        val ODPBP = DifficultyProfile(
            "ODPBP",
            mapOf(
                5 to intArrayOf(0, 10, 0, 10),
            )
        )

        val LBPDefault = DifficultyProfile(
            "LBPDefault",
            mapOf(
                5 to intArrayOf(5, 15, 0, 0),
            )
        )
    }
}
