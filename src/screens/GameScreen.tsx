import React, {useCallback, useEffect, useRef} from 'react';
import {View, StyleSheet, Alert} from 'react-native';
import {useSafeAreaInsets} from 'react-native-safe-area-context';
import {useCaroGame} from '../hooks/useCaroGame';
import {GameBoard} from '../components/game/GameBoard';
import {GameHUD} from '../components/game/GameHUD';
import {GameOverModal} from '../components/game/GameOverModal';
import {colors, spacing} from '../theme';

interface GameScreenProps {
  navigation: any;
}

export const GameScreen: React.FC<GameScreenProps> = ({navigation}) => {
  const insets = useSafeAreaInsets();
  const {
    board,
    moves,
    gameState,
    lastMove,
    winningCells,
    isMyTurn,
    isConnected,
    placeMove,
    stopGame,
  } = useCaroGame();

  const isFinished = gameState.status === 'FINISHED';
  const disabled = !isMyTurn || isFinished;

  // Track previous connection state to detect disconnect during gameplay
  const wasConnected = useRef(false);
  useEffect(() => {
    if (isConnected) {
      wasConnected.current = true;
    } else if (wasConnected.current && !isConnected && gameState.status === 'PLAYING') {
      Alert.alert(
        'Connection Lost',
        'The other player has disconnected.',
        [
          {
            text: 'Back to Menu',
            onPress: () => {
              stopGame();
              navigation.popToTop();
            },
          },
          {text: 'Wait', style: 'cancel'},
        ],
      );
    }
  }, [isConnected, gameState.status, stopGame, navigation]);

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
    navigation.replace('Lobby', {role: gameState.myRole === 'host' ? 'host' : 'join'});
  }, [stopGame, navigation, gameState.myRole]);

  const handleBackToMenu = useCallback(() => {
    stopGame();
    navigation.popToTop();
  }, [stopGame, navigation]);

  return (
    <View style={[styles.container, {paddingTop: insets.top}]}>
      {/* HUD */}
      <GameHUD
        mySymbol={gameState.mySymbol}
        currentTurn={gameState.currentTurn}
        myRole={gameState.myRole}
        moveCount={moves.length}
        connectedPlayers={gameState.connectedPlayers}
        status={gameState.status}
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
