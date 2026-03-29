import React, { useCallback, useEffect } from 'react';
import { View, StyleSheet, Alert } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useCaroGame } from '../hooks/useCaroGame';
import { GameBoard } from '../components/game/GameBoard';
import { GameHUD } from '../components/game/GameHUD';
import { GameOverModal } from '../components/game/GameOverModal';
import { ReconnectingOverlay } from '../components/game/ReconnectingOverlay';
import { colors, spacing } from '../theme';

interface GameScreenProps {
  navigation: any;
}

export const GameScreen: React.FC<GameScreenProps> = ({ navigation }) => {
  const insets = useSafeAreaInsets();
  const {
    board,
    moves,
    gameState,
    lastMove,
    winningCells,
    isMyTurn,
    opponentLeft,
    isReconnecting,
    gameCancelled,
    placeMove,
    stopGame,
    cancelGame,
    reconnect,
  } = useCaroGame();

  const isFinished = gameState.status === 'FINISHED';
  // Allow moves even while reconnecting — moves are queued locally and flushed on reconnect.
  const disabled = !isMyTurn || isFinished;

  // Show alert when opponent explicitly disconnects mid-game
  useEffect(() => {
    if (opponentLeft) {
      Alert.alert(
        'Opponent Left',
        'Your opponent has disconnected. The game has ended.',
        [
          {
            text: 'Back to Menu',
            onPress: () => {
              stopGame();
              navigation.popToTop();
            },
          },
        ],
        { cancelable: false },
      );
    }
  }, [opponentLeft, stopGame, navigation]);

  // Navigate back when either player cancels the game
  useEffect(() => {
    if (gameCancelled) {
      navigation.popToTop();
    }
  }, [gameCancelled, navigation]);

  const handleForfeit = useCallback(() => {
    Alert.alert(
      'Forfeit Game',
      'Are you sure you want to forfeit? This will end the game for both players.',
      [
        { text: 'Stay' },
        {
          text: 'Forfeit',
          style: 'destructive',
          onPress: async () => {
            await cancelGame();
            navigation.popToTop();
          },
        },
      ],
    );
  }, [cancelGame, navigation]);

  const handleCancelReconnect = useCallback(async () => {
    await cancelGame();
    navigation.popToTop();
  }, [cancelGame, navigation]);

  const handleReconnect = useCallback(async () => {
    await reconnect();
  }, [reconnect]);

  const handleCellPress = useCallback(
    async (x: number, y: number) => {
      if (disabled) return;
      const result = await placeMove(x, y);
      if (result && !result.success && result.error) {
        Alert.alert('Invalid Move', result.error);
      }
    },
    [disabled, placeMove],
  );

  const handlePlayAgain = useCallback(() => {
    // In a real flow, host would reset and re-broadcast
    stopGame();
    navigation.replace('Lobby', {
      role: gameState.myRole === 'host' ? 'host' : 'join',
    });
  }, [stopGame, navigation, gameState.myRole]);

  const handleBackToMenu = useCallback(() => {
    stopGame();
    navigation.popToTop();
  }, [stopGame, navigation]);

  return (
    <View style={[styles.container, { paddingTop: insets.top }]}>
      {/* HUD */}
      <GameHUD
        mySymbol={gameState.mySymbol}
        currentTurn={gameState.currentTurn}
        myRole={gameState.myRole}
        moveCount={moves.length}
        connectedPlayers={gameState.connectedPlayers}
        status={gameState.status}
        onForfeit={handleForfeit}
      />

      {/* Non-blocking reconnect banner — sits above the board, doesn't block taps */}
      <ReconnectingOverlay
        visible={isReconnecting}
        onReconnect={handleReconnect}
        onCancel={handleCancelReconnect}
      />

      {/* Board */}
      <View style={styles.boardContainer}>
        <GameBoard
          board={board}
          onCellPress={handleCellPress}
          disabled={disabled}
          lastMove={lastMove}
          winningCells={winningCells}
        />
      </View>

      {/* Game Over Modal */}
      <GameOverModal
        visible={isFinished}
        winner={gameState.winner}
        mySymbol={gameState.mySymbol}
        onPlayAgain={handlePlayAgain}
        onBackToMenu={handleBackToMenu}
      />
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: colors.background,
  },
  boardContainer: {
    flex: 1,
    justifyContent: 'center',
    paddingHorizontal: spacing.sm,
  },
});
