package org.tfcc.bingo

import org.apache.logging.log4j.kotlin.logger
import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.openxml4j.opc.PackageAccess
import org.apache.poi.ss.usermodel.CellType.STRING
import org.apache.poi.xssf.usermodel.XSSFCell
import org.apache.poi.xssf.usermodel.XSSFRow
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.tfcc.bingo.message.HandlerException
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.HashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Describes how a placeholder star value should be upgraded during card drawing.
 * Used by [draw] to implement the OD and BPOD upgrade/降级 logic.
 *
 * @param placeholderStar the star value in the stars array that triggers this rule (e.g. 7 for OD 5-star, 13 for BPOD 3-star)
 * @param targetStar the actual star level to try drawing first (e.g. 5 for OD, 3 for BPOD)
 * @param fallbackStar the star level to assign if target cards are exhausted (e.g. 6→3 for OD, 2 for BPOD)
 * @param drawPriority whether these positions should be drawn before low-star cards (true for OD 4/5, false for BPOD where only 3-star is priority)
 */
data class UpgradeRule(
    val placeholderStar: Int,
    val targetStar: Int,
    val fallbackStar: Int,
    val drawPriority: Boolean = true,
)

object SpellConfig {
    /** 标准赛和Link赛用同一个配置 */
    const val NORMAL_GAME = 1

    /** BP赛的配置 */
    const val BP_GAME = 2

    /** Standard game: no upgrade rules */
    val NO_UPGRADES = emptyList<UpgradeRule>()

    /** OD game: star=7 → try 5★ (fallback 6→3), star=6 → try 4★ (fallback 3) */
    val OD_UPGRADES = listOf(
        UpgradeRule(placeholderStar = 7, targetStar = 5, fallbackStar = 3, drawPriority = true),
        UpgradeRule(placeholderStar = 6, targetStar = 4, fallbackStar = 3, drawPriority = true),
    )

    /** BPOD game: star=13 → try 3★ (fallback 2) */
    val BPOD_UPGRADES = listOf(
        UpgradeRule(placeholderStar = 13, targetStar = 3, fallbackStar = 2, drawPriority = false),
    )

    private var rollSpellLeftCache = HashMap<Boolean, HashMap<Int, LinkedList<Spell>>>()

    var weightVar = 1.0f

    val weightDict: HashMap<String, Float> = HashMap()

    private fun calProb(configLevel: Int): Float {
        return when (configLevel) {
            -2 -> 0.1f
            -1 -> 0.5f
            0 -> 1.0f
            1 -> 2.0f
            2 -> 10.0f
            else -> 1.0f
        }
    }

    private fun calWeightVar(configLevel: Int): Float {
        return when (configLevel) {
            -2 -> 0.2f
            -1 -> 0.5f
            0 -> 1.0f
            1 -> 1.35f
            2 -> 1.85f
            else -> 1.0f
        }
    }

    fun setWeightDict(weightDict: HashMap<String, Int>) {
        this.weightDict.clear()
        weightDict.forEach { (k, v) ->
            if (k != "weight_balancer")
                this.weightDict[k] = calProb(v)
        }
    }

    fun fixWeightVar(configLevel: Int) {
        weightVar = calWeightVar(configLevel)
    }

    fun defaultWeightMap(games: Set<String>): HashMap<String, Float> {
        return HashMap<String, Float>().apply {
            games.forEach { put(it, 1.0f) }
        }
    }

    fun getSpellLeftCache(): HashMap<Boolean, HashMap<Int, LinkedList<Spell>>> {
        return rollSpellLeftCache
    }

    /** 保底替换后重建剩余卡池缓存，供 RefreshSpellManager 初始化使用 */
    fun commitRollCache(spells: List<Spell>) {
        val cache = HashMap<Boolean, HashMap<Int, LinkedList<Spell>>>()
        spells.forEach { spell ->
            cache.getOrPut(spell.rank != "L") { HashMap() }
                .getOrPut(spell.star) { LinkedList() }
                .add(spell)
        }
        rollSpellLeftCache = cache
    }

    // ---- Shared drawing helpers ----

