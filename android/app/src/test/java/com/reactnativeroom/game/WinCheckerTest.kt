package com.reactnativeroom.game

import com.reactnativeroom.database.CaroMove
import org.junit.Assert.*
import org.junit.Test

class WinCheckerTest {

    private fun move(x: Int, y: Int, symbol: String, num: Int) =
        CaroMove(x = x, y = y, playerSymbol = symbol, moveNumber = num)

    // ── Horizontal win ─────────────────────────────────────────────────

    @Test
    fun `horizontal 5 in a row wins`() {
        val moves = listOf(
            move(0, 0, "X", 1),
            move(1, 0, "X", 3),
            move(2, 0, "X", 5),
            move(3, 0, "X", 7),
            move(4, 0, "X", 9),
            // O moves (filler)
            move(0, 1, "O", 2),
            move(1, 1, "O", 4),
            move(2, 1, "O", 6),
            move(3, 1, "O", 8),
        )
        val last = move(4, 0, "X", 9)
        val result = WinChecker.checkWin(moves, last)

        assertTrue(result.isWin)
        assertEquals("X", result.winner)
        assertEquals(5, result.winningCells.size)
    }

    @Test
    fun `horizontal win from middle placement`() {
        val moves = listOf(
            move(3, 5, "X", 1),
            move(4, 5, "X", 3),
            move(5, 5, "X", 5),
            // Place the missing one
            move(6, 5, "X", 7),
            move(7, 5, "X", 9),
            move(0, 0, "O", 2),
            move(0, 1, "O", 4),
            move(0, 2, "O", 6),
            move(0, 3, "O", 8),
        )
        val last = move(5, 5, "X", 5)
        val result = WinChecker.checkWin(moves, last)

        assertTrue(result.isWin)
        assertEquals("X", result.winner)
    }

    // ── Vertical win ───────────────────────────────────────────────────

    @Test
    fun `vertical 5 in a row wins`() {
        val moves = listOf(
            move(7, 0, "O", 2),
            move(7, 1, "O", 4),
            move(7, 2, "O", 6),
            move(7, 3, "O", 8),
            move(7, 4, "O", 10),
            move(0, 0, "X", 1),
            move(1, 0, "X", 3),
            move(2, 0, "X", 5),
            move(3, 0, "X", 7),
            move(4, 0, "X", 9),
        )
        val last = move(7, 4, "O", 10)
        val result = WinChecker.checkWin(moves, last)

        assertTrue(result.isWin)
        assertEquals("O", result.winner)
        assertEquals(5, result.winningCells.size)
    }

    // ── Diagonal wins ──────────────────────────────────────────────────

    @Test
    fun `diagonal down-right wins`() {
        val moves = listOf(
            move(0, 0, "X", 1),
            move(1, 1, "X", 3),
            move(2, 2, "X", 5),
            move(3, 3, "X", 7),
            move(4, 4, "X", 9),
            move(0, 1, "O", 2),
            move(0, 2, "O", 4),
            move(0, 3, "O", 6),
            move(0, 4, "O", 8),
        )
        val last = move(4, 4, "X", 9)
        val result = WinChecker.checkWin(moves, last)

        assertTrue(result.isWin)
        assertEquals("X", result.winner)
    }

    @Test
    fun `diagonal up-right wins`() {
        val moves = listOf(
            move(0, 4, "O", 2),
            move(1, 3, "O", 4),
            move(2, 2, "O", 6),
            move(3, 1, "O", 8),
            move(4, 0, "O", 10),
            move(5, 0, "X", 1),
            move(5, 1, "X", 3),
            move(5, 2, "X", 5),
            move(5, 3, "X", 7),
            move(5, 4, "X", 9),
        )
        val last = move(4, 0, "O", 10)
        val result = WinChecker.checkWin(moves, last)

        assertTrue(result.isWin)
        assertEquals("O", result.winner)
    }

    // ── No win ─────────────────────────────────────────────────────────

    @Test
    fun `4 in a row does not win`() {
        val moves = listOf(
            move(0, 0, "X", 1),
            move(1, 0, "X", 3),
            move(2, 0, "X", 5),
            move(3, 0, "X", 7),
            move(0, 1, "O", 2),
            move(1, 1, "O", 4),
            move(2, 1, "O", 6),
        )
        val last = move(3, 0, "X", 7)
        val result = WinChecker.checkWin(moves, last)

        assertFalse(result.isWin)
        assertNull(result.winner)
    }

    @Test
    fun `empty board no win`() {
        val result = WinChecker.checkWin(
            listOf(move(7, 7, "X", 1)),
            move(7, 7, "X", 1)
        )
        assertFalse(result.isWin)
    }

    @Test
    fun `broken line with opponent piece does not win`() {
        val moves = listOf(
            move(0, 0, "X", 1),
            move(1, 0, "X", 3),
            move(2, 0, "O", 2), // Opponent blocks
            move(3, 0, "X", 5),
            move(4, 0, "X", 7),
            move(5, 0, "X", 9),
            move(0, 1, "O", 4),
            move(0, 2, "O", 6),
            move(0, 3, "O", 8),
        )
        val last = move(5, 0, "X", 9)
        val result = WinChecker.checkWin(moves, last)

        assertFalse(result.isWin)
    }

    // ── Draw detection ─────────────────────────────────────────────────

    @Test
    fun `draw when board full`() {
        assertTrue(WinChecker.isDraw(225)) // 15 * 15
    }

    @Test
    fun `not draw when board not full`() {
        assertFalse(WinChecker.isDraw(224))
        assertFalse(WinChecker.isDraw(0))
        assertFalse(WinChecker.isDraw(100))
    }

    // ── Edge cases ─────────────────────────────────────────────────────

    @Test
    fun `win at board edge row 0`() {
        val moves = listOf(
            move(10, 0, "X", 1),
            move(11, 0, "X", 3),
            move(12, 0, "X", 5),
            move(13, 0, "X", 7),
            move(14, 0, "X", 9),
            move(0, 1, "O", 2),
            move(0, 2, "O", 4),
            move(0, 3, "O", 6),
            move(0, 4, "O", 8),
        )
        val last = move(14, 0, "X", 9)
        val result = WinChecker.checkWin(moves, last)

        assertTrue(result.isWin)
    }

    @Test
    fun `win at board edge column 14`() {
        val moves = listOf(
            move(14, 10, "O", 2),
            move(14, 11, "O", 4),
            move(14, 12, "O", 6),
            move(14, 13, "O", 8),
            move(14, 14, "O", 10),
            move(0, 0, "X", 1),
            move(1, 0, "X", 3),
            move(2, 0, "X", 5),
            move(3, 0, "X", 7),
            move(4, 0, "X", 9),
        )
        val last = move(14, 14, "O", 10)
        val result = WinChecker.checkWin(moves, last)

        assertTrue(result.isWin)
    }

    @Test
    fun `more than 5 in a row also wins`() {
        val moves = listOf(
            move(0, 0, "X", 1),
            move(1, 0, "X", 3),
            move(2, 0, "X", 5),
            move(3, 0, "X", 7),
            move(4, 0, "X", 9),
            move(5, 0, "X", 11),
            move(0, 1, "O", 2),
            move(0, 2, "O", 4),
            move(0, 3, "O", 6),
            move(0, 4, "O", 8),
            move(0, 5, "O", 10),
        )
        val last = move(5, 0, "X", 11)
        val result = WinChecker.checkWin(moves, last)

        assertTrue(result.isWin)
        assertEquals("X", result.winner)
    }
}
