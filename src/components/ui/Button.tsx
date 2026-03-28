import React from 'react';
import {
  TouchableOpacity,
  Text,
  StyleSheet,
  ActivityIndicator,
  type ViewStyle,
  type TextStyle,
} from 'react-native';
import {colors, borderRadius, fontSize, spacing} from '../../theme';

type ButtonVariant = 'primary' | 'secondary' | 'outline' | 'ghost' | 'danger';
type ButtonSize = 'sm' | 'md' | 'lg';

interface ButtonProps {
  title: string;
  onPress: () => void;
  variant?: ButtonVariant;
  size?: ButtonSize;
  disabled?: boolean;
  loading?: boolean;
  icon?: React.ReactNode;
  style?: ViewStyle;
}

export const Button: React.FC<ButtonProps> = ({
  title,
  onPress,
  variant = 'primary',
  size = 'md',
  disabled = false,
  loading = false,
  icon,
  style,
}) => {
  const buttonStyle: ViewStyle[] = [
    styles.base,
    sizeStyles[size],
    variantStyles[variant],
    (disabled || loading) && styles.disabled,
    style as ViewStyle,
  ].filter(Boolean) as ViewStyle[];

  const textStyle: TextStyle[] = [
    styles.text,
    textSizeStyles[size],
    textVariantStyles[variant],
    (disabled || loading) && styles.disabledText,
  ].filter(Boolean) as TextStyle[];

  return (
    <TouchableOpacity
      style={buttonStyle}
      onPress={onPress}
      disabled={disabled || loading}
      activeOpacity={0.7}>
      {loading ? (
        <ActivityIndicator
          size="small"
          color={variant === 'outline' ? colors.primary : colors.textPrimary}
        />
      ) : (
        <>
          {icon}
          <Text style={textStyle}>{title}</Text>
        </>
      )}
    </TouchableOpacity>
  );
};

const styles = StyleSheet.create({
  base: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: spacing.sm,
  },
  text: {
    fontWeight: '700',
  },
  disabled: {
    opacity: 0.5,
  },
  disabledText: {
    opacity: 0.7,
  },
});

const sizeStyles: Record<ButtonSize, ViewStyle> = {
  sm: {
    height: 36,
    paddingHorizontal: spacing.md,
    borderRadius: borderRadius.sm,
  },
  md: {
    height: 48,
    paddingHorizontal: spacing.lg,
    borderRadius: borderRadius.md,
  },
  lg: {
    height: 56,
    paddingHorizontal: spacing.xl,
    borderRadius: borderRadius.lg,
  },
};

const textSizeStyles: Record<ButtonSize, TextStyle> = {
  sm: {fontSize: fontSize.sm},
  md: {fontSize: fontSize.lg},
  lg: {fontSize: fontSize.xl},
};

const variantStyles: Record<ButtonVariant, ViewStyle> = {
  primary: {
    backgroundColor: colors.primary,
  },
  secondary: {
    backgroundColor: colors.surface,
  },
  outline: {
    backgroundColor: 'transparent',
    borderWidth: 1.5,
    borderColor: colors.primary,
  },
  ghost: {
    backgroundColor: 'transparent',
  },
  danger: {
    backgroundColor: colors.error,
  },
};

const textVariantStyles: Record<ButtonVariant, TextStyle> = {
  primary: {color: colors.textPrimary},
  secondary: {color: colors.textSecondary},
  outline: {color: colors.primary},
  ghost: {color: colors.textSecondary},
  danger: {color: colors.textPrimary},
};
