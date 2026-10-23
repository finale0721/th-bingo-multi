package org.tfcc.bingo

data class BlindRevealCounts(
    val first: Int,
    val second: Int,
    val both: Int,
)

object BlindRevealProfile {
    private val normalMode2 = mapOf(
        4 to mapOf(
            0 to BlindRevealCounts(0, 0, 0),
            1 to BlindRevealCounts(2, 2, 1),
            2 to BlindRevealCounts(3, 3, 1),
            3 to BlindRevealCounts(4, 4, 3),
            4 to BlindRevealCounts(5, 5, 3),
        ),
        5 to mapOf(
            0 to BlindRevealCounts(0, 0, 0),
            1 to BlindRevealCounts(3, 3, 1),
            2 to BlindRevealCounts(5, 5, 2),
            3 to BlindRevealCounts(6, 6, 4),
            4 to BlindRevealCounts(8, 8, 4),
        ),
        6 to mapOf(
            0 to BlindRevealCounts(0, 0, 0),
            1 to BlindRevealCounts(4, 4, 1),
            2 to BlindRevealCounts(7, 7, 3),
            3 to BlindRevealCounts(9, 9, 6),
            4 to BlindRevealCounts(12, 12, 6),
        ),
    )

    private val normalMode3 = mapOf(
        4 to mapOf(
            0 to BlindRevealCounts(8, 0, 0),
            1 to BlindRevealCounts(16, 0, 0),
            2 to BlindRevealCounts(8, 8, 0),
            3 to BlindRevealCounts(0, 16, 0),
            4 to BlindRevealCounts(0, 12, 4),
        ),
        5 to mapOf(
            0 to BlindRevealCounts(12, 0, 0),
            1 to BlindRevealCounts(25, 0, 0),
            2 to BlindRevealCounts(13, 12, 0),
            3 to BlindRevealCounts(0, 25, 0),
            4 to BlindRevealCounts(0, 18, 7),
        ),
        6 to mapOf(
            0 to BlindRevealCounts(18, 0, 0),
            1 to BlindRevealCounts(36, 0, 0),
            2 to BlindRevealCounts(18, 18, 0),
            3 to BlindRevealCounts(0, 36, 0),
            4 to BlindRevealCounts(0, 27, 9),
        ),
    )

    fun normal(blindSetting: Int, boardSize: Int, revealLevel: Int): BlindRevealCounts {
        val profile = when (blindSetting) {
            2 -> normalMode2
            3 -> normalMode3
            else -> return BlindRevealCounts(0, 0, 0)
        }
        return profile[boardSize]?.get(revealLevel) ?: BlindRevealCounts(0, 0, 0)
    }
}
