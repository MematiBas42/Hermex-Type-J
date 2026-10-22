package io.github.jcartu.hermex.ui.feature.chat

/** Searchable fields for one composer autocomplete row. */
data class SlashRankableFields(
    val name: String,
    val label: String? = null,
    val shortDescription: String? = null,
    val description: String? = null,
)

/**
 * Lower-is-better scorer ported from
 * `Features/Chat/SlashCommandRanker.swift`.
 *
 * Match strength is exact, prefix, word-boundary prefix, substring, then fuzzy
 * subsequence. Name matches beat labels, which beat descriptive text. Sorting
 * has an explicit original-index tiebreaker so equal rows are stable on every JVM.
 */
object SlashCommandRanker {
    const val RESULT_LIMIT: Int = 20

    private val boundaryMarkers = setOf('-', '_', '/')

    private data class FieldWeight(val base: Int, val fuzzy: Int?)

    private val nameWeight = FieldWeight(base = 0, fuzzy = 100)
    private val labelWeight = FieldWeight(base = 1, fuzzy = 110)
    private val shortDescriptionWeight = FieldWeight(base = 20, fuzzy = null)
    private val descriptionWeight = FieldWeight(base = 30, fuzzy = null)

    fun score(fields: SlashRankableFields, query: String): Int? =
        scoreNormalized(fields, normalize(query))

    fun <Item> rank(
        items: List<Item>,
        query: String,
        limit: Int = RESULT_LIMIT,
        fields: (Item) -> SlashRankableFields,
    ): List<Item> {
        val needle = normalize(query)
        if (needle.isEmpty()) return if (limit < 0) items else items.take(limit)
        if (limit <= 0) return emptyList()

        return items.mapIndexedNotNull { index, item ->
            val itemFields = fields(item)
            val score = scoreNormalized(itemFields, needle) ?: return@mapIndexedNotNull null
            RankedItem(
                item = item,
                score = score,
                label = normalize(itemFields.label ?: itemFields.name),
                name = normalize(itemFields.name),
                originalIndex = index,
            )
        }.sortedWith(
            compareBy<RankedItem<Item>> { it.score }
                .thenBy { it.label }
                .thenBy { it.name }
                .thenBy { it.originalIndex },
        ).take(limit).map(RankedItem<Item>::item)
    }

    private fun scoreNormalized(fields: SlashRankableFields, needle: String): Int? {
        if (needle.isEmpty()) return 0

        val weighted = listOf(
            fields.name to nameWeight,
            fields.label to labelWeight,
            fields.shortDescription to shortDescriptionWeight,
            fields.description to descriptionWeight,
        )
        var best: Int? = null
        for ((raw, weight) in weighted) {
            val value = raw?.let(::normalize).orEmpty()
            if (value.isEmpty()) continue
            val candidate = scoreField(value, needle, weight) ?: continue
            if (best == null || candidate < best) best = candidate
        }
        return best
    }

    private fun scoreField(field: String, needle: String, weight: FieldWeight): Int? = when {
        field == needle -> weight.base
        field.startsWith(needle) -> weight.base + 2
        hasBoundaryPrefix(field, needle) -> weight.base + 4
        field.contains(needle) -> weight.base + 6
        weight.fuzzy != null && isSubsequence(needle, field) -> weight.fuzzy
        else -> null
    }

    private fun hasBoundaryPrefix(field: String, needle: String): Boolean {
        for (index in field.indices) {
            if (field[index] !in boundaryMarkers) continue
            val wordStart = index + 1
            if (wordStart < field.length && field.regionMatches(wordStart, needle, 0, needle.length)) {
                return true
            }
        }
        return false
    }

    private fun isSubsequence(needle: String, field: String): Boolean {
        var fieldIndex = 0
        for (character in needle) {
            while (fieldIndex < field.length && field[fieldIndex] != character) fieldIndex += 1
            if (fieldIndex == field.length) return false
            fieldIndex += 1
        }
        return true
    }

    private fun normalize(value: String): String = value.trim().lowercase()

    private data class RankedItem<Item>(
        val item: Item,
        val score: Int,
        val label: String,
        val name: String,
        val originalIndex: Int,
    )
}
