/**
 * Integration test: Full game flow.
 * Simulates: Host starts → Challenger joins → Start match → Alternating moves → Win detection.
 *
 * Uses mocked NativeModules.CaroGame to verify the complete game lifecycle.
 */

// Define mock INLINE in the factory to avoid TDZ issues
jest.mock('react-native', () => {
  const RN = jest.requireActual('react-native');
  RN.NativeModules.CaroGame = {
    startHosting: jest.fn(),
    joinGame: jest.fn(),
    startMatch: jest.fn(),
    stopGame: jest.fn(),
    placeMove: jest.fn(),
    getBoard: jest.fn().mockResolvedValue('[]'),
    getGameState: jest.fn().mockResolvedValue(
      JSON.stringify({
        gameId: '',
        status: 'WAITING',
        myRole: '',
        mySymbol: '',
        currentTurn: 'X',
        connectedPlayers: 0,
      }),
    ),
    addListener: jest.fn(),
    removeListeners: jest.fn(),
  };
  return RN;
});

import {renderHook, act} from '@testing-library/react-native';
import {NativeModules} from 'react-native';
import {useCaroGame} from '../../src/hooks/useCaroGame';

const mockCaroGame = NativeModules.CaroGame as any;

let moveNumber = 0;

function mockHostPlaceMove(x: number, y: number, symbol: string) {
  moveNumber++;
  return JSON.stringify({
    success: true,
    isWin: false,
    isDraw: false,
  });
}

function mockHostWinningMove(x: number, y: number, symbol: string, winCells: number[][]) {
  moveNumber++;
  return JSON.stringify({
    success: true,
    isWin: true,
    isDraw: false,
    winner: symbol,
    winningCells: JSON.stringify(winCells),
  });
}

beforeEach(() => {
  jest.clearAllMocks();
  moveNumber = 0;
  mockCaroGame.getBoard.mockResolvedValue('[]');
  mockCaroGame.getGameState.mockResolvedValue(
    JSON.stringify({
      gameId: '',
      status: 'WAITING',
      myRole: '',
      mySymbol: '',
      currentTurn: 'X',
      connectedPlayers: 0,
    }),
  );
});

