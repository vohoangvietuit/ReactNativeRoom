package com.reactnativeroom.repository

import com.reactnativeroom.database.CaroDao
import com.reactnativeroom.database.CaroMove
import com.reactnativeroom.database.GameSession
import com.reactnativeroom.game.WinChecker
import com.reactnativeroom.game.WinResult
import kotlinx.coroutines.flow.Flow

data class MoveResult(
    val success: Boolean,
    val error: String? = null,
    val winResult: WinResult? = null,
    val isDraw: Boolean = false
)

class CaroRepository(private val dao: CaroDao) {

    // Live board updates for UI
    val observeMoves: Flow<List<CaroMove>> = dao.observeMoves()

    /**
     * Place a move on the board. Validates:
     * 1. Cell is empty
     * 2. Correct turn order
     * 3. Game not already finished
     * Returns MoveResult with win/draw info.
     */
    suspend fun placeMove(x: Int, y: Int, symbol: String, gameId: String): MoveResult {
        // Validate bounds
        if (x !in 0..14 || y !in 0..14) {
            return MoveResult(success = false, error = "Position out of bounds")
        }

        // Validate symbol
        if (symbol != "X" && symbol != "O") {
            return MoveResult(success = false, error = "Invalid symbol: $symbol")
        }

        // Check game status
        val session = dao.getSession(gameId)
        if (session?.status == "FINISHED") {
            return MoveResult(success = false, error = "Game is already finished")
        }

        // Check if cell is already occupied
        if (dao.getMoveAt(x, y) != null) {
            return MoveResult(success = false, error = "Cell ($x, $y) is already occupied")
        }

        // Validate turn order: even moves = X (host), odd moves = O (challenger)
        val moveCount = dao.getMoveCount()
        val expectedSymbol = if (moveCount % 2 == 0) "X" else "O"
        if (symbol != expectedSymbol) {
            return MoveResult(success = false, error = "Not your turn. Expected: $expectedSymbol")
        }

        // Insert the move
        val move = CaroMove(
            x = x,
            y = y,
            playerSymbol = symbol,
            moveNumber = moveCount + 1
        )

        try {
            dao.insertMove(move)
        } catch (e: Exception) {
            return MoveResult(success = false, error = "Failed to insert move: ${e.message}")
        }

        // Check for win
        val allMoves = dao.getAllMoves()
        val winResult = WinChecker.checkWin(allMoves, move)

        if (winResult.isWin) {
            dao.updateSessionStatus(gameId, "FINISHED", winResult.winner)
            return MoveResult(success = true, winResult = winResult)
        }

        // Check for draw
        if (WinChecker.isDraw(allMoves.size)) {
            dao.updateSessionStatus(gameId, "FINISHED", "DRAW")
            return MoveResult(success = true, isDraw = true)
        }

        return MoveResult(success = true)
    }

    /**
     * Apply a remote move received via BLE (no turn validation — host already validated)
     */
    suspend fun applyRemoteMove(move: CaroMove, gameId: String): WinResult? {
        try {
            dao.insertMove(move)
        } catch (_: Exception) {
            // Duplicate or conflict — ignore, host is source of truth
            return null
        }

        val allMoves = dao.getAllMoves()
        val winResult = WinChecker.checkWin(allMoves, move)
        if (winResult.isWin) {
            dao.updateSessionStatus(gameId, "FINISHED", winResult.winner)
        } else if (WinChecker.isDraw(allMoves.size)) {
            dao.updateSessionStatus(gameId, "FINISHED", "DRAW")
        }
        return if (winResult.isWin) winResult else null
    }

    /**
     * Full sync — replace local board with host's moves (for late-joining spectators)
     */
    suspend fun fullSync(moves: List<CaroMove>) {
        dao.clearMoves()
        dao.insertAll(moves)
    }

    suspend fun getBoard(): List<CaroMove> = dao.getAllMoves()

    suspend fun getMoveCount(): Int = dao.getMoveCount()

    suspend fun createSession(gameId: String, hostDeviceId: String): GameSession {
        val session = GameSession(gameId = gameId, hostDeviceId = hostDeviceId)
        dao.upsertSession(session)
        return session
    }

    suspend fun getSession(gameId: String): GameSession? = dao.getSession(gameId)

    suspend fun startGame(gameId: String) {
        dao.updateSessionStatus(gameId, "PLAYING")
    }

    suspend fun resetGame(gameId: String) {
        dao.clearMoves()
        dao.updateSessionStatus(gameId, "WAITING", null)
    }

    suspend fun clearAll() {
        dao.clearMoves()
        dao.clearSessions()
    }
}
