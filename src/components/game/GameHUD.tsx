import React from 'react';
import { View, Text, StyleSheet, TouchableOpacity } from 'react-native';
import {Badge} from '../ui/Badge';
import {colors, spacing, fontSize} from '../../theme';

interface GameHUDProps {
  mySymbol: string;
  currentTurn: string;
  myRole: string;
  moveCount: number;
  connectedPlayers: number;
  status: string;
  onForfeit?: () => void;
}

export const GameHUD: React.FC<GameHUDProps> = ({
  mySymbol,
  currentTurn,
  myRole,
  moveCount,
  connectedPlayers,
  status,
  onForfeit,
}) => {
  const isMyTurn = currentTurn === mySymbol;
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
            ]}
          >
            {mySymbol || '?'}
          </Text>
          <View>
            <Text style={styles.youLabel}>You</Text>
            <Badge
              text={myRole || 'none'}
              variant={myRole === 'host' ? 'success' : 'default'}
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
          <View
            style={[
              styles.connectionDot,
              connectedPlayers > 0 ? styles.dotOnline : styles.dotOffline,
            ]}
          />
          <Text style={styles.statLabel}>
            {connectedPlayers > 0 ? 'Online' : 'Offline'}
          </Text>
        </View>

        {onForfeit && status === 'PLAYING' && (
          <TouchableOpacity style={styles.forfeitBtn} onPress={onForfeit}>
            <Text style={styles.forfeitText}>✕ Forfeit</Text>
          </TouchableOpacity>
        )}
      </View>
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
  connectionDot: {
    width: 10,
    height: 10,
    borderRadius: 5,
    marginBottom: 2,
  },
  dotOnline: {
    backgroundColor: colors.success,
  },
  dotOffline: {
    backgroundColor: colors.error,
  },
  forfeitBtn: {
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xs,
    borderRadius: 6,
    borderWidth: 1,
    borderColor: 'rgba(239,68,68,0.4)',
  },
  forfeitText: {
    fontSize: fontSize.xs,
    color: '#ef4444',
    fontWeight: '600',
  },
});
