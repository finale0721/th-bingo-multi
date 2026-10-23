package org.tfcc.bingo.message

import kotlinx.serialization.Serializable
import org.tfcc.bingo.Spell

private fun legacyRoomConfigDefault() = RoomConfig(
    rid = "legacy",
    type = 1,
    gameTime = 30,
    countdown = 60,
    games = emptyArray(),
    ranks = emptyArray(),
    needWin = 1,
    difficulty = 3,
    cdTime = 0,
    cdModifierA = 0,
    cdModifierB = 0,
    reservedType = null,
    blindSetting = 1,
    spellCardVersion = 2,
    dualBoard = 0,
    portalCount = 5,
    blindRevealLevel = 2,
    diffLevel = 3,
    useAI = false,
    aiStrategyLevel = 3,
    aiStyle = 0,
    aiBasePower = 5f,
    aiExperience = 5f,
    aiTemperature = 0f,
    gameWeight = hashMapOf(),
    aiPreference = hashMapOf(),
    customLevelCount = arrayOf(2, 6, 12, 4, 1, 1, 0, 4, 1, 1, 5),
    boardSize = 5,
    extraLineCount = 0,
)

@Serializable
data class PlayerAction(
    val playerName: String = "",
    val actionType: String = "",
    val spellIndex: Int = -1,
    val spellName: String = "",
    val timestamp: Long = 0L,
    val scoreNow: List<Int> = listOf(0, 0),
    val linkData: LinkData? = null,
)

@Serializable
data class GameLog(
    val roomConfig: RoomConfig = legacyRoomConfigDefault(),
    val players: List<String> = emptyList(),
    val spells: List<Spell> = emptyList(),
    val spells2: List<Spell>? = null,
    val normalData: NormalData? = null,
    val actions: List<PlayerAction> = emptyList(),
    val gameStartTimestamp: Long = 0L,
    val score: List<Int> = listOf(0, 0),
    val initStatus: List<Int> = emptyList(),
    val isCustomGame: Boolean = false,
    val linkData: LinkData? = null,
)