    private fun buildSpellMap(
        type: Int,
        fileId: Int,
        games: Array<String>,
        ranks: Array<String>?,
        rand: Random
    ): HashMap<Int, HashMap<Boolean, HashMap<String, LinkedList<Spell>>>> {
        val map = HashMap<Int, HashMap<Boolean, HashMap<String, LinkedList<Spell>>>>()
        for ((star, isExMap) in get(type, fileId)) {
            val isExMap2 = HashMap<Boolean, HashMap<String, LinkedList<Spell>>>()
            for ((isEx, gameMap) in isExMap) {
                for ((game, spellList) in gameMap) {
                    if (game !in games) continue
                    val spellList2 = spellList.filter { ranks == null || it.rank in ranks }
                    if (spellList2.isNotEmpty()) {
                        val gameMap2 = isExMap2.getOrPut(isEx) { HashMap<String, LinkedList<Spell>>() }
                        gameMap2[game] = LinkedList(spellList2.shuffled(rand))
                    }
                }
            }
            if (isExMap2.isNotEmpty()) map[star] = isExMap2
        }
        return map
    }

    private fun buildSpellMapFromSpells(
        source: Array<Spell>,
        games: Array<String>,
        ranks: Array<String>?,
        rand: Random
    ): HashMap<Int, HashMap<Boolean, HashMap<String, LinkedList<Spell>>>> {
        val map = HashMap<Int, HashMap<Boolean, HashMap<String, LinkedList<Spell>>>>()
        source
            .filter { it.game in games && (ranks == null || it.rank in ranks) }
            .shuffled(rand)
            .forEach { spell ->
                map.getOrPut(spell.star) { hashMapOf() }
                    .getOrPut(spell.rank != "L") { hashMapOf() }
                    .getOrPut(spell.game) { LinkedList() }
                    .add(spell)
            }
        return map
    }

    private fun buildWeightMaps(
        map: HashMap<Int, HashMap<Boolean, HashMap<String, LinkedList<Spell>>>>,
        maxStar: Int
    ): HashMap<Int, HashMap<String, Float>> {
        val weightMaps = HashMap<Int, HashMap<String, Float>>()
        val availableGames = map.values
            .flatMap { isExMap -> isExMap.values }
            .flatMap { gameMap -> gameMap.keys }
            .toSet()
        for (i in 1..maxStar) {
            weightMaps[i] = defaultWeightMap(availableGames).apply {
                weightDict.forEach { (game, weight) ->
                    if (game in availableGames) {
                        this[game] = weight
                    }
                }
            }
        }
        return weightMaps
    }

    private fun placeExSpells(
        result: Array<Spell?>,
        stars: IntArray,
        exPos: IntArray,
        map: HashMap<Int, HashMap<Boolean, HashMap<String, LinkedList<Spell>>>>,
        weightMaps: HashMap<Int, HashMap<String, Float>>,
        spellIds: HashSet<String>,
        rand: Random,
        exDrawStar: Int,
    ) {
        for (i in exPos.indices) {
            var index = exPos[i]
            var firstTry = true
            tryOnce@ while (true) {
                if (firstTry) {
                    firstTry = false
                } else {
                    index = (index + 1) % result.size
                    if (index == exPos[i]) throw HandlerException("EX符卡数量不足")
                    if (index in exPos) continue
                }
                val isExMap = map[stars[index]] ?: continue
                val gameMap = isExMap[true] ?: continue

                val spell = drawSpellWithWeight(gameMap, weightMaps, spellIds, rand, exDrawStar)
                    ?: continue@tryOnce

                exPos[i] = index
                result[index] = spell
                break
            }
        }
    }

    // ---- Unified draw method ----

