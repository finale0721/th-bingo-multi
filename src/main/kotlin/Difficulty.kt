package org.tfcc.bingo

class Difficulty(val value: IntArray) {
    companion object {
        val E = Difficulty(intArrayOf(12, 6, 2))
        val N = Difficulty(intArrayOf(6, 8, 6))
        val L = Difficulty(intArrayOf(2, 6, 12))
        val OD = Difficulty(intArrayOf(1, 4, 8, 0, 0, 5, 2))
        val ODP = Difficulty(intArrayOf(0, 0, 6, 0, 0, 9, 5))
        val ODBP = Difficulty(intArrayOf(3, 12, 0, 5))
        val ODPBP = Difficulty(intArrayOf(0, 10, 0, 10))
        val LDefault = Difficulty(intArrayOf(2, 6, 12, 0, 0, 0, 0))
        val LBPDefault = Difficulty(intArrayOf(5, 15, 0, 0))
    }
}
