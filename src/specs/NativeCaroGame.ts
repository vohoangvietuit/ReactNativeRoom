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
  hostDeviceId?: string;
  challengerDeviceId?: string;
  challengerDeviceName?: string;
  challengerReady?: boolean;
};

export interface Spec extends TurboModule {
  // Game actions
  placeMove(x: number, y: number): Promise<string>; // JSON MoveResultData
  getBoard(): Promise<string>; // JSON CaroMoveData[]
  getGameState(): Promise<string>; // JSON GameStateData

  // Hosting / Joining
  startHosting(playerName: string, passKey: string): Promise<string>; // returns gameId
  joinGame(playerName: string): Promise<void>;
  stopGame(): void;

  // Lobby controls
  startMatch(): Promise<void>; // Host only — transitions WAITING → PLAYING
  setReady(): Promise<void>; // Challenger only — sends PLAYER_READY to host
  cancelGame(): Promise<void>; // Either player — broadcasts GAME_CANCEL + clears DB
  submitPassKey(key: string): Promise<void>; // Challenger — sends AUTH hash to host
  initialize(): Promise<void>; // Boot-time stale DB wipe
  reconnect(): Promise<void>; // Manually retry connecting to last known host

  // Event emitter
  addListener(eventName: string): void;
  removeListeners(count: number): void;
}

export default TurboModuleRegistry.getEnforcing<Spec>('CaroGame');
