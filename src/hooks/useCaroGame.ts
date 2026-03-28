import {useState, useEffect, useCallback, useMemo} from 'react';
import {NativeEventEmitter, NativeModules} from 'react-native';
import type {
  CaroMoveData,
  MoveResultData,
  GameStateData,
} from '../specs/NativeCaroGame';

const CaroGame = NativeModules.CaroGame;
const eventEmitter = CaroGame ? new NativeEventEmitter(CaroGame) : null;

const BOARD_SIZE = 15;

type CellValue = '' | 'X' | 'O';

export function useCaroGame() {
  const [moves, setMoves] = useState<CaroMoveData[]>([]);
  const [gameState, setGameState] = useState<GameStateData>({
    gameId: '',
    status: 'WAITING',
    myRole: '',
    mySymbol: '',
    currentTurn: 'X',
    connectedPlayers: 0,
  });
  const [lastMoveResult, setLastMoveResult] = useState<MoveResultData | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Derive 2D board from moves list
  const board = useMemo(() => {
    const grid: CellValue[][] = Array.from({length: BOARD_SIZE}, () =>
      Array(BOARD_SIZE).fill(''),
    );
    for (const move of moves) {
      if (move.x >= 0 && move.x < BOARD_SIZE && move.y >= 0 && move.y < BOARD_SIZE) {
        grid[move.y][move.x] = move.playerSymbol as CellValue;
      }
    }
    return grid;
  }, [moves]);

  // Last move position for highlighting
  const lastMove = useMemo(() => {
    if (moves.length === 0) return null;
    const last = moves[moves.length - 1];
    return {x: last.x, y: last.y};
  }, [moves]);

  // Winning cells for highlighting
  const winningCells = useMemo(() => {
    if (!lastMoveResult?.isWin || !lastMoveResult.winningCells) return [];
    try {
      return JSON.parse(lastMoveResult.winningCells) as [number, number][];
    } catch {
      return [];
    }
  }, [lastMoveResult]);

  const isMyTurn = useMemo(() => {
    if (gameState.myRole === 'spectator') return false;
    return gameState.currentTurn === gameState.mySymbol;
  }, [gameState]);

  // Subscribe to native events
  useEffect(() => {
    if (!eventEmitter) return;

    const boardSub = eventEmitter.addListener('onBoardUpdate', (data: string) => {
      try {
        const parsed = JSON.parse(data);
        if (Array.isArray(parsed)) {
          setMoves(parsed);
          // Update turn
          const turn = parsed.length % 2 === 0 ? 'X' : 'O';
          setGameState(prev => ({...prev, currentTurn: turn}));
        } else {
          // Single move update — append
          setMoves(prev => {
            const updated = [...prev, parsed];
            const turn = updated.length % 2 === 0 ? 'X' : 'O';
            setGameState(gs => ({...gs, currentTurn: turn}));
            return updated;
          });
        }
      } catch {}
    });

    const stateSub = eventEmitter.addListener('onGameStateChange', (data: string) => {
      try {
        const parsed = JSON.parse(data);
        setGameState(prev => ({...prev, ...parsed}));
      } catch {}
    });

    const playerConnSub = eventEmitter.addListener('onPlayerConnected', (_role: string) => {
      setGameState(prev => ({
        ...prev,
        connectedPlayers: prev.connectedPlayers + 1,
      }));
    });

    const playerDiscSub = eventEmitter.addListener('onPlayerDisconnected', () => {
      setGameState(prev => ({
        ...prev,
        connectedPlayers: Math.max(0, prev.connectedPlayers - 1),
      }));
    });

    const gameOverSub = eventEmitter.addListener('onGameOver', (_data: string) => {
      setGameState(prev => ({...prev, status: 'FINISHED'}));
    });

    // Register listener with native side
    CaroGame?.addListener('onBoardUpdate');

    return () => {
      boardSub.remove();
      stateSub.remove();
      playerConnSub.remove();
      playerDiscSub.remove();
      gameOverSub.remove();
    };
  }, []);

  // Fetch initial board
  const refreshBoard = useCallback(async () => {
    if (!CaroGame) return;
    try {
      const json = await CaroGame.getBoard();
      const parsed: CaroMoveData[] = JSON.parse(json);
      setMoves(parsed);
    } catch {}
  }, []);

  const refreshGameState = useCallback(async () => {
    if (!CaroGame) return;
    try {
      const json = await CaroGame.getGameState();
      const parsed: GameStateData = JSON.parse(json);
      setGameState(prev => ({...prev, ...parsed}));
    } catch {}
  }, []);

  const placeMove = useCallback(
    async (x: number, y: number) => {
      if (!CaroGame) return;
      try {
        const json = await CaroGame.placeMove(x, y);
        const result: MoveResultData = JSON.parse(json);
        setLastMoveResult(result);

        if (result.success) {
          await refreshBoard();
          if (result.isWin || result.isDraw) {
            setGameState(prev => ({
              ...prev,
              status: 'FINISHED',
              winner: result.winner,
            }));
          }
        }
        return result;
      } catch {
        return null;
      }
    },
    [refreshBoard],
  );

  const startHosting = useCallback(async (playerName: string) => {
    if (!CaroGame) {
      const msg = 'Game module not available';
      setError(msg);
      throw new Error(msg);
    }
    setLoading(true);
    setError(null);
    try {
      const gameId = await CaroGame.startHosting(playerName);
      setGameState(prev => ({
        ...prev,
        gameId,
        myRole: 'host',
        mySymbol: 'X',
        status: 'WAITING',
      }));
      return gameId;
    } catch (e: any) {
      const msg = e?.message || 'Failed to start hosting';
      setError(msg);
      throw e;
    } finally {
      setLoading(false);
    }
  }, []);

  const joinGame = useCallback(async (playerName: string) => {
    if (!CaroGame) {
      const msg = 'Game module not available';
      setError(msg);
      throw new Error(msg);
    }
    setLoading(true);
    setError(null);
    try {
      await CaroGame.joinGame(playerName);
      setGameState(prev => ({
        ...prev,
        myRole: 'challenger',
        mySymbol: 'O',
        status: 'WAITING',
      }));
    } catch (e: any) {
      const msg = e?.message || 'Failed to join game';
      setError(msg);
      throw e;
    } finally {
      setLoading(false);
    }
  }, []);

  const startMatch = useCallback(async () => {
    if (!CaroGame) return;
    try {
      await CaroGame.startMatch();
      setGameState(prev => ({...prev, status: 'PLAYING'}));
    } catch {}
  }, []);

  const stopGame = useCallback(() => {
    CaroGame?.stopGame();
    setMoves([]);
    setGameState({
      gameId: '',
      status: 'WAITING',
      myRole: '',
      mySymbol: '',
      currentTurn: 'X',
      connectedPlayers: 0,
    });
    setLastMoveResult(null);
  }, []);

  return {
    // State
    board,
    moves,
    gameState,
    lastMove,
    lastMoveResult,
    winningCells,
    isMyTurn,
    loading,
    error,

    // Actions
    placeMove,
    startHosting,
    joinGame,
    startMatch,
    stopGame,
    refreshBoard,
    refreshGameState,
  };
}