    /**
     * Unified spell drawing method. Replaces the old get(), getOD(), getBPOD().
     *
     * @param type NORMAL_GAME or BP_GAME
     * @param fileId spell card file version
     * @param games which Touhou games to include
     * @param ranks rank filter (null = all)
     * @param exPos EX spell positions
     * @param stars star distribution array (may contain placeholder values like 7/6/13)
     * @param rand random number generator
     * @param upgrades rules for placeholder-star upgrade logic; empty = no upgrades (standard mode)
     * @param maxWeightStar the max star level for weight map initialization (6 for normal/OD, 4 for BPOD)
     * @param exDrawStar the star level used for weighted drawing of EX spells (6 for normal/OD, 4 for BPOD)
     * @param priorityIndices exact board positions that must be drawn before all other cards.
     * @param priorityStars star values that should be drawn first before general fill (e.g. 4,5 for OD; 3 for BPOD)
     */
    private fun draw(
        type: Int,
        fileId: Int,
        games: Array<String>,
        ranks: Array<String>?,
        exPos: IntArray,
        stars: IntArray,
        rand: Random,
        upgrades: List<UpgradeRule> = NO_UPGRADES,
        maxWeightStar: Int = 6,
        exDrawStar: Int = 6,
        priorityIndices: Set<Int> = emptySet(),
        priorityStars: Set<Int> = emptySet(),
    ): Array<Spell> {
        return drawFromMap(
            buildSpellMap(type, fileId, games, ranks, rand),
            exPos,
            stars,
            rand,
            upgrades,
            maxWeightStar,
            exDrawStar,
            priorityIndices,
            priorityStars
        )
    }

    private fun drawFromMap(
        map: HashMap<Int, HashMap<Boolean, HashMap<String, LinkedList<Spell>>>>,
        exPos: IntArray,
        stars: IntArray,
        rand: Random,
        upgrades: List<UpgradeRule> = NO_UPGRADES,
        maxWeightStar: Int = 6,
        exDrawStar: Int = 6,
        priorityIndices: Set<Int> = emptySet(),
        priorityStars: Set<Int> = emptySet(),
    ): Array<Spell> {
        val spellIds = HashSet<String>()
        val result = arrayOfNulls<Spell>(stars.size)
        val weightMaps = buildWeightMaps(map, maxWeightStar)

        // Phase 0: Draw fixed high-level positions before any other card generation.
        for (i in priorityIndices) {
            val star = stars[i]
            val isExMap = map[star] ?: throw HandlerException("${star}星符卡数量不足")
            val gameMap = isExMap[false] ?: throw HandlerException("${star}星符卡数量不足")

            val spell = drawSpellWithWeight(gameMap, weightMaps, spellIds, rand, star)
                ?: throw HandlerException("${star}星符卡数量不足")

            result[i] = spell
        }

        // Phase 1: Draw priority stars (e.g. 4★ and 5★ for OD, 3★ for BPOD)
        for (i in stars.indices) {
            if (result[i] != null) continue
            val star = stars[i]
            if (star !in priorityStars) continue

            val isExMap = map[star] ?: throw HandlerException("${star}星符卡数量不足")
            val gameMap = isExMap[false] ?: throw HandlerException("${star}星符卡数量不足")

            val spell = drawSpellWithWeight(gameMap, weightMaps, spellIds, rand, star)
                ?: throw HandlerException("${star}星符卡数量不足")

            result[i] = spell
        }

        // Phase 2: Upgrade placeholders (e.g. star=7→5★, star=6→4★ for OD; star=13→3★ for BPOD)
        for (rule in upgrades) {
            val placeholderIndices = stars.indices.filter { stars[it] == rule.placeholderStar }.toMutableList()
            placeholderIndices.shuffle(rand)
            for (i in placeholderIndices) {
                try {
                    val isExMap = map[rule.targetStar] ?: continue
                    val gameMap = isExMap[false] ?: continue

                    val spell = drawSpellWithWeight(gameMap, weightMaps, spellIds, rand, rule.targetStar)

                    spell?.let {
                        result[i] = it
                    } ?: run {
                        stars[i] = rule.fallbackStar
                    }
                } catch (e: Exception) {
                    stars[i] = rule.fallbackStar
                }
            }
        }

        // Phase 3: Fill remaining positions (1-3 star or 1-2 star)
        for (i in stars.indices) {
            if (result[i] != null) continue

            val star = stars[i]
            val isExMap = map[star] ?: throw HandlerException("${star}星符卡数量不足")
            val gameMap = isExMap[false] ?: throw HandlerException("${star}星符卡数量不足")

            val spell = drawSpellWithWeight(gameMap, weightMaps, spellIds, rand, star)
                ?: throw HandlerException("${star}星符卡数量不足")

            result[i] = spell
        }

        // Phase 4: Place EX spells
        placeExSpells(result, stars, exPos, map, weightMaps, spellIds, rand, exDrawStar)

        constructRollCache(map)
        return result.filterNotNull().toTypedArray()
    }

