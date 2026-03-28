import React from 'react';
import {View, Text, StyleSheet, ScrollView} from 'react-native';
import {useSafeAreaInsets} from 'react-native-safe-area-context';
import {Card} from '../components/ui/Card';
import {Button} from '../components/ui/Button';
import {colors, spacing, fontSize, borderRadius} from '../theme';

interface HowToPlayScreenProps {
  navigation: any;
}

const sections = [
  {
    icon: '🎯',
    title: 'Game Rules',
    content:
      'Caro (Gomoku) is played on a 15×15 grid. Two players take turns placing their marks (X or O). The first player to get exactly 5 in a row — horizontally, vertically, or diagonally — wins the game!',
  },
  {
    icon: '📡',
    title: 'How to Host',
    steps: [
      'Tap "Host Game" on the main menu',
      'Your phone starts a Bluetooth LE advertisement',
      'Wait for a Challenger to join your game',
      'Once connected, tap "Start Match" to begin',
      'You play as X (first move)',
    ],
  },
  {
    icon: '🔍',
    title: 'How to Join',
    steps: [
      'Tap "Join Game" on the main menu',
      'Your phone scans for nearby hosted games',
      'Connect to a host automatically',
      'Wait for the host to start the match',
      'You play as O (second move)',
    ],
  },
  {
    icon: '⚡',
    title: 'How It Works (Technical)',
    content:
      "This app demonstrates a real-world pattern: React Native TurboModules bridge to Kotlin native code. The game engine uses Room (SQLite) for persistent board state, Bluetooth Low Energy (GATT Server/Client) for real-time peer-to-peer sync, and Kotlin Coroutines for async operations. The Host's database is the single source of truth — all moves are validated, and game state is automatically re-synced when navigating.",
  },
  {
    icon: '💡',
    title: 'Tips & Strategy',
    steps: [
      'Control the center of the board early',
      'Try to create "open fours" — 4 in a row with both ends open',
      'Block your opponent when they have 3 in a row',
      'Think 2-3 moves ahead',
      "Watch for diagonal threats — they're easy to miss!",
    ],
  },
];

export const HowToPlayScreen: React.FC<HowToPlayScreenProps> = ({navigation}) => {
  const insets = useSafeAreaInsets();

  return (
    <View style={[styles.container, {paddingTop: insets.top}]}>
      <ScrollView
        style={styles.scrollView}
        contentContainerStyle={styles.scrollContent}
        showsVerticalScrollIndicator={false}>
        {/* Header */}
        <View style={styles.header}>
          <Text style={styles.title}>How to Play</Text>
          <Text style={styles.subtitle}>
            Everything you need to know about Caro BLE Sync
          </Text>
        </View>

        {/* Sections */}
        {sections.map((section, index) => (
          <Card key={index} style={styles.sectionCard} variant="glass">
            <View style={styles.sectionHeader}>
              <Text style={styles.sectionIcon}>{section.icon}</Text>
              <Text style={styles.sectionTitle}>{section.title}</Text>
            </View>

            {section.content && (
              <Text style={styles.sectionContent}>{section.content}</Text>
            )}

            {section.steps && (
              <View style={styles.stepsList}>
                {section.steps.map((step, stepIndex) => (
                  <View key={stepIndex} style={styles.stepRow}>
                    <View style={styles.stepNumber}>
                      <Text style={styles.stepNumberText}>{stepIndex + 1}</Text>
                    </View>
                    <Text style={styles.stepText}>{step}</Text>
                  </View>
                ))}
              </View>
            )}
          </Card>
        ))}

        {/* Data flow diagram */}
        <Card style={styles.sectionCard} variant="glass">
          <View style={styles.sectionHeader}>
            <Text style={styles.sectionIcon}>🔄</Text>
            <Text style={styles.sectionTitle}>Data Flow</Text>
          </View>
          <View style={styles.flowRow}>
            <View style={styles.flowBox}>
              <Text style={styles.flowLabel}>Your Move</Text>
            </View>
            <Text style={styles.flowArrow}>→</Text>
            <View style={styles.flowBox}>
              <Text style={styles.flowLabel}>TurboModule</Text>
            </View>
            <Text style={styles.flowArrow}>→</Text>
            <View style={styles.flowBox}>
              <Text style={styles.flowLabel}>Room DB</Text>
            </View>
          </View>
          <View style={styles.flowRow}>
            <View style={styles.flowBox}>
              <Text style={styles.flowLabel}>Win Check</Text>
            </View>
            <Text style={styles.flowArrow}>→</Text>
            <View style={styles.flowBox}>
              <Text style={styles.flowLabel}>BLE Broadcast</Text>
            </View>
            <Text style={styles.flowArrow}>→</Text>
            <View style={styles.flowBox}>
              <Text style={styles.flowLabel}>All Devices</Text>
            </View>
          </View>
        </Card>

        {/* Back button */}
        <Button
          title="Back to Menu"
          onPress={() => navigation.goBack()}
          variant="outline"
          size="lg"
          style={styles.backButton}
        />
      </ScrollView>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: colors.background,
  },
  scrollView: {
    flex: 1,
  },
  scrollContent: {
    padding: spacing.lg,
    paddingBottom: spacing.xxl,
  },
  header: {
    marginBottom: spacing.xl,
  },
  title: {
    fontSize: fontSize.xxl,
    fontWeight: '900',
    color: colors.textPrimary,
  },
  subtitle: {
    fontSize: fontSize.md,
    color: colors.textSecondary,
    marginTop: spacing.xs,
  },
  sectionCard: {
    marginBottom: spacing.md,
  },
  sectionHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    marginBottom: spacing.md,
  },
  sectionIcon: {
    fontSize: 24,
  },
  sectionTitle: {
    fontSize: fontSize.lg,
    fontWeight: '700',
    color: colors.textPrimary,
  },
  sectionContent: {
    fontSize: fontSize.md,
    color: colors.textSecondary,
    lineHeight: 22,
  },
  stepsList: {
    gap: spacing.sm,
  },
  stepRow: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: spacing.sm,
  },
  stepNumber: {
    width: 24,
    height: 24,
    borderRadius: 12,
    backgroundColor: colors.primary,
    justifyContent: 'center',
    alignItems: 'center',
    marginTop: 1,
  },
  stepNumberText: {
    fontSize: fontSize.xs,
    fontWeight: '700',
    color: colors.textPrimary,
  },
  stepText: {
    flex: 1,
    fontSize: fontSize.md,
    color: colors.textSecondary,
    lineHeight: 22,
  },
  flowRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: spacing.xs,
    marginVertical: spacing.xs,
  },
  flowBox: {
    backgroundColor: colors.backgroundSecondary,
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xs,
    borderRadius: borderRadius.sm,
    borderWidth: 1,
    borderColor: colors.border,
  },
  flowLabel: {
    fontSize: fontSize.xs,
    color: colors.primaryLight,
    fontWeight: '600',
  },
  flowArrow: {
    fontSize: fontSize.md,
    color: colors.textMuted,
  },
  backButton: {
    marginTop: spacing.lg,
  },
});
