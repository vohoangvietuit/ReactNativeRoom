import React, {useEffect, useRef} from 'react';
import {
  View,
  Text,
  StyleSheet,
  Modal,
  Animated,
  Dimensions,
} from 'react-native';
import {Button} from '../ui/Button';
import {colors, spacing, fontSize, borderRadius} from '../../theme';

const {width: SCREEN_WIDTH} = Dimensions.get('window');

interface GameOverModalProps {
  visible: boolean;
  winner?: string | null; // "X" | "O" | "DRAW"
  mySymbol: string;
  onPlayAgain: () => void;
  onBackToMenu: () => void;
}

export const GameOverModal: React.FC<GameOverModalProps> = ({
  visible,
  winner,
  mySymbol,
  onPlayAgain,
  onBackToMenu,
}) => {
  const scaleAnim = useRef(new Animated.Value(0)).current;
  const fadeAnim = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    if (visible) {
      Animated.parallel([
        Animated.spring(scaleAnim, {
          toValue: 1,
          friction: 6,
          tension: 40,
          useNativeDriver: true,
        }),
        Animated.timing(fadeAnim, {
          toValue: 1,
          duration: 300,
          useNativeDriver: true,
        }),
      ]).start();
    } else {
      scaleAnim.setValue(0);
      fadeAnim.setValue(0);
    }
  }, [visible, scaleAnim, fadeAnim]);

  const isDraw = winner === 'DRAW';
  const isWinner = winner === mySymbol;

  const title = isDraw ? 'DRAW!' : isWinner ? 'VICTORY!' : 'DEFEAT';
  const subtitle = isDraw
    ? 'The board is full. Well played!'
    : isWinner
      ? 'Congratulations! You won the game!'
      : `Player ${winner} wins the game.`;

  const titleColor = isDraw
    ? colors.warning
    : isWinner
      ? colors.success
      : colors.error;

  return (
    <Modal transparent visible={visible} animationType="none">
      <Animated.View style={[styles.overlay, {opacity: fadeAnim}]}>
        <Animated.View
          style={[
            styles.modalContent,
            {transform: [{scale: scaleAnim}]},
          ]}>
          {/* Trophy / icon area */}
          <View style={styles.iconContainer}>
            <Text style={styles.icon}>
              {isDraw ? '🤝' : isWinner ? '🏆' : '😔'}
            </Text>
          </View>

          <Text style={[styles.title, {color: titleColor}]}>{title}</Text>

          {winner && !isDraw && (
            <View style={styles.winnerBadge}>
              <Text
                style={[
                  styles.winnerSymbol,
                  winner === 'X' ? styles.symbolX : styles.symbolO,
                ]}>
                {winner}
              </Text>
              <Text style={styles.winnerLabel}>WINS</Text>
            </View>
          )}

          <Text style={styles.subtitle}>{subtitle}</Text>

          <View style={styles.buttonRow}>
            <Button
              title="Play Again"
              onPress={onPlayAgain}
              variant="primary"
              size="lg"
              style={styles.button}
            />
            <Button
              title="Menu"
              onPress={onBackToMenu}
              variant="outline"
              size="lg"
              style={styles.button}
            />
          </View>
        </Animated.View>
      </Animated.View>
    </Modal>
  );
};

const styles = StyleSheet.create({
  overlay: {
    flex: 1,
    backgroundColor: 'rgba(0, 0, 0, 0.75)',
    justifyContent: 'center',
    alignItems: 'center',
    padding: spacing.lg,
  },
  modalContent: {
    width: SCREEN_WIDTH - spacing.xl * 2,
    backgroundColor: colors.surface,
    borderRadius: borderRadius.xl,
    padding: spacing.xl,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: colors.border,
  },
  iconContainer: {
    marginBottom: spacing.md,
  },
  icon: {
    fontSize: 64,
  },
  title: {
    fontSize: fontSize.xxxl,
    fontWeight: '900',
    marginBottom: spacing.sm,
    letterSpacing: 2,
  },
  winnerBadge: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    marginBottom: spacing.md,
    backgroundColor: colors.backgroundSecondary,
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.sm,
    borderRadius: borderRadius.full,
  },
  winnerSymbol: {
    fontSize: fontSize.xxl,
    fontWeight: '900',
  },
  symbolX: {
    color: colors.playerX,
  },
  symbolO: {
    color: colors.playerO,
  },
  winnerLabel: {
    fontSize: fontSize.lg,
    fontWeight: '700',
    color: colors.textSecondary,
  },
  subtitle: {
    fontSize: fontSize.md,
    color: colors.textSecondary,
    textAlign: 'center',
    marginBottom: spacing.xl,
    lineHeight: 22,
  },
  buttonRow: {
    flexDirection: 'row',
    gap: spacing.md,
  },
  button: {
    flex: 1,
  },
});
