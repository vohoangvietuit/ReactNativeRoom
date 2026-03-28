import React, {useEffect, useRef} from 'react';
import {
  View,
  Text,
  StyleSheet,
  StatusBar,
  Animated,
  Dimensions,
} from 'react-native';
import {useSafeAreaInsets} from 'react-native-safe-area-context';
import {Button} from '../components/ui/Button';
import {colors, spacing, fontSize, borderRadius} from '../theme';

const {width: SCREEN_WIDTH} = Dimensions.get('window');

interface HomeScreenProps {
  navigation: any;
}

export const HomeScreen: React.FC<HomeScreenProps> = ({navigation}) => {
  const insets = useSafeAreaInsets();
  const fadeAnim = useRef(new Animated.Value(0)).current;
  const slideAnim = useRef(new Animated.Value(30)).current;

  useEffect(() => {
    Animated.parallel([
      Animated.timing(fadeAnim, {
        toValue: 1,
        duration: 800,
        useNativeDriver: true,
      }),
      Animated.timing(slideAnim, {
        toValue: 0,
        duration: 800,
        useNativeDriver: true,
      }),
    ]).start();
  }, [fadeAnim, slideAnim]);

  return (
    <View style={[styles.container, {paddingTop: insets.top}]}>
      <StatusBar barStyle="light-content" backgroundColor={colors.background} />

      {/* Decorative grid background */}
      <View style={styles.gridDecoration}>
        {Array.from({length: 6}).map((_r, row) => (
          <View key={row} style={styles.gridRow}>
            {Array.from({length: 6}).map((_c, col) => (
              <View key={col} style={styles.gridCell}>
                {((row + col) % 3 === 0) && (
                  <Text style={[styles.gridSymbol, col % 2 === 0 ? styles.gridX : styles.gridO]}>
                    {col % 2 === 0 ? '×' : '○'}
                  </Text>
                )}
              </View>
            ))}
          </View>
        ))}
      </View>

      {/* Content */}
      <Animated.View
        style={[
          styles.content,
          {opacity: fadeAnim, transform: [{translateY: slideAnim}]},
        ]}>
        {/* Logo area */}
        <View style={styles.logoArea}>
          <View style={styles.logoGrid}>
            <View style={styles.logoRow}>
              <Text style={[styles.logoCell, styles.cellX]}>X</Text>
              <Text style={[styles.logoCell, styles.cellEmpty]}>·</Text>
              <Text style={[styles.logoCell, styles.cellO]}>O</Text>
            </View>
            <View style={styles.logoRow}>
              <Text style={[styles.logoCell, styles.cellEmpty]}>·</Text>
              <Text style={[styles.logoCell, styles.cellX]}>X</Text>
              <Text style={[styles.logoCell, styles.cellEmpty]}>·</Text>
            </View>
            <View style={styles.logoRow}>
              <Text style={[styles.logoCell, styles.cellO]}>O</Text>
              <Text style={[styles.logoCell, styles.cellEmpty]}>·</Text>
              <Text style={[styles.logoCell, styles.cellX]}>X</Text>
            </View>
          </View>
          <Text style={styles.title}>CARO</Text>
          <Text style={styles.subtitle}>
            Bluetooth Multiplayer Gomoku
          </Text>
        </View>

        {/* Menu */}
        <View style={styles.menu}>
          <Button
            title="Host Game"
            onPress={() => navigation.navigate('Lobby', {role: 'host'})}
            variant="primary"
            size="lg"
            icon={<Text style={styles.menuIcon}>📡</Text>}
            style={styles.menuButton}
          />

          <Button
            title="Join Game"
            onPress={() => navigation.navigate('Lobby', {role: 'join'})}
            variant="secondary"
            size="lg"
            icon={<Text style={styles.menuIcon}>🔍</Text>}
            style={styles.menuButton}
          />

          <Button
            title="How to Play"
            onPress={() => navigation.navigate('HowToPlay')}
            variant="outline"
            size="lg"
            icon={<Text style={styles.menuIcon}>📖</Text>}
            style={styles.menuButton}
          />
        </View>

        {/* Footer */}
        <View style={styles.footer}>
          <Text style={styles.footerText}>
            Room + BLE + TurboModule Demo
          </Text>
          <Text style={styles.version}>v1.0.0</Text>
        </View>
      </Animated.View>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: colors.background,
  },
  gridDecoration: {
    ...StyleSheet.absoluteFillObject,
    opacity: 0.06,
    justifyContent: 'center',
    alignItems: 'center',
  },
  gridRow: {
    flexDirection: 'row',
  },
  gridCell: {
    width: SCREEN_WIDTH / 6,
    height: SCREEN_WIDTH / 6,
    justifyContent: 'center',
    alignItems: 'center',
  },
  gridSymbol: {
    fontSize: 32,
    fontWeight: '300',
  },
  gridX: {
    color: colors.playerX,
  },
  gridO: {
    color: colors.playerO,
  },
  content: {
    flex: 1,
    justifyContent: 'center',
    paddingHorizontal: spacing.xl,
  },
  logoArea: {
    alignItems: 'center',
    marginBottom: spacing.xxl,
  },
  logoGrid: {
    marginBottom: spacing.lg,
    borderWidth: 2,
    borderColor: colors.border,
    borderRadius: borderRadius.md,
    overflow: 'hidden',
  },
  logoRow: {
    flexDirection: 'row',
  },
  logoCell: {
    width: 48,
    height: 48,
    textAlign: 'center',
    textAlignVertical: 'center',
    lineHeight: 48,
    fontSize: 24,
    fontWeight: '900',
    borderWidth: 0.5,
    borderColor: colors.border,
  },
  cellX: {
    color: colors.playerX,
    backgroundColor: 'rgba(0, 212, 255, 0.08)',
  },
  cellO: {
    color: colors.playerO,
    backgroundColor: 'rgba(255, 107, 107, 0.08)',
  },
  cellEmpty: {
    color: colors.textMuted,
    backgroundColor: colors.surface,
  },
  title: {
    fontSize: fontSize.title,
    fontWeight: '900',
    color: colors.textPrimary,
    letterSpacing: 12,
  },
  subtitle: {
    fontSize: fontSize.md,
    color: colors.textSecondary,
    marginTop: spacing.xs,
    letterSpacing: 1,
  },
  menu: {
    gap: spacing.md,
    marginBottom: spacing.xxl,
  },
  menuButton: {
    width: '100%',
  },
  menuIcon: {
    fontSize: 20,
  },
  footer: {
    alignItems: 'center',
  },
  footerText: {
    fontSize: fontSize.xs,
    color: colors.textMuted,
  },
  version: {
    fontSize: fontSize.xs,
    color: colors.textMuted,
    marginTop: spacing.xs,
    opacity: 0.5,
  },
});
