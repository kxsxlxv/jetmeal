package com.kxsxlxv.jetmeal.data

import com.kxsxlxv.jetmeal.domain.FoodCandidate
import java.util.Locale
import kotlin.math.abs

/** Fast, deterministic, local-only matching. No network/LLM call per keystroke.
 * Synonyms are conservative: a generic food never inherits the grams or
 * macronutrients of another item just because its name is similar. */
internal enum class NutrientSearchIntent { Protein, LowCalorie, LowFat }

internal data class ParsedFoodSearch(
    val words: List<String>,
    val normalizedText: String,
    val nutrient: NutrientSearchIntent?,
)

internal data class SmartFoodMatch(val relevance: Double, val reason: String)

private val separators = Regex("[^\\p{L}\\p{N}]+")
private val whitespace = Regex("\\s+")

/** Treat yo/ye, punctuation, hyphens and mixed case the same. */
internal fun normalizeFoodSearch(value: String): String =
    value.lowercase(Locale.ROOT).replace('ё', 'е')
        .replace(separators, " ").trim().replace(whitespace, " ")

/** Deliberately small, auditable aliases, not a universal association between
 * food families (e.g. no assumption that every cheese is cottage cheese). */
private val foodAliases = mapOf(
    "гречка" to listOf("гречневая", "гречневый", "гречневые"),
    "овсянка" to listOf("овсяная", "овсяные хлопья", "овсяный"),
    "картошка" to listOf("картофель", "картофельный"),
    "курогрудка" to listOf("куриная грудка", "грудка куриная", "филе грудки"),
    "сгущенка" to listOf("молоко сгущенное", "сгущенное молоко"),
    "сгущенки" to listOf("молоко сгущенное", "сгущенное молоко"),
    "яйца" to listOf("яйцо", "яйца"),
    "яиц" to listOf("яйцо"),
    "ростикс" to listOf("rostic", "ростик"),
    "ростик" to listOf("rostic", "ростикс"),
    "кфс" to listOf("rostic", "ростикс", "kfc"),
    "kfc" to listOf("rostic", "ростикс"),
    "макдак" to listOf("вкусно точка", "mcdonald"),
    "макдональдс" to listOf("вкусно точка", "mcdonald"),
    "mcdonalds" to listOf("вкусно точка", "mcdonald"),
    "tvorog" to listOf("творог"),
    "grechka" to listOf("гречневая"),
    "sugar" to listOf("сахар"),
    "protein" to listOf("протеин", "белковый"),
)

/** Cyrillic entered on an English keyboard: "ndjhju" -> "творог".
 * It is only a fallback, so real Latin brands always win. */
private const val latinKeys = "qwertyuiopasdfghjklzxcvbnm"
private const val russianKeys = "йцукенгшщзфывапролдячсмить"
private fun fromWrongKeyboard(word: String): String? {
    if (word.length < 4 || !word.all { it in 'a'..'z' }) return null
    val converted = buildString {
        for (c in word) {
            val index = latinKeys.indexOf(c)
            if (index < 0) return null
            append(russianKeys[index])
        }
    }
    return converted.takeIf { it != word }
}

internal fun parseFoodSearch(query: String): ParsedFoodSearch {
    val normalized = normalizeFoodSearch(query)
    if (normalized.isEmpty()) return ParsedFoodSearch(emptyList(), "", null)
    val original = normalized.split(' ')
    var intent: NutrientSearchIntent? = null
    val rest = mutableListOf<String>()
    var index = 0
    while (index < original.size) {
        val word = original[index]
        val next = original.getOrNull(index + 1)
        when {
            word == "много" && next == "белка" -> {
                intent = NutrientSearchIntent.Protein; index += 2
            }
            word == "мало" && next == "калорий" -> {
                intent = NutrientSearchIntent.LowCalorie; index += 2
            }
            word.startsWith("высокобелков") || word.startsWith("протеинов") ||
                word.startsWith("белков") -> {
                intent = NutrientSearchIntent.Protein; index++
            }
            word.startsWith("низкокалор") || word.startsWith("малокалор") -> {
                intent = NutrientSearchIntent.LowCalorie; index++
            }
            word.startsWith("маложирн") -> {
                intent = NutrientSearchIntent.LowFat; index++
            }
            // "и" as a conjunction should not make menu brands unsearchable.
            word == "и" -> index++
            else -> { rest += word; index++ }
        }
    }
    return ParsedFoodSearch(rest, rest.joinToString(" "), intent)
}

/** Return no match for arbitrary near-homonyms shorter than four characters.
 * Optimal string alignment distance also handles adjacent transpositions. */
private fun nearTypo(a: String, b: String): Boolean {
    if (a.length < 4 || b.length < 4 || a.length > 40 || b.length > 40) return false
    val max = if (minOf(a.length,b.length) >= 7) 2 else 1
    if (abs(a.length-b.length) > max) return false
    val distance = Array(a.length+1) { IntArray(b.length+1) }
    for (i in 0..a.length) distance[i][0] = i
    for (j in 0..b.length) distance[0][j] = j
    for (i in 1..a.length) {
        for (j in 1..b.length) {
            val cost = if (a[i-1] == b[j-1]) 0 else 1
            var result = minOf(distance[i-1][j]+1, distance[i][j-1]+1,
                distance[i-1][j-1]+cost)
            if (i>1 && j>1 && a[i-1]==b[j-2] && a[i-2]==b[j-1])
                result=minOf(result,distance[i-2][j-2]+1)
            distance[i][j]=result
        }
    }
    return distance[a.length][b.length] <= max
}