    fun getFromCustomPool(
        source: Array<Spell>,
        games: Array<String>,
        ranks: Array<String>?,
        exPos: IntArray,
        stars: IntArray,
        rand: Random,
        priorityIndices: Set<Int> = emptySet(),
        upgrades: List<UpgradeRule> = NO_UPGRADES,
        priorityStars: Set<Int> = emptySet(),
        maxWeightStar: Int = 6,
        exDrawStar: Int = 6,
    ): Array<Spell> = drawFromMap(
        buildSpellMapFromSpells(source, games, ranks, rand),
        exPos,
        stars,
        rand,
        upgrades,
        maxWeightStar,
        exDrawStar,
        priorityIndices,
        priorityStars
    )

    // ---- Public API (thin wrappers over draw) ----

    /**
     * 标准赛/Link赛随符卡
     */
    fun get(
        type: Int,
        fileId: Int,
        games: Array<String>,
        ranks: Array<String>?,
        exPos: IntArray,
        stars: IntArray,
        rand: Random,
        priorityIndices: Set<Int> = emptySet(),
    ): Array<Spell> = draw(type, fileId, games, ranks, exPos, stars, rand, priorityIndices = priorityIndices)

    fun getWithHighLevelDowngrade(
        type: Int,
        fileId: Int,
        games: Array<String>,
        ranks: Array<String>?,
        exPos: IntArray,
        stars: IntArray,
        rand: Random,
        priorityIndices: Set<Int> = emptySet(),
    ): Array<Spell> = draw(
        type, fileId, games, ranks, exPos, stars, rand,
        upgrades = OD_UPGRADES,
        priorityIndices = priorityIndices,
        priorityStars = setOf(4, 5),
    )

    /**
     * OD赛随符卡 (4/5★优先抽取，star=7/6升级降级逻辑)
     */
    fun getOD(
        type: Int,
        fileId: Int,
        games: Array<String>,
        ranks: Array<String>?,
        exPos: IntArray,
        stars: IntArray,
        rand: Random,
        priorityIndices: Set<Int> = emptySet(),
    ): Array<Spell> = getWithHighLevelDowngrade(
        type, fileId, games, ranks, exPos, stars, rand, priorityIndices
    )

    /**
     * BPOD赛随符卡 (3★优先抽取，star=13升级降级逻辑)
     */
    fun getBPOD(
        type: Int,
        fileId: Int,
        games: Array<String>,
        ranks: Array<String>?,
        exPos: IntArray,
        stars: IntArray,
        rand: Random
    ): Array<Spell> = draw(
        type, fileId, games, ranks, exPos, stars, rand,
        upgrades = BPOD_UPGRADES,
        maxWeightStar = 4,
        exDrawStar = 4,
        priorityStars = setOf(3),
    )

    fun getSpellById(type: Int, fileId: Int, id: Int): Spell? = cache.get(type)?.get(fileId)?.spellsByIndex?.get(id)

    private fun XSSFCell?.getFloatValue(): Float {
        if (this == null) return 0f
        if (cellType == STRING)
            return stringCellValue.ifBlank { return 0f }.toFloat()
        return numericCellValue.toFloat()
    }

    private fun buildNormalSpell(row: XSSFRow): Spell? {
        if (row.lastCellNum < 15) return null
        return Spell(
            index = row.getCell(0).numericCellValue.toInt(),
            game = row.getCell(1).numericCellValue.toInt().toString(),
            name = row.getCell(3).stringCellValue.trim(),
            rank = row.getCell(5).stringCellValue.trim(),
            star = row.getCell(6).numericCellValue.toInt(),
            desc = row.getCell(4)?.stringCellValue?.trim() ?: "",
            id = row.getCell(8)?.numericCellValue?.toInt() ?: 0,
            fastest = row.getCell(9).getFloatValue(),
            missTime = row.getCell(10).getFloatValue(),
            powerWeight = row.getCell(11).getFloatValue(),
            difficulty = row.getCell(12).getFloatValue(),
            changeRate = row.getCell(13).getFloatValue(),
            maxCapRate = row.getCell(14).getFloatValue(),
        )
    }

