package com.reactnativeroom.game

import com.reactnativeroom.database.CaroMove

data class WinResult(
    val isWin: Boolean,
    val winner: String? = null,           // "X" or "O"
    val winningCells: List<Pair<Int, Int>> = emptyList()
)

object WinChecker {

    private const val WIN_COUNT = 5
    private const val BOARD_SIZE = 15

    // Four direction pairs: horizontal, vertical, diagonal-down, diagonal-up
    private val directions = listOf(
        Pair(1, 0),   // horizontal →
        Pair(0, 1),   // vertical ↓
        Pair(1, 1),   // diagonal ↘
        Pair(1, -1)   // diagonal ↗
    )

    /**
     * Check if the last move results in a win.
     * Scans 4 axes from the last placed move, counting consecutive same symbols.
     */
    fun checkWin(moves: List<CaroMove>, lastMove: CaroMove): WinResult {
        // Build a lookup map: (x, y) -> symbol
        val board = HashMap<Pair<Int, Int>, String>(moves.size)
        for (move in moves) {
            board[Pair(move.x, move.y)] = move.playerSymbol
        }

        val symbol = lastMove.playerSymbol
        val x = lastMove.x
        val y = lastMove.y

        for ((dx, dy) in directions) {
            val cells = mutableListOf(Pair(x, y))

            // Count forward
            var i = 1
            while (i < WIN_COUNT) {
                val nx = x + dx * i
                val ny = y + dy * i
                if (nx !in 0 until BOARD_SIZE || ny !in 0 until BOARD_SIZE) break
                if (board[Pair(nx, ny)] != symbol) break
                cells.add(Pair(nx, ny))
                i++
            }

            // Count backward
            i = 1
            while (i < WIN_COUNT) {
                val nx = x - dx * i
                val ny = y - dy * i
                if (nx !in 0 until BOARD_SIZE || ny !in 0 until BOARD_SIZE) break
                if (board[Pair(nx, ny)] != symbol) break
                cells.add(0, Pair(nx, ny))
                i++
            }

            if (cells.size >= WIN_COUNT) {
                return WinResult(
                    isWin = true,
                    winner = symbol,
                    winningCells = cells.take(WIN_COUNT)
                )
            }
        }

        return WinResult(isWin = false)
    }

    /**
     * Check if the board is full (draw condition).
     */
    fun isDraw(moveCount: Int): Boolean {
        return moveCount >= BOARD_SIZE * BOARD_SIZE
    }
}