/** Higher means stronger lexical evidence, not a nutrition or popularity score. */
private fun tokenQuality(word: String, tokens: List<String>, fuzzy: Boolean): Int {
    if (tokens.any { it==word }) return 4
    if (word.length>=2 && tokens.any { it.startsWith(word) }) return 3
    if (word.length>=4 && tokens.any { it.contains(word) }) return 2
    if (fuzzy && tokens.any { nearTypo(word,it) }) return 1
    return 0
}

private fun allTokenQuality(query:List<String>, tokens:List<String>, fuzzy:Boolean):Int {
    if (query.isEmpty()) return 0
    var minimum=4
    for (word in query) {
        val quality=tokenQuality(word,tokens,fuzzy)
        if(quality==0) return 0
        minimum=minOf(minimum,quality)
    }
    return minimum
}

private fun matchText(words:List<String>, name:String, brand:String, source:String,
                      fuzzy:Boolean):Int {
    val nameTokens=name.split(' ').filter(String::isNotBlank)
    val brandTokens=brand.split(' ').filter(String::isNotBlank)
    val sourceTokens=source.split(' ').filter(String::isNotBlank)
    val nameScore=allTokenQuality(words,nameTokens,fuzzy)
    val nameBrandScore=allTokenQuality(words,nameTokens+brandTokens,fuzzy)
    val fullScore=allTokenQuality(words,nameTokens+brandTokens+sourceTokens,fuzzy)
    if (nameScore>=2) return 700+nameScore
    if (nameBrandScore>=2) return if(brandTokens.isNotEmpty() &&
        allTokenQuality(words,brandTokens,false)>=2) 500+nameBrandScore
        else 600+nameBrandScore
    if (fullScore>=2) return 450+fullScore
    if (fuzzy && nameScore==1) return 300
    if (fuzzy && nameBrandScore==1) return 260
    if (fuzzy && fullScore==1) return 220
    return 0
}

internal fun smartFoodMatch(food: FoodCandidate, query:ParsedFoodSearch): SmartFoodMatch? {
    if (query.words.isEmpty()) {
        return if (query.nutrient == null) SmartFoodMatch(.5,"Frequently used personal food")
            else SmartFoodMatch(.5,"Nutrient relevance")
    }
    val name = normalizeFoodSearch(food.name)
    val brand = normalizeFoodSearch(food.brand.orEmpty())
    val source = normalizeFoodSearch(food.source.orEmpty())
    val phrase = query.normalizedText
    if (name == phrase) return SmartFoodMatch(.99,"Exact normalized name")
    if (name.startsWith(phrase)) return SmartFoodMatch(.96,"Name prefix")
    if (name.contains(phrase)) return SmartFoodMatch(.93,"Name phrase")
    val direct = matchText(query.words,name,brand,source,fuzzy=false)
    if (direct>0) return SmartFoodMatch(when {
        direct>=700 -> .88
        direct>=600 -> .82
        direct>=500 -> .79
        else -> .75
    },"Matching name, brand or source")

    // Expand only known aliases and wrong keyboard layout. Never match on
    // a synonym when the original query had a stronger literal match.
    val replacements = query.words.map { token ->
        val aliases = foodAliases[token].orEmpty().map(::normalizeFoodSearch)
        val keyboard=fromWrongKeyboard(token)?.let(::normalizeFoodSearch)
        val candidates=aliases+listOfNotNull(keyboard)
        candidates.distinct().take(8)
    }
    var aliasesFound=false
    var bestAlias=0
    fun evaluate(position:Int, expanded:List<String>, altered:Boolean) {
        if(position==query.words.size) {
            if(!altered) return
            val score=matchText(expanded,name,brand,source,false)
            if(score>bestAlias) {
                bestAlias=score
                aliasesFound=true
            }
            return
        }
        val word=query.words[position]
        evaluate(position+1,expanded+word,altered)
        for(alternative in replacements[position]) {
            evaluate(position+1,expanded+alternative.split(' ').filter(String::isNotBlank),true)
        }
    }
    // Bound combinatorial growth when a user enters many aliases.
    if (query.words.size<=5 && replacements.count{it.isNotEmpty()}<=3)
        evaluate(0,emptyList(),false)
    if(aliasesFound && bestAlias>0) return SmartFoodMatch(.68,"Matching synonym or keyboard layout")

    if (matchText(query.words,name,brand,source,true)>0)
        return SmartFoodMatch(.58,"Similar spelling")
    return null
}

internal fun nutrientSearchValue(food:FoodCandidate, intent:NutrientSearchIntent?):Double =
    when(intent) {
        NutrientSearchIntent.Protein ->
            if(food.nutrition.calories>0.0) 100.0*food.nutrition.protein/food.nutrition.calories
            else 0.0
        NutrientSearchIntent.LowCalorie -> -100.0*food.nutrition.calories/food.amount
        NutrientSearchIntent.LowFat -> -100.0*food.nutrition.fat/food.amount
        null -> 0.0
    }
