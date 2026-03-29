import React, { useEffect, useRef, useCallback, useState } from 'react';
import {
  View,
  Text,
  StyleSheet,
  Animated,
  Easing,
  Platform,
  PermissionsAndroid,
  Modal,
  TextInput,
  TouchableOpacity,
  Alert,
  Linking,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useCaroGame } from '../hooks/useCaroGame';
import { Button } from '../components/ui/Button';
import { Card } from '../components/ui/Card';
import { Badge } from '../components/ui/Badge';
import { colors, spacing, fontSize, borderRadius } from '../theme';

interface LobbyScreenProps {
  navigation: any;
  route: { params: { role: 'host' | 'join'; passKey?: string } };
}

export const LobbyScreen: React.FC<LobbyScreenProps> = ({
  navigation,
  route,
}) => {
  const insets = useSafeAreaInsets();
  const { role, passKey: routePassKey } = route.params;
  const {
    gameState,
    loading,
    error,
    authRequired,
    authFailed,
    startHosting,
    joinGame,
    startMatch,
    stopGame,
    setReady,
    cancelGame,
    submitPassKey,
  } = useCaroGame();

  const pulseAnim = useRef(new Animated.Value(1)).current;
  const [passKeyInput, setPassKeyInput] = useState('');
  const [passKeyError, setPassKeyError] = useState('');
  const [readySent, setReadySent] = useState(false);

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

  const requestBlePermissions = useCallback(async (): Promise<boolean> => {
    if (Platform.OS !== 'android') return true;
    if (Platform.Version < 31) {
      const loc = await PermissionsAndroid.request(
        PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
        {
          title: 'Location Permission',
          message: 'Caro BLE needs location access for Bluetooth scanning.',
          buttonPositive: 'Allow',
        },
      );
      if (loc !== PermissionsAndroid.RESULTS.GRANTED) return false;
      // On API < 31, BLE scanning also requires Location Services (GPS) to be ON.
      // We can only warn the user — the OS does not expose a JS API to check directly.
      Alert.alert(
        'Location Services Required',
        'Please ensure Location Services (GPS) is enabled in Settings for Bluetooth scanning to work.',
        [
          { text: 'Open Settings', onPress: () => Linking.openSettings() },
          { text: 'Continue', style: 'cancel' },
        ],
      );
      return true;
    }
    const results = await PermissionsAndroid.requestMultiple([
      PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN,
      PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT,
      PermissionsAndroid.PERMISSIONS.BLUETOOTH_ADVERTISE,
    ]);
    return Object.values(results).every(
      r => r === PermissionsAndroid.RESULTS.GRANTED,
    );
  }, []);

  useEffect(() => {
    const init = async () => {
      await requestBlePermissions();
      if (role === 'host') {
        await startHosting('Player', routePassKey ?? '').catch(() => {});
      } else {
        await joinGame('Player').catch(() => {});
      }
    };
    init();
  }, [role, routePassKey, startHosting, joinGame, requestBlePermissions]);

  // Navigate to game when match starts (challenger waits for GAME_START event → status = PLAYING)
  useEffect(() => {
    if (gameState.status === 'PLAYING') {
      navigation.replace('Game');
    }
  }, [gameState.status, navigation]);

  // Show error if auth permanently fails (challenger kicked)
  useEffect(() => {
    if (authFailed) {
      setPassKeyError('Wrong passkey — access denied.');
    }
  }, [authFailed]);

  const handleLeave = useCallback(() => {
    stopGame();
    navigation.goBack();
  }, [stopGame, navigation]);

  const handleCancel = useCallback(async () => {
    await cancelGame();
    navigation.popToTop();
  }, [cancelGame, navigation]);

  const handleReady = useCallback(async () => {
    await setReady();
    setReadySent(true);
  }, [setReady]);

  const handleSubmitPassKey = useCallback(async () => {
    if (!passKeyInput.trim()) {
      setPassKeyError('Please enter the passkey.');
      return;
    }
    setPassKeyError('');
    await submitPassKey(passKeyInput.trim());
    setPassKeyInput('');
  }, [submitPassKey, passKeyInput]);

  // Host: can start once challenger is ready
  const canStart = role === 'host' && gameState.challengerReady === true;
  // Host: challenger connected but not yet ready
  const challengerConnected = role === 'host' && gameState.connectedPlayers > 0;

  const hasPassKey = !!routePassKey;

  return (
    <View style={[styles.container, { paddingTop: insets.top + spacing.md }]}>
      {/* Header */}
      <View style={styles.header}>
        <Text style={styles.title}>
          {role === 'host' ? 'Hosting Game' : 'Joining Game'}
        </Text>
        {gameState.gameId ? (
          <Badge text={`ID: ${gameState.gameId}`} variant="success" />
        ) : null}
      </View>

      {/* Error state */}
      {error ? (
        <View style={styles.errorCard}>
          <Text style={styles.errorIcon}>⚠️</Text>
          <Text style={styles.errorTitle}>Bluetooth Unavailable</Text>
          <Text style={styles.errorMessage}>{error}</Text>
          <Text style={styles.errorHint}>
            This feature requires a real Android device with Bluetooth enabled.
            Simulators and emulators do not support BLE advertising.
          </Text>
          <Button
            title="Go Back"
            onPress={handleLeave}
            variant="outline"
            size="md"
            style={styles.errorButton}
          />
        </View>
      ) : (
        <>
          {/* Scanning / advertising animation */}
          <View style={styles.scanArea}>
            <Animated.View
              style={[
                styles.scanCircleOuter,
                { transform: [{ scale: pulseAnim }] },
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
                ? challengerConnected
                  ? 'Challenger connected — waiting for ready...'
                  : 'Waiting for players to join...'
                : gameState.connectedPlayers > 0
                ? 'Connected to host!'
                : 'Looking for host...'}
            </Text>
            {hasPassKey && role === 'host' && (
              <Badge text="🔒 Password Protected" variant="default" />
            )}
          </View>

          {/* Players card */}
          <Card style={styles.playersCard}>
            <Text style={styles.sectionTitle}>Connected Players</Text>
            <View style={styles.playersList}>
              {/* Host row */}
              <View style={styles.playerRow}>
                <View style={[styles.playerDot, styles.dotConnected]} />
                <View style={styles.playerInfo}>
                  <Text style={styles.playerName}>
                    {role === 'host' ? 'You (Host)' : 'Host'}
                  </Text>
                  {role === 'join' && gameState.hostDeviceId ? (
                    <Text style={styles.deviceId} numberOfLines={1}>
                      {gameState.hostDeviceId}
                    </Text>
                  ) : null}
                </View>
                <Badge text="X" variant="playerX" />
              </View>

              {/* Challenger row */}
              <View style={styles.playerRow}>
                <View
                  style={[
                    styles.playerDot,
                    gameState.connectedPlayers > 0
                      ? styles.dotConnected
                      : styles.dotWaiting,
                  ]}
                />
                <View style={styles.playerInfo}>
                  <Text style={styles.playerName}>
                    {gameState.connectedPlayers > 0
                      ? role === 'join'
                        ? 'You (Challenger)'
                        : gameState.challengerDeviceName || 'Challenger'
                      : 'Waiting for challenger...'}
                  </Text>
                  {role === 'host' && gameState.challengerDeviceId ? (
                    <Text style={styles.deviceId} numberOfLines={1}>
                      {gameState.challengerDeviceId}
                    </Text>
                  ) : null}
                </View>
                {gameState.connectedPlayers > 0 && (
                  <View style={styles.badgeRow}>
                    <Badge text="O" variant="playerO" />
                    {gameState.challengerReady && (
                      <Badge text="Ready ✓" variant="success" />
                    )}
                  </View>
                )}
              </View>
            </View>
          </Card>

          {/* Actions */}
          <View style={styles.actions}>
            {role === 'host' && (
              <Button
                title={canStart ? 'Start Match' : 'Waiting for Ready...'}
                onPress={startMatch}
                variant="primary"
                size="lg"
                disabled={!canStart}
                loading={loading}
                style={styles.actionButton}
              />
            )}

            {role === 'join' &&
              gameState.connectedPlayers > 0 &&
              !authRequired && (
                <Button
                  title={readySent ? 'Ready ✓' : 'Ready'}
                  onPress={handleReady}
                  variant="primary"
                  size="lg"
                  disabled={readySent}
                  style={styles.actionButton}
                />
              )}

            <Button
              title={role === 'host' ? 'Cancel Hosting' : 'Leave'}
              onPress={handleCancel}
              variant="outline"
              size="md"
              style={styles.actionButton}
            />
          </View>
        </>
      )}

      {/* Passkey modal — shown to challenger when host requires auth */}
      <Modal
        visible={authRequired && !authFailed}
        transparent
        animationType="fade"
        onRequestClose={() => {}}
      >
        <View style={styles.modalOverlay}>
          <View style={styles.modalCard}>
            <Text style={styles.modalTitle}>Passkey Required</Text>
            <Text style={styles.modalSubtitle}>
              The host has protected this game. Enter the passkey to continue.
            </Text>
            {passKeyError ? (
              <Text style={styles.passKeyError}>{passKeyError}</Text>
            ) : null}
            <TextInput
              style={styles.passkeyInput}
              placeholder="Enter passkey"
              placeholderTextColor={colors.textMuted}
              value={passKeyInput}
              onChangeText={text => {
                setPassKeyInput(text);
                setPassKeyError('');
              }}
              secureTextEntry
              autoCapitalize="none"
            />
            <View style={styles.modalButtons}>
              <TouchableOpacity
                style={[styles.modalBtn, styles.modalBtnCancel]}
                onPress={() => {
                  Alert.alert('Cancel', 'Leave the game?', [
                    { text: 'Stay' },
                    { text: 'Leave', onPress: handleCancel },
                  ]);
                }}
              >
                <Text style={styles.modalBtnText}>Cancel</Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={[styles.modalBtn, styles.modalBtnConfirm]}
                onPress={handleSubmitPassKey}
              >
                <Text style={[styles.modalBtnText, styles.modalBtnConfirmText]}>
                  Submit
                </Text>
              </TouchableOpacity>
            </View>
          </View>
        </View>
      </Modal>

      {/* Auth fail overlay */}
      <Modal
        visible={authFailed}
        transparent
        animationType="fade"
        onRequestClose={() => {}}
      >
        <View style={styles.modalOverlay}>
          <View style={styles.modalCard}>
            <Text style={styles.errorIcon}>🔒</Text>
            <Text style={styles.modalTitle}>Access Denied</Text>
            <Text style={styles.modalSubtitle}>
              Incorrect passkey. You have been disconnected from this game.
            </Text>
            <Button
              title="Go Back"
              onPress={handleCancel}
              variant="primary"
              size="md"
            />
          </View>
        </View>
      </Modal>
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
    textAlign: 'center',
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
  playerInfo: {
    flex: 1,
  },
  playerName: {
    fontSize: fontSize.md,
    color: colors.textPrimary,
  },
  deviceId: {
    fontSize: fontSize.xs,
    color: colors.textMuted,
    fontFamily: 'monospace',
    marginTop: 2,
  },
  badgeRow: {
    flexDirection: 'row',
    gap: spacing.xs,
  },
  actions: {
    marginTop: 'auto',
    marginBottom: spacing.xl,
    gap: spacing.md,
  },
  actionButton: {
    width: '100%',
  },
  errorCard: {
    alignItems: 'center',
    backgroundColor: 'rgba(239,68,68,0.1)',
    borderRadius: 12,
    borderWidth: 1,
    borderColor: 'rgba(239,68,68,0.3)',
    padding: spacing.lg,
    marginBottom: spacing.lg,
  },
  errorIcon: {
    fontSize: 32,
    marginBottom: spacing.sm,
  },
  errorTitle: {
    fontSize: fontSize.lg,
    fontWeight: '700',
    color: '#ef4444',
    marginBottom: spacing.xs,
  },
  errorMessage: {
    fontSize: fontSize.sm,
    color: colors.textSecondary,
    textAlign: 'center',
    marginBottom: spacing.sm,
  },
  errorHint: {
    fontSize: fontSize.xs,
    color: colors.textMuted,
    textAlign: 'center',
    marginBottom: spacing.md,
    lineHeight: 18,
  },
  errorButton: {
    alignSelf: 'center',
  },
  // Passkey modal
  modalOverlay: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.7)',
    justifyContent: 'center',
    alignItems: 'center',
    padding: spacing.xl,
  },
  modalCard: {
    backgroundColor: colors.surface,
    borderRadius: borderRadius.lg,
    padding: spacing.xl,
    width: '100%',
    alignItems: 'center',
  },
  modalTitle: {
    fontSize: fontSize.xl,
    fontWeight: '800',
    color: colors.textPrimary,
    marginBottom: spacing.xs,
    alignSelf: 'flex-start',
  },
  modalSubtitle: {
    fontSize: fontSize.sm,
    color: colors.textSecondary,
    marginBottom: spacing.lg,
    alignSelf: 'flex-start',
  },
  passKeyError: {
    fontSize: fontSize.sm,
    color: '#ef4444',
    marginBottom: spacing.sm,
    alignSelf: 'flex-start',
  },
  passkeyInput: {
    width: '100%',
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: borderRadius.md,
    padding: spacing.md,
    color: colors.textPrimary,
    fontSize: fontSize.md,
    marginBottom: spacing.lg,
  },
  modalButtons: {
    flexDirection: 'row',
    gap: spacing.md,
    width: '100%',
  },
  modalBtn: {
    flex: 1,
    paddingVertical: spacing.sm,
    borderRadius: borderRadius.md,
    alignItems: 'center',
  },
  modalBtnCancel: {
    borderWidth: 1,
    borderColor: colors.border,
  },
  modalBtnConfirm: {
    backgroundColor: colors.primary,
  },
  modalBtnText: {
    fontSize: fontSize.md,
    fontWeight: '600',
    color: colors.textSecondary,
  },
  modalBtnConfirmText: {
    color: colors.background,
  },
});
