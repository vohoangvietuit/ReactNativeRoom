import React from 'react';
import {View, Text, StyleSheet} from 'react-native';
import {Badge} from '../ui/Badge';
import {colors, spacing, fontSize, borderRadius} from '../../theme';

interface GameHUDProps {
  mySymbol: string;
  currentTurn: string;
  myRole: string;
  moveCount: number;
  connectedPlayers: number;
  status: string;
}

export const GameHUD: React.FC<GameHUDProps> = ({
  mySymbol,
  currentTurn,
  myRole,
  moveCount,
  connectedPlayers,
  status,
}) => {
  const isMyTurn = currentTurn === mySymbol && myRole !== 'spectator';
  const turnLabel = isMyTurn ? 'Your Turn' : `${currentTurn}'s Turn`;

  return (
    <View style={styles.container}>
      {/* Top row: player info + status */}
      <View style={styles.topRow}>
        <View style={styles.playerInfo}>
          <Text
            style={[
              styles.symbolText,
              mySymbol === 'X' ? styles.symbolX : styles.symbolO,
            ]}>
            {mySymbol || '?'}
          </Text>
          <View>
            <Text style={styles.youLabel}>You</Text>
            <Badge
              text={myRole || 'none'}
              variant={
                myRole === 'host'
                  ? 'success'
                  : myRole === 'spectator'
                    ? 'warning'
                    : 'default'
              }
            />
          </View>
        </View>

        <View style={styles.turnIndicator}>
          <View
            style={[
              styles.turnDot,
              isMyTurn ? styles.turnDotActive : styles.turnDotInactive,
            ]}
          />
          <Text style={[styles.turnText, isMyTurn && styles.turnTextActive]}>
            {status === 'PLAYING' ? turnLabel : status}
          </Text>
        </View>

        <View style={styles.statsCol}>
          <Text style={styles.statValue}>{moveCount}</Text>
          <Text style={styles.statLabel}>Moves</Text>
        </View>

        <View style={styles.statsCol}>
          <Text style={styles.statValue}>{connectedPlayers}</Text>
          <Text style={styles.statLabel}>Players</Text>
        </View>
      </View>

      {/* Spectator banner */}
      {myRole === 'spectator' && (
        <View style={styles.spectatorBanner}>
          <Text style={styles.spectatorText}>SPECTATING</Text>
        </View>
      )}
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
  },
  topRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
  },
  playerInfo: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
  },
  symbolText: {
    fontSize: fontSize.xxl,
    fontWeight: '900',
  },
  symbolX: {
    color: colors.playerX,
  },
  symbolO: {
    color: colors.playerO,
  },
  youLabel: {
    fontSize: fontSize.xs,
    color: colors.textMuted,
    marginBottom: 2,
  },
  turnIndicator: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: spacing.xs,
  },
  turnDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
  },
  turnDotActive: {
    backgroundColor: colors.success,
  },
  turnDotInactive: {
    backgroundColor: colors.textMuted,
  },
  turnText: {
    fontSize: fontSize.md,
    color: colors.textSecondary,
    fontWeight: '600',
  },
  turnTextActive: {
    color: colors.success,
  },
  statsCol: {
    alignItems: 'center',
  },
  statValue: {
    fontSize: fontSize.lg,
    fontWeight: '700',
    color: colors.textPrimary,
  },
  statLabel: {
    fontSize: fontSize.xs,
    color: colors.textMuted,
  },
  spectatorBanner: {
    marginTop: spacing.sm,
    backgroundColor: 'rgba(245, 158, 11, 0.15)',
    paddingVertical: spacing.xs,
    borderRadius: borderRadius.sm,
    alignItems: 'center',
  },
  spectatorText: {
    color: colors.warning,
    fontSize: fontSize.xs,
    fontWeight: '800',
    letterSpacing: 2,
  },
});