describe('Full Game Flow Integration', () => {
  it('should complete a full game: host → start → moves → win', async () => {
    mockCaroGame.startHosting.mockResolvedValue('game123');
    mockCaroGame.startMatch.mockResolvedValue(undefined);

    const {result} = renderHook(() => useCaroGame());

    // ── Step 1: Host starts game ──
    await act(async () => {
      await result.current.startHosting('Alice');
    });
    expect(result.current.gameState.myRole).toBe('host');
    expect(result.current.gameState.mySymbol).toBe('X');
    expect(result.current.gameState.status).toBe('WAITING');

    // ── Step 2: Start match (after challenger connected) ──
    await act(async () => {
      await result.current.startMatch();
    });
    expect(result.current.gameState.status).toBe('PLAYING');

    // ── Step 3: Host places first move (X at 7,7) ──
    mockCaroGame.placeMove.mockResolvedValueOnce(mockHostPlaceMove(7, 7, 'X'));
    const move1 = await act(async () => {
      return await result.current.placeMove(7, 7);
    });
    expect(move1!.success).toBe(true);
    expect(move1!.isWin).toBe(false);
    expect(result.current.gameState.status).toBe('PLAYING');

    // ── Step 4: More moves ──
    mockCaroGame.placeMove.mockResolvedValueOnce(mockHostPlaceMove(8, 7, 'X'));
    await act(async () => {
      await result.current.placeMove(8, 7);
    });
    expect(result.current.gameState.status).toBe('PLAYING');

    mockCaroGame.placeMove.mockResolvedValueOnce(mockHostPlaceMove(9, 7, 'X'));
    await act(async () => {
      await result.current.placeMove(9, 7);
    });

    mockCaroGame.placeMove.mockResolvedValueOnce(mockHostPlaceMove(10, 7, 'X'));
    await act(async () => {
      await result.current.placeMove(10, 7);
    });

    // ── Step 5: Winning move (5th X in a row) ──
    const winCells = [[7, 7], [8, 7], [9, 7], [10, 7], [11, 7]];
    mockCaroGame.placeMove.mockResolvedValueOnce(
      mockHostWinningMove(11, 7, 'X', winCells),
    );
    mockCaroGame.getBoard.mockResolvedValueOnce('[]');

    const winMove = await act(async () => {
      return await result.current.placeMove(11, 7);
    });

    expect(winMove!.success).toBe(true);
    expect(winMove!.isWin).toBe(true);
    expect(result.current.gameState.status).toBe('FINISHED');
    expect(result.current.gameState.winner).toBe('X');
    expect(result.current.winningCells).toEqual(winCells);
  });

  it('should handle failed move gracefully', async () => {
    mockCaroGame.startHosting.mockResolvedValue('game456');
    mockCaroGame.startMatch.mockResolvedValue(undefined);

    const {result} = renderHook(() => useCaroGame());

    await act(async () => {
      await result.current.startHosting('Host');
    });
    await act(async () => {
      await result.current.startMatch();
    });

    // Simulate placing on occupied cell
    mockCaroGame.placeMove.mockResolvedValueOnce(
      JSON.stringify({
        success: false,
        isWin: false,
        isDraw: false,
        error: 'Cell (7, 7) is already occupied',
      }),
    );

    const failedMove = await act(async () => {
      return await result.current.placeMove(7, 7);
    });

    expect(failedMove!.success).toBe(false);
    expect(failedMove!.error).toBe('Cell (7, 7) is already occupied');
    // Game should still be PLAYING
    expect(result.current.gameState.status).toBe('PLAYING');
  });

  it('should handle draw scenario', async () => {
    mockCaroGame.startHosting.mockResolvedValue('game789');
    mockCaroGame.startMatch.mockResolvedValue(undefined);

    const {result} = renderHook(() => useCaroGame());

    await act(async () => {
      await result.current.startHosting('Host');
    });
    await act(async () => {
      await result.current.startMatch();
    });

    // Simulate the 225th move ending in draw
    mockCaroGame.placeMove.mockResolvedValueOnce(
      JSON.stringify({
        success: true,
        isWin: false,
        isDraw: true,
      }),
    );
    mockCaroGame.getBoard.mockResolvedValueOnce('[]');

    await act(async () => {
      await result.current.placeMove(14, 14);
    });

    expect(result.current.gameState.status).toBe('FINISHED');
    expect(result.current.gameState.winner).toBe('DRAW');
  });

  it('should reset cleanly after stopGame', async () => {
    mockCaroGame.startHosting.mockResolvedValue('game101');
    mockCaroGame.startMatch.mockResolvedValue(undefined);

    const {result} = renderHook(() => useCaroGame());

    await act(async () => {
      await result.current.startHosting('Host');
    });
    await act(async () => {
      await result.current.startMatch();
    });

    // Place a move
    mockCaroGame.placeMove.mockResolvedValueOnce(
      JSON.stringify({success: true, isWin: false, isDraw: false}),
    );
    await act(async () => {
      await result.current.placeMove(7, 7);
    });

    // Stop game — verify full reset
    act(() => {
      result.current.stopGame();
    });

    expect(result.current.gameState.status).toBe('WAITING');
    expect(result.current.gameState.myRole).toBe('');
    expect(result.current.gameState.mySymbol).toBe('');
    expect(result.current.gameState.gameId).toBe('');
    expect(result.current.gameState.connectedPlayers).toBe(0);
    expect(result.current.moves).toEqual([]);
    expect(result.current.isMyTurn).toBe(false);
  });

  it('should sync game state from native on mount', async () => {
    // Simulate navigating to GameScreen — native module has active game
    mockCaroGame.getGameState.mockResolvedValue(
      JSON.stringify({
        gameId: 'active-game',
        status: 'PLAYING',
        myRole: 'host',
        mySymbol: 'X',
        currentTurn: 'X',
        connectedPlayers: 1,
      }),
    );
    mockCaroGame.getBoard.mockResolvedValue(
      JSON.stringify([
        {id: 1, x: 7, y: 7, playerSymbol: 'X', moveNumber: 1, timestamp: 0},
      ]),
    );

    const {result} = renderHook(() => useCaroGame());

    // Wait for mount sync effect
    await act(async () => {
      await new Promise(resolve => setTimeout(resolve, 100));
    });

    expect(result.current.gameState.status).toBe('PLAYING');
    expect(result.current.gameState.myRole).toBe('host');
    expect(result.current.gameState.mySymbol).toBe('X');
    expect(result.current.gameState.gameId).toBe('active-game');
    expect(result.current.isMyTurn).toBe(true);
    expect(result.current.board[7][7]).toBe('X');
  });
});
