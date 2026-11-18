package com.pcbsolderview.model

import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * A simple single-line (stroke) font, like the plotter font Altium uses for silkscreen text.
 * Turns a string into polylines in board coordinates (µm, Y up), so text can be drawn as tracks
 * with round caps, exactly like the silkscreen strokes in an IPC-2581 export.
 *
 * Glyphs are drawn on a grid 4 units wide and 6 units tall (capitals and digits; lower case is
 * 4 tall with descenders to -2). As in Altium, the text height is the capital height measured on
 * the stroke centre lines (the stroke adds half its width around it), and characters advance by
 * about one text height (checked against Altium's own IPC-2581 export of the RP2040 board).
 */
object StrokeFont {
    private const val CAP = 6.0
    /** Advance per character in grid units (4 wide + spacing). */
    private const val ADVANCE = 6.0

    /** Polylines of [text] anchored at its bottom-left corner ([x], [y]), rotated CCW about it. */
    fun strokes(
        text: String,
        x: Long,
        y: Long,
        heightUm: Long,
        strokeUm: Long,
        rotationDeg: Double = 0.0,
        mirrored: Boolean = false,
    ): List<List<Point>> {
        if (text.isBlank() || heightUm <= 0) return emptyList()
        val unit = heightUm / CAP
        val r = Math.toRadians(rotationDeg)
        val c = cos(r); val s = sin(r)
        val out = ArrayList<List<Point>>()
        var penX = 0.0
        for (ch in text) {
            val glyph = glyphs[ch] ?: if (ch == ' ') null else MISSING
            glyph?.forEach { line ->
                out += line.map { (gx, gy) ->
                    var lx = (penX + gx) * unit
                    val ly = gy * unit
                    if (mirrored) lx = -lx
                    Point(x + (lx * c - ly * s).roundToLong(), y + (lx * s + ly * c).roundToLong())
                }
            }
            penX += ADVANCE
        }
        return out
    }

    /** Width of [text] in µm for a given height (centre lines plus the outer stroke halves). */
    fun width(text: String, heightUm: Long, strokeUm: Long): Long {
        if (text.isEmpty()) return 0
        val unit = heightUm / CAP
        return (((text.length - 1) * ADVANCE + 4) * unit + strokeUm).roundToLong()
    }

    private val MISSING: List<List<Pair<Double, Double>>> = parse("0,0 4,0 4,6 0,6 0,0")

    /** "x,y x,y; x,y x,y" → polylines. */
    private fun parse(spec: String): List<List<Pair<Double, Double>>> = spec.split(';').map { line ->
        line.trim().split(' ').filter { it.isNotEmpty() }.map { p -> p.split(',').let { it[0].toDouble() to it[1].toDouble() } }
    }

    private val glyphs: Map<Char, List<List<Pair<Double, Double>>>> = mapOf(
        '0' to "1,0 3,0 4,1 4,5 3,6 1,6 0,5 0,1 1,0; 4,5 0,1",
        '1' to "1,5 2,6 2,0; 1,0 3,0",
        '2' to "0,5 1,6 3,6 4,5 4,4 0,0 4,0",
        '3' to "0,5 1,6 3,6 4,5 4,4 3,3 1,3; 3,3 4,2 4,1 3,0 1,0 0,1",
        '4' to "3,0 3,6 0,2 4,2",
        '5' to "4,6 0,6 0,3 3,3 4,2 4,1 3,0 0,0",
        '6' to "4,5 3,6 1,6 0,5 0,1 1,0 3,0 4,1 4,2 3,3 0,3",
        '7' to "0,6 4,6 1,0",
        '8' to "1,3 0,4 0,5 1,6 3,6 4,5 4,4 3,3 1,3 0,2 0,1 1,0 3,0 4,1 4,2 3,3",
        '9' to "4,3 1,3 0,4 0,5 1,6 3,6 4,5 4,1 3,0 1,0 0,1",
        'A' to "0,0 0,4 2,6 4,4 4,0; 0,3 4,3",
        'B' to "0,0 0,6 3,6 4,5 4,4 3,3 0,3; 3,3 4,2 4,1 3,0 0,0",
        'C' to "4,5 3,6 1,6 0,5 0,1 1,0 3,0 4,1",
        'D' to "0,0 0,6 3,6 4,5 4,1 3,0 0,0",
        'E' to "4,6 0,6 0,0 4,0; 0,3 3,3",
        'F' to "4,6 0,6 0,0; 0,3 3,3",
        'G' to "4,5 3,6 1,6 0,5 0,1 1,0 3,0 4,1 4,3 2,3",
        'H' to "0,0 0,6; 4,0 4,6; 0,3 4,3",
        'I' to "1,6 3,6; 2,6 2,0; 1,0 3,0",
        'J' to "1,6 4,6; 3,6 3,1 2,0 1,0 0,1",
        'K' to "0,0 0,6; 4,6 0,2; 1,3 4,0",
        'L' to "0,6 0,0 4,0",
        'M' to "0,0 0,6 2,3 4,6 4,0",
        'N' to "0,0 0,6 4,0 4,6",
        'O' to "1,0 3,0 4,1 4,5 3,6 1,6 0,5 0,1 1,0",
        'P' to "0,0 0,6 3,6 4,5 4,4 3,3 0,3",
        'Q' to "1,0 3,0 4,1 4,5 3,6 1,6 0,5 0,1 1,0; 2,2 4,0",
        'R' to "0,0 0,6 3,6 4,5 4,4 3,3 0,3; 2,3 4,0",
        'S' to "4,5 3,6 1,6 0,5 0,4 1,3 3,3 4,2 4,1 3,0 1,0 0,1",
        'T' to "0,6 4,6; 2,6 2,0",
        'U' to "0,6 0,1 1,0 3,0 4,1 4,6",
        'V' to "0,6 2,0 4,6",
        'W' to "0,6 1,0 2,3 3,0 4,6",
        'X' to "0,0 4,6; 0,6 4,0",
        'Y' to "0,6 2,3 4,6; 2,3 2,0",
        'Z' to "0,6 4,6 0,0 4,0",
        'a' to "1,4 3,4 4,3 4,0; 4,2 1,2 0,1 1,0 3,0 4,1",
        'b' to "0,6 0,0; 0,3 1,4 3,4 4,3 4,1 3,0 1,0 0,1",
        'c' to "4,3 3,4 1,4 0,3 0,1 1,0 3,0 4,1",
        'd' to "4,6 4,0; 4,3 3,4 1,4 0,3 0,1 1,0 3,0 4,1",
        'e' to "0,2 4,2 4,3 3,4 1,4 0,3 0,1 1,0 3,0",
        'f' to "4,6 3,6 2,5 2,0; 1,4 3,4",
        'g' to "4,4 4,-1 3,-2 1,-2; 4,3 3,4 1,4 0,3 0,1 1,0 3,0 4,1",
        'h' to "0,6 0,0; 0,3 1,4 3,4 4,3 4,0",
        'i' to "2,4 2,0; 2,5.6 2,5.7",
        'j' to "3,4 3,-1 2,-2 1,-2; 3,5.6 3,5.7",
        'k' to "0,6 0,0; 4,4 0,1.5; 1.5,2.5 4,0",
        'l' to "1,6 2,6 2,1 3,0",
        'm' to "0,0 0,4; 0,3 1,4 2,3 2,0; 2,3 3,4 4,3 4,0",
        'n' to "0,0 0,4; 0,3 1,4 3,4 4,3 4,0",
        'o' to "1,0 3,0 4,1 4,3 3,4 1,4 0,3 0,1 1,0",
        'p' to "0,4 0,-2; 0,3 1,4 3,4 4,3 4,1 3,0 1,0 0,1",
        'q' to "4,4 4,-2; 4,3 3,4 1,4 0,3 0,1 1,0 3,0 4,1",
        'r' to "0,4 0,0; 0,2 2,4 4,4",
        's' to "4,4 1,4 0,3 1,2 3,2 4,1 3,0 0,0",
        't' to "2,6 2,1 3,0 4,0; 1,4 4,4",
        'u' to "0,4 0,1 1,0 3,0 4,1; 4,4 4,0",
        'v' to "0,4 2,0 4,4",
        'w' to "0,4 1,0 2,2 3,0 4,4",
        'x' to "0,0 4,4; 0,4 4,0",
        'y' to "0,4 2,0; 4,4 2,0 1,-2",
        'z' to "0,4 4,4 0,0 4,0",
        '.' to "2,0 2,0.1",
        ',' to "2,0.5 2,0 1,-1",
        '-' to "1,3 3,3",
        '_' to "0,-1 4,-1",
        '+' to "2,1 2,5; 0,3 4,3",
        '/' to "0,0 4,6",
        '\\' to "0,6 4,0",
        ':' to "2,1 2,1.1; 2,4 2,4.1",
        ';' to "2,4 2,4.1; 2,1 2,0 1,-1",
        '(' to "3,6 1.5,4.5 1.5,1.5 3,0",
        ')' to "1,6 2.5,4.5 2.5,1.5 1,0",
        '[' to "3,6 1,6 1,0 3,0",
        ']' to "1,6 3,6 3,0 1,0",
        '%' to "0,0 4,6; 0,6 1,6 1,5 0,5 0,6; 3,1 4,1 4,0 3,0 3,1",
        '#' to "1,0 1,6; 3,0 3,6; 0,2 4,2; 0,4 4,4",
        '*' to "2,1 2,5; 0,2 4,4; 0,4 4,2",
        '=' to "0,2 4,2; 0,4 4,4",
        '!' to "2,6 2,2; 2,0 2,0.1",
        '?' to "0,5 1,6 3,6 4,5 4,4 2,3 2,2; 2,0 2,0.1",
        '\'' to "2,6 2,4",
        '"' to "1,6 1,4; 3,6 3,4",
        '<' to "4,6 0,3 4,0",
        '>' to "0,6 4,3 0,0",
        '&' to "4,0 0,4 0,5 1,6 2,6 3,5 3,4 0,1 1,0 2,0 4,2",
        '@' to "3,2 3,4 1,4 1,2 3,2 4,2 4,5 3,6 1,6 0,5 0,1 1,0 4,0",
        '~' to "0,3 1,4 3,2 4,3",
        '|' to "2,-1 2,6",
        '$' to "4,5 1,5 0,4 1,3 3,3 4,2 3,1 0,1; 2,6 2,0",
        '^' to "0,4 2,6 4,4",
        '{' to "3,6 2,5 2,4 1,3 2,2 2,1 3,0",
        '}' to "1,6 2,5 2,4 3,3 2,2 2,1 1,0",
        'Ω' to "0,0 1,0 1,1 0,3 0,5 1,6 3,6 4,5 4,3 3,1 3,0 4,0",
        'µ' to "0,-2 0,4; 0,1 1,0 3,0 4,1; 4,4 4,0",
        'μ' to "0,-2 0,4; 0,1 1,0 3,0 4,1; 4,4 4,0",
        '±' to "2,2 2,6; 0,4 4,4; 0,0 4,0",
        '°' to "1,6 2,6 2,5 1,5 1,6",
    ).mapValues { parse(it.value) }
}
