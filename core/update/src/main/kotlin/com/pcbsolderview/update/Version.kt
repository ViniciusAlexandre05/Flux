package com.pcbsolderview.update

/**
 * A Flux version name: "0.1", "0.1.2", "0.1.3-test1". Numbers compare numerically
 * ("0.1.10" > "0.1.9"), missing parts count as 0 ("0.1" == "0.1.0"), and a version with a
 * suffix comes before the same version without one ("0.1.3-test1" < "0.1.3").
 *
 * Android itself only compares versionCode; this is for showing and ordering versions, and for
 * the PC version, which has no versionCode.
 */
data class Version(val numbers: List<Int>, val suffix: String? = null) : Comparable<Version> {

    override fun compareTo(other: Version): Int {
        for (i in 0 until maxOf(numbers.size, other.numbers.size)) {
            val c = numbers.getOrElse(i) { 0 }.compareTo(other.numbers.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return when {
            suffix == other.suffix -> 0
            suffix == null -> 1
            other.suffix == null -> -1
            else -> compareSuffix(suffix, other.suffix)
        }
    }

    /** True for test versions ("0.1.3-test1"). */
    val isTest: Boolean get() = suffix != null

    override fun toString() = numbers.joinToString(".") + (suffix?.let { "-$it" } ?: "")

    companion object {
        private val pattern = Regex("""^v?(\d+(?:\.\d+)*)(?:-([0-9A-Za-z.]+))?$""")

        fun parse(text: String): Version? {
            val m = pattern.matchEntire(text.trim()) ?: return null
            val numbers = m.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
            return Version(numbers, m.groupValues[2].ifEmpty { null })
        }

        /** "test2" < "test10": compare the letters, then the trailing number. */
        private fun compareSuffix(a: String, b: String): Int {
            val ra = Regex("""^(.*?)(\d*)$""").matchEntire(a)!!
            val rb = Regex("""^(.*?)(\d*)$""").matchEntire(b)!!
            val c = ra.groupValues[1].compareTo(rb.groupValues[1])
            if (c != 0) return c
            return (ra.groupValues[2].toLongOrNull() ?: 0).compareTo(rb.groupValues[2].toLongOrNull() ?: 0)
        }
    }
}
