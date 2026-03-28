/**
 * Tests for GameOverModal component — victory, defeat, draw display.
 */
import React from 'react';
import {render, fireEvent} from '@testing-library/react-native';
import {GameOverModal} from '../../src/components/game/GameOverModal';

describe('GameOverModal', () => {
  const mockPlayAgain = jest.fn();
  const mockBackToMenu = jest.fn();

  beforeEach(() => {
    mockPlayAgain.mockClear();
    mockBackToMenu.mockClear();
  });

  it('should show VICTORY when player wins', () => {
    const {getByText} = render(
      <GameOverModal
        visible={true}
        winner="X"
        mySymbol="X"
        onPlayAgain={mockPlayAgain}
        onBackToMenu={mockBackToMenu}
      />,
    );

    expect(getByText('VICTORY!')).toBeTruthy();
    expect(getByText('Congratulations! You won the game!')).toBeTruthy();
  });

  it('should show DEFEAT when opponent wins', () => {
    const {getByText} = render(
      <GameOverModal
        visible={true}
        winner="X"
        mySymbol="O"
        onPlayAgain={mockPlayAgain}
        onBackToMenu={mockBackToMenu}
      />,
    );

    expect(getByText('DEFEAT')).toBeTruthy();
    expect(getByText('Player X wins the game.')).toBeTruthy();
  });

  it('should show DRAW when game is drawn', () => {
    const {getByText} = render(
      <GameOverModal
        visible={true}
        winner="DRAW"
        mySymbol="X"
        onPlayAgain={mockPlayAgain}
        onBackToMenu={mockBackToMenu}
      />,
    );

    expect(getByText('DRAW!')).toBeTruthy();
    expect(getByText('The board is full. Well played!')).toBeTruthy();
  });

  it('should display winner symbol badge when not draw', () => {
    const {getByText} = render(
      <GameOverModal
        visible={true}
        winner="O"
        mySymbol="X"
        onPlayAgain={mockPlayAgain}
        onBackToMenu={mockBackToMenu}
      />,
    );

    expect(getByText('WINS')).toBeTruthy();
  });

  it('should call onPlayAgain when Play Again is pressed', () => {
    const {getByText} = render(
      <GameOverModal
        visible={true}
        winner="X"
        mySymbol="X"
        onPlayAgain={mockPlayAgain}
        onBackToMenu={mockBackToMenu}
      />,
    );

    fireEvent.press(getByText('Play Again'));
    expect(mockPlayAgain).toHaveBeenCalledTimes(1);
  });

  it('should call onBackToMenu when Menu is pressed', () => {
    const {getByText} = render(
      <GameOverModal
        visible={true}
        winner="X"
        mySymbol="X"
        onPlayAgain={mockPlayAgain}
        onBackToMenu={mockBackToMenu}
      />,
    );

    fireEvent.press(getByText('Menu'));
    expect(mockBackToMenu).toHaveBeenCalledTimes(1);
  });

  it('should show Play Again and Menu buttons', () => {
    const {getByText} = render(
      <GameOverModal
        visible={true}
        winner="X"
        mySymbol="X"
        onPlayAgain={mockPlayAgain}
        onBackToMenu={mockBackToMenu}
      />,
    );

    expect(getByText('Play Again')).toBeTruthy();
    expect(getByText('Menu')).toBeTruthy();
  });
});
