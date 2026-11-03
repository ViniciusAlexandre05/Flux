package com.pcbsolderview.parser.pnp

/**
 * Pick & Place files carry no body size, so the hit-test box is guessed from the footprint name.
 * Returns (width, height) in micrometres at rotation 0.
 */
object FootprintSizeGuesser {

    // Imperial chip codes -> body size (µm). Metric codes are mapped to the same entries.
    private val chip = mapOf(
        "01005" to (400L to 200L),
        "0201" to (600L to 300L),
        "0402" to (1000L to 500L),
        "0603" to (1600L to 800L),
        "0805" to (2000L to 1250L),
        "1206" to (3200L to 1600L),
        "1210" to (3200L to 2500L),
        "1812" to (4500L to 3200L),
        "2010" to (5000L to 2500L),
        "2512" to (6300L to 3200L),
    )
    private val metricToImperial = mapOf(
        "0402" to "01005", "0603" to "0201", "1005" to "0402", "1608" to "0603", "2012" to "0805",
        "3216" to "1206", "3225" to "1210", "4532" to "1812", "5025" to "2010", "6332" to "2512",
    )

    private val packages = listOf(
        Regex("SOT-?23", RegexOption.IGNORE_CASE) to (3000L to 2500L),
        Regex("SOT-?223", RegexOption.IGNORE_CASE) to (7000L to 6500L),
        Regex("SOD-?123", RegexOption.IGNORE_CASE) to (3700L to 1600L),
        Regex("SOD-?323", RegexOption.IGNORE_CASE) to (2500L to 1300L),
        Regex("SMA|DO-?214AC", RegexOption.IGNORE_CASE) to (5200L to 2600L),
        Regex("SMB|DO-?214AA", RegexOption.IGNORE_CASE) to (5400L to 3600L),
        Regex("TO-?252|DPAK", RegexOption.IGNORE_CASE) to (10000L to 6600L),
        Regex("TO-?220", RegexOption.IGNORE_CASE) to (10000L to 4500L),
    )

    private val pinCount = Regex("""(SOIC|SOP|SO|TSSOP|SSOP|MSOP|QFN|DFN|QFP|LQFP|TQFP|DIP|PDIP)-?(\d+)""", RegexOption.IGNORE_CASE)

    const val DEFAULT_SIZE = 2000L

    fun guess(footprint: String?): Pair<Long, Long> {
        if (footprint.isNullOrBlank()) return DEFAULT_SIZE to DEFAULT_SIZE
        val fp = footprint.uppercase()

        packages.firstOrNull { it.first.containsMatchIn(fp) }?.let { return it.second }

        pinCount.find(fp)?.let { m ->
            val family = m.groupValues[1]
            val pins = m.groupValues[2].toIntOrNull() ?: return@let
            return when (family) {
                "QFN", "DFN", "QFP", "LQFP", "TQFP" -> {
                    // Assume 0.5 mm pitch, pins on four sides (two for DFN).
                    val perSide = if (family == "DFN") pins / 2 else pins / 4
                    val side = (perSide * 500L + 1000L).coerceAtLeast(2000L)
                    side to side
                }
                "DIP", "PDIP" -> ((pins / 2) * 2540L) to 7620L
                else -> {
                    val pitch = if (family in setOf("TSSOP", "SSOP", "MSOP")) 650L else 1270L
                    ((pins / 2) * pitch) to if (pitch == 1270L) 6000L else 4400L
                }
            }
        }

        // Chip code: imperial first (most common in Altium libraries), metric as fallback.
        Regex("""(?<!\d)(\d{4,5})(?!\d)""").findAll(fp).forEach { m ->
            val code = m.groupValues[1]
            chip[code]?.let { return it }
            metricToImperial[code]?.let { chip[it] }?.let { return it }
        }
        return DEFAULT_SIZE to DEFAULT_SIZE
    }
}
