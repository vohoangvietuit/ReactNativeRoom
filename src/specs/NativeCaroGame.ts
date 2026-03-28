import type {TurboModule} from 'react-native';
import {TurboModuleRegistry} from 'react-native';

export type CaroMoveData = {
  id: number;
  x: number;
  y: number;
  playerSymbol: string; // "X" or "O"
  moveNumber: number;
  timestamp: number;
};

export type MoveResultData = {
  success: boolean;
  error?: string;
  isWin: boolean;
  winner?: string;         // "X" | "O" | null
  winningCells?: string;   // JSON string of [[x,y], ...] — serialized for TurboModule compat
  isDraw: boolean;
};

export type GameStateData = {
  gameId: string;
  status: string; // "WAITING" | "PLAYING" | "FINISHED"
  myRole: string; // "host" | "challenger"
  mySymbol: string; // "X" | "O" | ""
  currentTurn: string; // "X" | "O"
  connectedPlayers: number;
  winner?: string;
};

export interface Spec extends TurboModule {
  // Game actions
  placeMove(x: number, y: number): Promise<string>; // JSON MoveResultData
  getBoard(): Promise<string>;                       // JSON CaroMoveData[]
  getGameState(): Promise<string>;                   // JSON GameStateData

  // Hosting / Joining
  startHosting(playerName: string): Promise<string>; // returns gameId
  joinGame(playerName: string): Promise<void>;
  stopGame(): void;

  // Lobby controls
  startMatch(): Promise<void>;  // Host only — transitions WAITING → PLAYING

  // Event emitter
  addListener(eventName: string): void;
  removeListeners(count: number): void;
}

export default TurboModuleRegistry.getEnforcing<Spec>('CaroGame');
