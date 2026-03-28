/**
 * Tests for GameHUD component — turn display, role indicators, connection status.
 */
import React from 'react';
import {render} from '@testing-library/react-native';
import {GameHUD} from '../../src/components/game/GameHUD';

describe('GameHUD', () => {
  it('should display player symbol', () => {
    const {getByText} = render(
      <GameHUD
        mySymbol="X"
        currentTurn="X"
        myRole="host"
        moveCount={0}
        connectedPlayers={1}
        status="PLAYING"
      />,
    );

    expect(getByText('X')).toBeTruthy();
  });

  it('should show "Your Turn" when it is the player\'s turn', () => {
    const {getByText} = render(
      <GameHUD
        mySymbol="X"
        currentTurn="X"
        myRole="host"
        moveCount={0}
        connectedPlayers={1}
        status="PLAYING"
      />,
    );

    expect(getByText('Your Turn')).toBeTruthy();
  });

  it('should show opponent turn when not your turn', () => {
    const {getByText} = render(
      <GameHUD
        mySymbol="O"
        currentTurn="X"
        myRole="challenger"
        moveCount={0}
        connectedPlayers={1}
        status="PLAYING"
      />,
    );

    expect(getByText("X's Turn")).toBeTruthy();
  });

  it('should show status text when not PLAYING', () => {
    const {getByText} = render(
      <GameHUD
        mySymbol="X"
        currentTurn="X"
        myRole="host"
        moveCount={0}
        connectedPlayers={0}
        status="WAITING"
      />,
    );

    expect(getByText('WAITING')).toBeTruthy();
  });

  it('should show role badge', () => {
    const {getByText} = render(
      <GameHUD
        mySymbol="X"
        currentTurn="X"
        myRole="host"
        moveCount={5}
        connectedPlayers={1}
        status="PLAYING"
      />,
    );

    expect(getByText('host')).toBeTruthy();
  });

  it('should show move count', () => {
    const {getByText} = render(
      <GameHUD
        mySymbol="X"
        currentTurn="X"
        myRole="host"
        moveCount={10}
        connectedPlayers={1}
        status="PLAYING"
      />,
    );

    expect(getByText('10')).toBeTruthy();
    expect(getByText('Moves')).toBeTruthy();
  });

  it('should show Online when connected', () => {
    const {getByText} = render(
      <GameHUD
        mySymbol="X"
        currentTurn="X"
        myRole="host"
        moveCount={0}
        connectedPlayers={1}
        status="PLAYING"
      />,
    );

    expect(getByText('Online')).toBeTruthy();
  });

  it('should show Offline when disconnected', () => {
    const {getByText} = render(
      <GameHUD
        mySymbol="X"
        currentTurn="X"
        myRole="host"
        moveCount={0}
        connectedPlayers={0}
        status="PLAYING"
      />,
    );

    expect(getByText('Offline')).toBeTruthy();
  });

  it('should show ? when no symbol assigned', () => {
    const {getByText} = render(
      <GameHUD
        mySymbol=""
        currentTurn="X"
        myRole=""
        moveCount={0}
        connectedPlayers={0}
        status="WAITING"
      />,
    );

    expect(getByText('?')).toBeTruthy();
  });
});
