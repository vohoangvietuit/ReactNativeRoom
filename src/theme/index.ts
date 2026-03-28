export const colors = {
  // Core palette
  background: '#0A0E1A',
  backgroundSecondary: '#111827',
  surface: '#1A1F35',
  surfaceLight: '#252B45',

  // Accent
  primary: '#6366F1',       // Indigo
  primaryLight: '#818CF8',
  primaryDark: '#4F46E5',

  // Player colors
  playerX: '#00D4FF',       // Cyan
  playerXLight: '#67EFFF',
  playerXDark: '#0099CC',
  playerO: '#FF6B6B',       // Coral
  playerOLight: '#FF9999',
  playerODark: '#CC4444',

  // Status
  success: '#10B981',
  warning: '#F59E0B',
  error: '#EF4444',

  // Text
  textPrimary: '#F9FAFB',
  textSecondary: '#9CA3AF',
  textMuted: '#6B7280',

  // Borders
  border: '#374151',
  borderLight: '#4B5563',

  // Grid
  gridLine: '#1E2540',
  gridCellHover: 'rgba(99, 102, 241, 0.15)',
  lastMoveHighlight: 'rgba(99, 102, 241, 0.4)',
  winHighlight: 'rgba(16, 185, 129, 0.5)',
};

export const spacing = {
  xs: 4,
  sm: 8,
  md: 16,
  lg: 24,
  xl: 32,
  xxl: 48,
};

export const fontSize = {
  xs: 10,
  sm: 12,
  md: 14,
  lg: 16,
  xl: 20,
  xxl: 28,
  xxxl: 36,
  title: 48,
};

export const borderRadius = {
  sm: 6,
  md: 12,
  lg: 16,
  xl: 24,
  full: 999,
};

export const shadow = {
  sm: {
    shadowColor: '#000',
    shadowOffset: {width: 0, height: 2},
    shadowOpacity: 0.25,
    shadowRadius: 4,
    elevation: 3,
  },
  md: {
    shadowColor: '#000',
    shadowOffset: {width: 0, height: 4},
    shadowOpacity: 0.3,
    shadowRadius: 8,
    elevation: 6,
  },
  lg: {
    shadowColor: '#000',
    shadowOffset: {width: 0, height: 8},
    shadowOpacity: 0.4,
    shadowRadius: 16,
    elevation: 12,
  },
  glow: (color: string) => ({
    shadowColor: color,
    shadowOffset: {width: 0, height: 0},
    shadowOpacity: 0.6,
    shadowRadius: 12,
    elevation: 8,
  }),
};
