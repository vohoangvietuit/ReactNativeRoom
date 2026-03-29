import React from 'react';
import {
  View,
  Text,
  StyleSheet,
  ActivityIndicator,
  TouchableOpacity,
} from 'react-native';
import {colors, spacing, fontSize, borderRadius} from '../../theme';

interface ReconnectingOverlayProps {
  visible: boolean;
  onReconnect: () => void;
  onCancel: () => void;
}

/**
 * Non-blocking connection-loss banner.
 * Rendered as a plain View strip above the game board — does NOT overlay or block taps.
 * The board remains fully interactive while this is visible (moves are queued offline).
 */
export const ReconnectingOverlay: React.FC<ReconnectingOverlayProps> = ({
  visible,
  onReconnect,
  onCancel,
}) => {
  if (!visible) return null;

  return (
    <View style={styles.banner}>
      <ActivityIndicator size="small" color={colors.surface} />
      <Text style={styles.message}>Connection lost — moves are saved</Text>
      <View style={styles.actions}>
        <TouchableOpacity style={styles.reconnectBtn} onPress={onReconnect}>
          <Text style={styles.reconnectText}>Reconnect</Text>
        </TouchableOpacity>
        <TouchableOpacity style={styles.cancelBtn} onPress={onCancel}>
          <Text style={styles.cancelText}>Forfeit</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  banner: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: colors.error ?? '#c0392b',
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.md,
    gap: spacing.sm,
    flexWrap: 'wrap',
  },
  message: {
    flex: 1,
    fontSize: fontSize.xs ?? 11,
    color: colors.surface,
    fontWeight: '600',
  },
  actions: {
    flexDirection: 'row',
    gap: spacing.sm,
  },
  reconnectBtn: {
    backgroundColor: 'rgba(255,255,255,0.25)',
    borderRadius: borderRadius.sm,
    paddingVertical: 4,
    paddingHorizontal: spacing.sm,
  },
  reconnectText: {
    fontSize: fontSize.xs ?? 11,
    color: colors.surface,
    fontWeight: '700',
  },
  cancelBtn: {
    paddingVertical: 4,
    paddingHorizontal: spacing.sm,
  },
  cancelText: {
    fontSize: fontSize.xs ?? 11,
    color: 'rgba(255,255,255,0.75)',
    fontWeight: '600',
  },
});