    private fun buildBPSpell(row: XSSFRow): Spell? {
        if (row.lastCellNum < 15) return null
        return Spell(
            index = row.getCell(0).numericCellValue.toInt(),
            game = row.getCell(1).numericCellValue.toInt().toString(),
            name = row.getCell(3).stringCellValue.trim(),
            rank = row.getCell(5).stringCellValue.trim(),
            star = row.getCell(7).numericCellValue.toInt(),
            desc = row.getCell(4)?.stringCellValue?.trim() ?: "",
            id = row.getCell(8).numericCellValue.toInt(),
            fastest = row.getCell(9).getFloatValue(),
            missTime = row.getCell(10).getFloatValue(),
            powerWeight = row.getCell(11).getFloatValue(),
            difficulty = row.getCell(12).getFloatValue(),
            changeRate = row.getCell(13).getFloatValue(),
            maxCapRate = row.getCell(14).getFloatValue(),
        )
    }

    private fun md5sum(fileName: String): String? {
        if (isWindows) return null
        val p = Runtime.getRuntime().exec(arrayOf("md5sum", fileName))
        if (!p.waitFor(1, TimeUnit.SECONDS)) {
            logger.error("shell execute failed: md5sum $fileName")
            return null
        }
        p.inputStream.use { `is` ->
            BufferedReader(InputStreamReader(`is`)).use {
                val md5 = it.readLine()
                logger.debug(md5)
                return md5
            }
        }
    }

    private class Config(
        val spellBuilder: (XSSFRow) -> Spell?
    ) {
        var md5sum: Set<String>? = null

        /** star => ( isEx => ( game => spellList ) ) */
        var allSpells: Map<Int, Map<Boolean, Map<String, List<Spell>>>> = mapOf()

        var spellsByIndex: Map<Int, Spell> = mapOf()
    }

    private val isWindows = System.getProperty("os.name").lowercase().contains("windows")

    fun get(type: Int, fileCode: Int): Map<Int, Map<Boolean, Map<String, List<Spell>>>> {
        val (fileName, needUpdate) = parseControlFile(fileCode)
        val (config, typeCache) = getConfig(type, fileCode)
        val file = File(fileName).apply {
            if (!exists() || extension != "xlsx" || name.startsWith("log")) {
                throw HandlerException("无效符卡文件: $fileName")
            }
        }
        val currentMd5 = md5sum(file.path)?.let { hashSetOf(it) }
        if (!needUpdate && currentMd5 != null && config.md5sum == currentMd5) {
            return config.allSpells
        }
        XSSFWorkbook(OPCPackage.open(file, PackageAccess.READ)).use { wb ->
            val sheet = wb.getSheetAt(0)
            val tempSpells = HashMap<Int, HashMap<Boolean, HashMap<String, ArrayList<Spell>>>>()
            val tempIndices = HashMap<Int, Spell>()

            for (i in 1..sheet.lastRowNum) {
                val row = sheet.getRow(i)
                config.spellBuilder(row)?.let { spell ->
                    tempSpells.getOrPut(spell.star) { hashMapOf() }
                        .getOrPut(spell.rank != "L") { hashMapOf() }
                        .getOrPut(spell.game) { arrayListOf() }
                        .add(spell)
                    tempIndices[spell.index] = spell
                }
            }

            config.md5sum = currentMd5
            config.allSpells = tempSpells
            config.spellsByIndex = tempIndices
        }
        if (needUpdate) updateControlFile(fileCode)
        return config.allSpells
    }

    private fun parseControlFile(fileCode: Int): Pair<String, Boolean> {
        val controlFile = File("spellcard_version.txt").takeIf { it.exists() }
            ?: throw HandlerException("控制文件不存在")

        return controlFile.readLines().asSequence()
            .map { it.split(",").map { s -> s.trim() } }
            .find { it.first().toIntOrNull() == fileCode }
            ?.let {
                val filename = it[1]
                val updateFlag = it.getOrNull(2)?.equals("update", true) ?: false
                filename to updateFlag
            } ?: throw HandlerException("未找到文件配置项: $fileCode")
    }

