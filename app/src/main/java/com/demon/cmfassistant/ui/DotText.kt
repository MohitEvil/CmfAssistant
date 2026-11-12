package com.demon.cmfassistant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/** A small 5x7 dot-matrix font, enough for the state labels (the Nothing-style look). */
private val GLYPHS: Map<Char, List<String>> = mapOf(
    'A' to listOf(".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
    'C' to listOf(".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."),
    'D' to listOf("####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."),
    'E' to listOf("#####", "#....", "#....", "####.", "#....", "#....", "#####"),
    'F' to listOf("#####", "#....", "#....", "####.", "#....", "#....", "#...."),
    'G' to listOf(".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".###."),
    'H' to listOf("#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
    'I' to listOf("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "#####"),
    'K' to listOf("#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"),
    'L' to listOf("#....", "#....", "#....", "#....", "#....", "#....", "#####"),
    'M' to listOf("#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"),
    'N' to listOf("#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#", "#...#"),
    'O' to listOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
    'P' to listOf("####.", "#...#", "#...#", "####.", "#....", "#....", "#...."),
    'R' to listOf("####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"),
    'S' to listOf(".####", "#....", "#....", ".###.", "....#", "....#", "####."),
    'T' to listOf("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."),
    'U' to listOf("#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
    'X' to listOf("#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#"),
    'Y' to listOf("#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."),
    'Z' to listOf("#####", "....#", "...#.", "..#..", ".#...", "#....", "#####")
)

/** Number of dot columns a string occupies (5 per letter plus a 1 column gap). */
fun dotTextCols(text: String): Int = text.length * 6 - 1

@Composable
fun DotText(
    text: String,
    color: Color,
    offColor: Color,
    pitch: Dp,
    modifier: Modifier = Modifier
) {
    val chars = text.uppercase()
    Canvas(modifier.size(pitch * dotTextCols(chars), pitch * 7)) {
        val p = pitch.toPx()
        chars.forEachIndexed { ci, ch ->
            val g = GLYPHS[ch]
            for (r in 0 until 7) {
                for (c in 0 until 5) {
                    val on = g != null && g[r][c] == '#'
                    drawCircle(
                        color = if (on) color else offColor,
                        radius = p * 0.34f,
                        center = Offset((ci * 6 + c + 0.5f) * p, (r + 0.5f) * p)
                    )
                }
            }
        }
    }
}
