import React from 'react';
import {View, StyleSheet, type ViewStyle} from 'react-native';
import {colors, borderRadius, spacing, shadow} from '../../theme';

interface CardProps {
  children: React.ReactNode;
  style?: ViewStyle;
  variant?: 'default' | 'glass';
}

export const Card: React.FC<CardProps> = ({children, style, variant = 'default'}) => {
  return (
    <View
      style={[
        styles.base,
        variant === 'glass' && styles.glass,
        shadow.md,
        style,
      ]}>
      {children}
    </View>
  );
};

const styles = StyleSheet.create({
  base: {
    backgroundColor: colors.surface,
    borderRadius: borderRadius.lg,
    padding: spacing.lg,
    borderWidth: 1,
    borderColor: colors.border,
  },
  glass: {
    backgroundColor: 'rgba(26, 31, 53, 0.8)',
    borderColor: 'rgba(55, 65, 81, 0.5)',
  },
});
