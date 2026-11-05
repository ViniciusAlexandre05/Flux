package com.pcbsolderview.model

/** "R2" < "R10" < "RN1"; letters compared case-insensitively, digit runs numerically. */
val NaturalOrder: Comparator<String> = Comparator { a, b ->
    var i = 0; var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]; val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            val si = i; val sj = j
            while (i < a.length && a[i].isDigit()) i++
            while (j < b.length && b[j].isDigit()) j++
            val na = a.substring(si, i).trimStart('0'); val nb = b.substring(sj, j).trimStart('0')
            if (na.length != nb.length) return@Comparator na.length - nb.length
            val c = na.compareTo(nb)
            if (c != 0) return@Comparator c
        } else {
            val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (c != 0) return@Comparator c
            i++; j++
        }
    }
    (a.length - i) - (b.length - j)
}

val ComponentOrder: Comparator<Component> = compareBy(NaturalOrder) { it.refDes }

/** Normalises a component value for matching: "100 nF" == "100nf", "4K7" == "4k7". */
fun normalizeValue(v: String): String = v.lowercase().filterNot { it.isWhitespace() }

/**
 * True when [a] and [b] are the same part: same part number when both have one; otherwise same
 * value (or description, if neither has a value) and same footprint. Unknown parts never match.
 */
fun sameKind(a: Component, b: Component): Boolean {
    val pa = a.partNumber?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val pb = b.partNumber?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    if (pa != null && pb != null) return pa == pb
    val va = (a.value ?: a.description)?.let { normalizeValue(it) } ?: return false
    val vb = (b.value ?: b.description)?.let { normalizeValue(it) } ?: return false
    return va == vb && a.footprint?.lowercase() == b.footprint?.lowercase()
}

/** Every component identical to [c] (including [c]), in natural designator order. */
fun Board.identicalTo(c: Component): List<Component> =
    components.filter { it.refDes == c.refDes || sameKind(it, c) }.sortedWith(ComponentOrder)

/** How a part is mounted, from its pads. */
enum class Mount { SMD, THROUGH_HOLE, MIXED }

/** Null when the part has no pads (e.g. a Pick & Place board without geometry). */
fun Board.mountOf(c: Component): Mount? {
    val pads = padsOf(c)
    if (pads.isEmpty()) return null
    val th = pads.any { it.isThroughHole }; val smd = pads.any { !it.isThroughHole }
    return when { th && smd -> Mount.MIXED; th -> Mount.THROUGH_HOLE; else -> Mount.SMD }
}

/** The All / SMD / PTH switch on the highlight chip. A part with any through-hole pin counts as PTH. */
enum class MountFilter(val label: String) { ALL("All"), SMD("SMD"), PTH("PTH") }

fun Board.filterByMount(parts: List<Component>, filter: MountFilter): List<Component> = when (filter) {
    MountFilter.ALL -> parts
    MountFilter.SMD -> parts.filter { mountOf(it) == Mount.SMD }
    MountFilter.PTH -> parts.filter { mountOf(it).let { m -> m == Mount.THROUGH_HOLE || m == Mount.MIXED } }
}

/**
 * "Custom" search inside a group: true when every word of [query] appears in the designator,
 * value, description, footprint, part number or manufacturer (case and spaces ignored).
 * "R1" matches R1 and R10; "100n 0402" matches 100 nF capacitors in 0402.
 */
fun Component.matchesQuery(query: String): Boolean {
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { normalizeValue(it) }
    if (words.isEmpty()) return true
    val hay = listOfNotNull(refDes, value, description, footprint, partNumber, manufacturer).joinToString(" ") { normalizeValue(it) }
    return words.all { it in hay }
}

class SearchResult(
    /** Exact designator match, if any — the view should zoom to it. */
    val exact: Component?,
    /** All matches, best first. */
    val matches: List<Component>,
)

/**
 * Searches by designator or value. Ranking: exact designator, designator prefix, exact value,
 * value contains, description/footprint/part number contains.
 */
fun Board.search(query: String, limit: Int = 200): SearchResult {
    val q = query.trim()
    if (q.isEmpty()) return SearchResult(null, emptyList())
    val qn = normalizeValue(q)
    fun rank(c: Component): Int? {
        val ref = c.refDes.lowercase()
        val value = c.value?.let { normalizeValue(it) }
        return when {
            ref == qn -> 0
            ref.startsWith(qn) -> 1
            value == qn -> 2
            value != null && value.contains(qn) -> 3
            listOfNotNull(c.description, c.footprint, c.partNumber).any { normalizeValue(it).contains(qn) } -> 4
            else -> null
        }
    }
    val ranked = components.mapNotNull { c -> rank(c)?.let { it to c } }
        .sortedWith(compareBy<Pair<Int, Component>> { it.first }.thenBy(NaturalOrder) { it.second.refDes })
        .take(limit)
    return SearchResult(ranked.firstOrNull { it.first == 0 }?.second, ranked.map { it.second })
}
