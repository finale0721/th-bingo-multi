package org.tfcc.bingo.team

/** 多人模式的队伍。index 与协议中的 0/1 一一对应（0=A，1=B）。 */
enum class Team(val index: Int) {
    A(0),
    B(1);

    val other: Team
        get() = if (this == A) B else A

    companion object {
        fun of(index: Int?): Team? = entries.firstOrNull { it.index == index }
    }
}