    private fun getConfig(type: Int, fileCode: Int): Pair<Config, MutableMap<Int, Config>> {
        val spellBuilder = when (type) {
            NORMAL_GAME -> ::buildNormalSpell
            BP_GAME -> ::buildBPSpell
            else -> throw IllegalArgumentException("不支持的比赛类型")
        }

        val typeCache = cache.getOrPut(type) { mutableMapOf() }
        return typeCache.getOrPut(fileCode) { Config(spellBuilder) } to typeCache
    }

    private fun updateControlFile(fileCode: Int) {
        File("spellcard_version.txt").let { controlFile ->
            val updatedLines = controlFile.readLines().map { line ->
                line.split(",").map { it.trim() }.let { parts ->
                    when {
                        parts.first().toIntOrNull() == fileCode && parts.size > 2 ->
                            parts.take(2).joinToString(", ")

                        else -> line
                    }
                }
            }
            controlFile.writeText(updatedLines.joinToString("\n"))
        }
    }

    private fun constructRollCache(map: HashMap<Int, HashMap<Boolean, HashMap<String, LinkedList<Spell>>>>) {
        val remainingSpellsTemp = HashMap<Boolean, HashMap<Int, LinkedList<Spell>>>()
        for ((star, isExMap) in map) {
            for ((isEx, gameMap) in isExMap) {
                val spellsForStarAndEx = LinkedList<Spell>()
                for ((game, spellList) in gameMap) {
                    spellsForStarAndEx.addAll(spellList)
                }
                if (spellsForStarAndEx.isNotEmpty()) {
                    val starMap = remainingSpellsTemp.getOrPut(isEx) { HashMap() }
                    starMap[star] = spellsForStarAndEx
                }
            }
        }
        rollSpellLeftCache = remainingSpellsTemp
    }

    private fun weightedRandomGame(
        gameMap: HashMap<String, LinkedList<Spell>>,
        weightMap: HashMap<String, Float>,
        rand: Random
    ): String? {
        if (gameMap.isEmpty()) return null

        val totalWeight = weightMap.entries
            .filter { it.key in gameMap.keys }
            .sumOf { it.value.toDouble() }

        if (totalWeight <= 0.0) {
            return gameMap.keys.randomOrNull(rand)
        }

        val randomValue = max(min(rand.nextDouble() * totalWeight, 10000.0), 0.0001)
        var currentWeight = 0.0

        for ((game, weight) in weightMap) {
            if (game !in gameMap.keys) continue
            currentWeight += weight.toDouble()
            if (randomValue <= currentWeight) {
                return game
            }
        }

        return weightMap.keys.lastOrNull { it in gameMap.keys }
    }

    private fun drawSpellWithWeight(
        gameMap: HashMap<String, LinkedList<Spell>>,
        weightMaps: HashMap<Int, HashMap<String, Float>>,
        spellIds: HashSet<String>,
        rand: Random,
        star: Int,
    ): Spell? {
        var spell: Spell? = null
        var selectedGame: String? = null
        val weightMap = weightMaps.get(star) ?: defaultWeightMap(gameMap.keys)

        do {
            selectedGame = weightedRandomGame(gameMap, weightMap, rand) ?: return null
            val spellList = gameMap[selectedGame] ?: continue

            if (spellList.isEmpty()) {
                gameMap.remove(selectedGame)
                weightMap.remove(selectedGame)
                continue
            }

            spell = spellList.removeFirst()
            if (spellList.isEmpty()) {
                gameMap.remove(selectedGame)
                weightMap.remove(selectedGame)
            }
        } while (!spellIds.add("${spell!!.game}-${spell.id}"))

        for (wm in weightMaps.values) {
            if (wm.containsKey(selectedGame)) {
                wm[selectedGame] = wm[selectedGame]!!.times(weightVar)
            }
        }

        return spell
    }

    private val cache = mutableMapOf<Int, MutableMap<Int, Config>>()
}
