/**
 * Tests for the useCaroGame hook — board derivation, turn logic, game state transitions.
 */

// Define mock INLINE in the factory to avoid TDZ issues
// (jest.mock is hoisted above imports by Jest, but const declarations are not)
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

// Get a reference to the mock after imports (now safely initialized)
const mockCaroGame = NativeModules.CaroGame as any;

const defaultGameState = JSON.stringify({
  gameId: '',
  status: 'WAITING',
  myRole: '',
  mySymbol: '',
  currentTurn: 'X',
  connectedPlayers: 0,
});

beforeEach(() => {
  jest.clearAllMocks();
  mockCaroGame.getBoard.mockResolvedValue('[]');
  mockCaroGame.getGameState.mockResolvedValue(defaultGameState);
});

describe('useCaroGame', () => {
  describe('initial state', () => {
    it('should start with empty board and WAITING status', () => {
      const {result} = renderHook(() => useCaroGame());

      expect(result.current.moves).toEqual([]);
      expect(result.current.gameState.status).toBe('WAITING');
      expect(result.current.gameState.currentTurn).toBe('X');
      expect(result.current.board.length).toBe(15);
      expect(result.current.board[0].length).toBe(15);
    });

    it('should derive empty 15x15 board from no moves', () => {
      const {result} = renderHook(() => useCaroGame());

      for (let y = 0; y < 15; y++) {
        for (let x = 0; x < 15; x++) {
          expect(result.current.board[y][x]).toBe('');
        }
      }
    });
  });

  describe('startHosting', () => {
    it('should set host role and X symbol', async () => {
      mockCaroGame.startHosting.mockResolvedValue('abc123');

      const {result} = renderHook(() => useCaroGame());

      await act(async () => {
        await result.current.startHosting('Player1');
      });

      expect(result.current.gameState.myRole).toBe('host');
      expect(result.current.gameState.mySymbol).toBe('X');
      expect(result.current.gameState.gameId).toBe('abc123');
      expect(result.current.gameState.status).toBe('WAITING');
    });

    it('should set error when module unavailable', async () => {
      mockCaroGame.startHosting.mockRejectedValue(new Error('BLE unavailable'));

      const {result} = renderHook(() => useCaroGame());

      // Catch inside act so React properly flushes state updates
      let thrownError: Error | null = null;
      await act(async () => {
        try {
          await result.current.startHosting('Player1');
        } catch (e) {
          thrownError = e as Error;
        }
      });

      expect(thrownError?.message).toBe('BLE unavailable');
      expect(result.current.error).toBe('BLE unavailable');
    });
  });

  describe('joinGame', () => {
    it('should set challenger role and O symbol', async () => {
      mockCaroGame.joinGame.mockResolvedValue(undefined);

      const {result} = renderHook(() => useCaroGame());

      await act(async () => {
        await result.current.joinGame('Player2');
      });

      expect(result.current.gameState.myRole).toBe('challenger');
      expect(result.current.gameState.mySymbol).toBe('O');
    });
  });

  describe('startMatch', () => {
    it('should transition to PLAYING status', async () => {
      mockCaroGame.startMatch.mockResolvedValue(undefined);
      mockCaroGame.startHosting.mockResolvedValue('game1');

      const {result} = renderHook(() => useCaroGame());

      await act(async () => {
        await result.current.startHosting('Host');
      });

      await act(async () => {
        await result.current.startMatch();
      });

      expect(result.current.gameState.status).toBe('PLAYING');
    });
  });

  describe('placeMove', () => {
    it('should handle successful host move with proper booleans', async () => {
      mockCaroGame.startHosting.mockResolvedValue('game1');
      mockCaroGame.placeMove.mockResolvedValue(
        JSON.stringify({
          success: true,
          isWin: false,
          isDraw: false,
        }),
      );
      mockCaroGame.getBoard.mockResolvedValue(
        JSON.stringify([
          {id: 1, x: 7, y: 7, playerSymbol: 'X', moveNumber: 1, timestamp: 0},
        ]),
      );

      const {result} = renderHook(() => useCaroGame());

      await act(async () => {
        await result.current.startHosting('Host');
      });

      const moveResult = await act(async () => {
        return await result.current.placeMove(7, 7);
      });

      expect(moveResult).toBeTruthy();
      expect(moveResult!.success).toBe(true);
      expect(moveResult!.isWin).toBe(false);
      expect(moveResult!.isDraw).toBe(false);
      // Game should still be playing (not FINISHED) because isWin is false
      expect(result.current.gameState.status).not.toBe('FINISHED');
    });

    it('should correctly parse string booleans as safety net', async () => {
      // Simulate old native module sending string booleans
      mockCaroGame.placeMove.mockResolvedValue(
        JSON.stringify({
          success: 'true',
          isWin: 'false',
          isDraw: 'false',
        }),
      );

      const {result} = renderHook(() => useCaroGame());

      const moveResult = await act(async () => {
        return await result.current.placeMove(0, 0);
      });

      expect(moveResult).toBeTruthy();
      expect(moveResult!.success).toBe(true);
      expect(moveResult!.isWin).toBe(false);
      expect(moveResult!.isDraw).toBe(false);
    });

    it('should set FINISHED on win', async () => {
      mockCaroGame.startHosting.mockResolvedValue('game1');
      mockCaroGame.placeMove.mockResolvedValue(
        JSON.stringify({
          success: true,
          isWin: true,
          isDraw: false,
          winner: 'X',
          winningCells: '[[0,0],[1,0],[2,0],[3,0],[4,0]]',
        }),
      );
      mockCaroGame.getBoard.mockResolvedValue('[]');

      const {result} = renderHook(() => useCaroGame());

      await act(async () => {
        await result.current.startHosting('Host');
      });
      await act(async () => {
        await result.current.startMatch();
      });

      await act(async () => {
        await result.current.placeMove(4, 0);
      });

      expect(result.current.gameState.status).toBe('FINISHED');
      expect(result.current.gameState.winner).toBe('X');
    });

    it('should set FINISHED on draw', async () => {
      mockCaroGame.placeMove.mockResolvedValue(
        JSON.stringify({
          success: true,
          isWin: false,
          isDraw: true,
        }),
      );
      mockCaroGame.getBoard.mockResolvedValue('[]');

      const {result} = renderHook(() => useCaroGame());

      await act(async () => {
        await result.current.placeMove(0, 0);
      });

      expect(result.current.gameState.status).toBe('FINISHED');
      expect(result.current.gameState.winner).toBe('DRAW');
    });

    it('should return error result for invalid move', async () => {
      mockCaroGame.placeMove.mockResolvedValue(
        JSON.stringify({
          success: false,
          isWin: false,
          isDraw: false,
          error: 'Cell already occupied',
        }),
      );

      const {result} = renderHook(() => useCaroGame());

      const moveResult = await act(async () => {
        return await result.current.placeMove(0, 0);
      });

      expect(moveResult!.success).toBe(false);
      expect(moveResult!.error).toBe('Cell already occupied');
    });
  });

  describe('isMyTurn', () => {
    it('should be false when status is WAITING', () => {
      const {result} = renderHook(() => useCaroGame());
      expect(result.current.isMyTurn).toBe(false);
    });

    it('should be true for host (X) when currentTurn is X and status is PLAYING', async () => {
      mockCaroGame.startHosting.mockResolvedValue('game1');
      mockCaroGame.startMatch.mockResolvedValue(undefined);

      const {result} = renderHook(() => useCaroGame());

      await act(async () => {
        await result.current.startHosting('Host');
      });
      await act(async () => {
        await result.current.startMatch();
      });

      expect(result.current.gameState.mySymbol).toBe('X');
      expect(result.current.gameState.currentTurn).toBe('X');
      expect(result.current.gameState.status).toBe('PLAYING');
      expect(result.current.isMyTurn).toBe(true);
    });

    it('should be false for challenger (O) when currentTurn is X', async () => {
      mockCaroGame.joinGame.mockResolvedValue(undefined);

      const {result} = renderHook(() => useCaroGame());

      await act(async () => {
        await result.current.joinGame('Challenger');
      });

      // currentTurn defaults to 'X', mySymbol is 'O'
      expect(result.current.isMyTurn).toBe(false);
    });
  });

  describe('stopGame', () => {
    it('should reset all state', async () => {
      mockCaroGame.startHosting.mockResolvedValue('game1');

      const {result} = renderHook(() => useCaroGame());

      await act(async () => {
        await result.current.startHosting('Host');
      });

      act(() => {
        result.current.stopGame();
      });

      expect(result.current.gameState.myRole).toBe('');
      expect(result.current.gameState.mySymbol).toBe('');
      expect(result.current.gameState.gameId).toBe('');
      expect(result.current.gameState.status).toBe('WAITING');
      expect(result.current.moves).toEqual([]);
    });
  });

  describe('board derivation', () => {
    it('should correctly place moves on the 2D grid', async () => {
      // Sync board from native with 2 moves
      mockCaroGame.getGameState.mockResolvedValue(
        JSON.stringify({
          gameId: 'test',
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
          {id: 2, x: 8, y: 7, playerSymbol: 'O', moveNumber: 2, timestamp: 1},
        ]),
      );

      const {result} = renderHook(() => useCaroGame());

      // Wait for the effect that syncs board on mount
      await act(async () => {
        await new Promise(resolve => setTimeout(resolve, 100));
      });

      expect(result.current.board[7][7]).toBe('X');
      expect(result.current.board[7][8]).toBe('O');
      expect(result.current.board[0][0]).toBe('');
    });
  });

  describe('winningCells', () => {
    it('should parse winning cells from lastMoveResult', async () => {
      mockCaroGame.placeMove.mockResolvedValue(
        JSON.stringify({
          success: true,
          isWin: true,
          isDraw: false,
          winner: 'X',
          winningCells: '[[0,0],[1,0],[2,0],[3,0],[4,0]]',
        }),
      );
      mockCaroGame.getBoard.mockResolvedValue('[]');

      const {result} = renderHook(() => useCaroGame());

      await act(async () => {
        await result.current.placeMove(4, 0);
      });

      expect(result.current.winningCells).toEqual([
        [0, 0],
        [1, 0],
        [2, 0],
        [3, 0],
        [4, 0],
      ]);
    });

    it('should return empty array when no win', () => {
      const {result} = renderHook(() => useCaroGame());
      expect(result.current.winningCells).toEqual([]);
    });
  });
});
