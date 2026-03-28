import React, {useEffect, useRef} from 'react';
import {
  View,
  Text,
  StyleSheet,
  Animated,
  Easing,
} from 'react-native';
import {useSafeAreaInsets} from 'react-native-safe-area-context';
import {useCaroGame} from '../hooks/useCaroGame';
import {Button} from '../components/ui/Button';
import {Card} from '../components/ui/Card';
import {Badge} from '../components/ui/Badge';
import {colors, spacing, fontSize} from '../theme';

interface LobbyScreenProps {
  navigation: any;
  route: {params: {role: 'host' | 'join'}};
}

export const LobbyScreen: React.FC<LobbyScreenProps> = ({navigation, route}) => {
  const insets = useSafeAreaInsets();
  const {role} = route.params;
  const {gameState, loading, startHosting, joinGame, startMatch, stopGame} = useCaroGame();

  const pulseAnim = useRef(new Animated.Value(1)).current;

  useEffect(() => {
    Animated.loop(
      Animated.sequence([
        Animated.timing(pulseAnim, {
          toValue: 1.3,
          duration: 1000,
          easing: Easing.inOut(Easing.ease),
          useNativeDriver: true,
        }),
        Animated.timing(pulseAnim, {
          toValue: 1,
          duration: 1000,
          easing: Easing.inOut(Easing.ease),
          useNativeDriver: true,
        }),
      ]),
    ).start();
  }, [pulseAnim]);

  useEffect(() => {
    if (role === 'host') {
      startHosting('Player');
    } else {
      joinGame('Player');
    }
  }, [role, startHosting, joinGame]);

  // Navigate to game when match starts
  useEffect(() => {
    if (gameState.status === 'PLAYING') {
      navigation.replace('Game');
    }
  }, [gameState.status, navigation]);

  const handleLeave = () => {
    stopGame();
    navigation.goBack();
  };

  const canStart = role === 'host' && gameState.connectedPlayers > 0;

  return (
    <View style={[styles.container, {paddingTop: insets.top + spacing.md}]}>
      {/* Header */}
      <View style={styles.header}>
        <Text style={styles.title}>
          {role === 'host' ? 'Hosting Game' : 'Joining Game'}
        </Text>
        {gameState.gameId ? (
          <Badge text={`ID: ${gameState.gameId}`} variant="success" />
        ) : null}
      </View>

      {/* Scanning animation */}
      <View style={styles.scanArea}>
        <Animated.View
          style={[
            styles.scanCircleOuter,
            {transform: [{scale: pulseAnim}]},
          ]}
        />
        <View style={styles.scanCircleInner}>
          <Text style={styles.scanIcon}>
            {role === 'host' ? '📡' : '🔍'}
          </Text>
        </View>
        <Text style={styles.scanText}>
          {loading
            ? role === 'host'
              ? 'Starting BLE advertising...'
              : 'Scanning for nearby games...'
            : role === 'host'
              ? 'Waiting for players to join...'
              : 'Looking for host...'}
        </Text>
      </View>

      {/* Connected players */}
      <Card style={styles.playersCard}>
        <Text style={styles.sectionTitle}>Connected Players</Text>
        <View style={styles.playersList}>
          {/* Host (always shown) */}
          <View style={styles.playerRow}>
            <View style={[styles.playerDot, styles.dotConnected]} />
            <Text style={styles.playerName}>
              {role === 'host' ? 'You (Host)' : 'Host'}
            </Text>
            <Badge text="X" variant="playerX" />
          </View>

          {/* Challenger slot */}
          <View style={styles.playerRow}>
            <View
              style={[
                styles.playerDot,
                gameState.connectedPlayers > 0
                  ? styles.dotConnected
                  : styles.dotWaiting,
              ]}
            />
            <Text style={styles.playerName}>
              {gameState.connectedPlayers > 0
                ? role === 'join'
                  ? 'You (Challenger)'
                  : 'Challenger'
                : 'Waiting for challenger...'}
            </Text>
            {gameState.connectedPlayers > 0 && (
              <Badge text="O" variant="playerO" />
            )}
          </View>

          {/* Extra spectators */}
          {gameState.connectedPlayers > 1 && (
            <View style={styles.playerRow}>
              <View style={[styles.playerDot, styles.dotConnected]} />
              <Text style={styles.playerName}>
                +{gameState.connectedPlayers - 1} Spectator(s)
              </Text>
              <Badge text="WATCH" variant="warning" />
            </View>
          )}
        </View>
      </Card>

      {/* Actions */}
      <View style={styles.actions}>
        {role === 'host' && (
          <Button
            title="Start Match"
            onPress={startMatch}
            variant="primary"
            size="lg"
            disabled={!canStart}
            loading={loading}
            style={styles.actionButton}
          />
        )}
        <Button
          title="Leave"
          onPress={handleLeave}
          variant="outline"
          size="md"
          style={styles.actionButton}
        />
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: colors.background,
    paddingHorizontal: spacing.lg,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: spacing.xl,
  },
  title: {
    fontSize: fontSize.xl,
    fontWeight: '800',
    color: colors.textPrimary,
  },
  scanArea: {
    alignItems: 'center',
    marginBottom: spacing.xl,
    paddingVertical: spacing.xl,
  },
  scanCircleOuter: {
    position: 'absolute',
    width: 120,
    height: 120,
    borderRadius: 60,
    backgroundColor: 'rgba(99, 102, 241, 0.1)',
    top: spacing.lg,
  },
  scanCircleInner: {
    width: 80,
    height: 80,
    borderRadius: 40,
    backgroundColor: colors.surface,
    borderWidth: 2,
    borderColor: colors.primary,
    justifyContent: 'center',
    alignItems: 'center',
  },
  scanIcon: {
    fontSize: 32,
  },
  scanText: {
    marginTop: spacing.md,
    fontSize: fontSize.md,
    color: colors.textSecondary,
  },
  playersCard: {
    marginBottom: spacing.lg,
  },
  sectionTitle: {
    fontSize: fontSize.lg,
    fontWeight: '700',
    color: colors.textPrimary,
    marginBottom: spacing.md,
  },
  playersList: {
    gap: spacing.md,
  },
  playerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
  },
  playerDot: {
    width: 10,
    height: 10,
    borderRadius: 5,
  },
  dotConnected: {
    backgroundColor: colors.success,
  },
  dotWaiting: {
    backgroundColor: colors.textMuted,
  },
  playerName: {
    flex: 1,
    fontSize: fontSize.md,
    color: colors.textPrimary,
  },
  actions: {
    marginTop: 'auto',
    marginBottom: spacing.xl,
    gap: spacing.md,
  },
  actionButton: {
    width: '100%',
  },
});
