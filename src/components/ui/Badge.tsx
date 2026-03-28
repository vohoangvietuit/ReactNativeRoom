import React from 'react';
import {View, Text, StyleSheet, type ViewStyle} from 'react-native';
import {colors, borderRadius, fontSize, spacing} from '../../theme';

type BadgeVariant = 'default' | 'success' | 'warning' | 'error' | 'playerX' | 'playerO';

interface BadgeProps {
  text: string;
  variant?: BadgeVariant;
  style?: ViewStyle;
}

export const Badge: React.FC<BadgeProps> = ({text, variant = 'default', style}) => {
  return (
    <View style={[styles.base, variantStyles[variant], style]}>
      <Text style={[styles.text, textVariantStyles[variant]]}>{text}</Text>
    </View>
  );
};

const styles = StyleSheet.create({
  base: {
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xs,
    borderRadius: borderRadius.full,
    alignSelf: 'flex-start',
  },
  text: {
    fontSize: fontSize.xs,
    fontWeight: '700',
    textTransform: 'uppercase',
    letterSpacing: 0.5,
  },
});

const variantStyles: Record<BadgeVariant, ViewStyle> = {
  default: {backgroundColor: colors.surfaceLight},
  success: {backgroundColor: 'rgba(16, 185, 129, 0.2)'},
  warning: {backgroundColor: 'rgba(245, 158, 11, 0.2)'},
  error: {backgroundColor: 'rgba(239, 68, 68, 0.2)'},
  playerX: {backgroundColor: 'rgba(0, 212, 255, 0.2)'},
  playerO: {backgroundColor: 'rgba(255, 107, 107, 0.2)'},
};

const textVariantStyles: Record<BadgeVariant, {color: string}> = {
  default: {color: colors.textSecondary},
  success: {color: colors.success},
  warning: {color: colors.warning},
  error: {color: colors.error},
  playerX: {color: colors.playerX},
  playerO: {color: colors.playerO},
};
