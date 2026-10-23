package org.tfcc.bingo

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

data class AICardModel(
    val index: Int,
    val baseTime: Float,
    val failureTime: Float,
    val successTime: Float,
    val successRate: Float,
    val expectedTime: Float,
)

object AICardModelFactory {
    fun create(room: Room): List<AICardModel> = room.spells?.mapIndexed { boardIndex, spell ->
        create(room, boardIndex, spell)
    } ?: emptyList()

    fun create(room: Room, boardIndex: Int, spell: Spell): AICardModel {
        val prefLevel = room.roomConfig.aiPreference[spell.game] ?: 0
        val aiPower = max(room.roomConfig.aiBasePower + 5f, 5f)
        val aief = prefLevel * if (prefLevel > 0) 3f else 4f
        val aiExp = max(room.roomConfig.aiExperience + 5f + aief, 0.0f)
        val spellPowerWeight = min(.95f, max(.05f, spell.powerWeight))
        val spellExpWeight = 1f - spellPowerWeight
        val randFloat1 = (Random.nextFloat() + Random.nextFloat()) / 2f
        val randFloat2 = (Random.nextFloat() + Random.nextFloat()) / 2f
        val successTime = if (spell.fastest > 60.0f) {
            spell.fastest + 3.5f + 3f * randFloat1
        } else {
            spell.fastest + 3.5f + randFloat1 * spell.fastest * .25f +
                randFloat2 * max(6f - aiExp / 4f, 0f)
        }

        val minCapRate = if (spell.difficulty < 6.0f) {
            0.10f
        } else {
            max(0.10f - (spell.difficulty - 6f) * .01f, 0f)
        }
        var baseCapRate = spell.maxCapRate + if (prefLevel > 0) (1f - spell.maxCapRate) * 0.33f * prefLevel else 0f
        baseCapRate *= exp(-0.5f * max((spell.difficulty - aiPower) * spellPowerWeight - 2.5f, 0f))
        val req = min(
            (1f - spellPowerWeight) * 10f + max(min((spell.difficulty - 8f) / 4f, 2f), 0f),
            9f,
        )
        val b = 0.9f - req * req * 0.005f - req * 0.045f
        val k = (1f - b) * 0.1f
        baseCapRate *= k * min(10f, max(0f, aiExp - 5f)) + b
        baseCapRate = max(baseCapRate - minCapRate, 0f)
        val finalRate =
            baseCapRate / (
                1f + exp(
                    -.925f * spell.changeRate *
                        (aiPower * spellPowerWeight + aiExp * spellExpWeight - spell.difficulty),
                )
                ) + minCapRate
        val successRate = min(.999f, max(finalRate, .001f))
        val failureTime = 1.5f + spell.missTime
        val expectedTime = successTime + failureTime * (1f - successRate) / successRate

        return AICardModel(
            index = boardIndex,
            baseTime = spell.fastest,
            failureTime = failureTime,
            successTime = successTime,
            successRate = successRate,
            expectedTime = expectedTime,
        )
    }
}
