/**
 * Mock for NativeModules.CaroGame used by useCaroGame hook and tests.
 *
 * Usage: import this mock at the top of test files, then use
 * `mockCaroGame.<method>.mockResolvedValue(...)` to control responses.
 */

const mockCaroGame = {
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

export default mockCaroGame;
