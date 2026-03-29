/**
 * Caro (Gomoku) BLE Sync
 * Multiplayer board game over Bluetooth Low Energy
 */

import React, { useEffect } from 'react';
import { StatusBar, useColorScheme, NativeModules } from 'react-native';
import {SafeAreaProvider} from 'react-native-safe-area-context';
import {AppNavigator} from './src/navigation/AppNavigator';

function App() {
  const isDarkMode = useColorScheme() === 'dark';

  // Wipe any stale Room DB state from a previous crashed/closed session.
  useEffect(() => {
    NativeModules.CaroGame?.initialize?.().catch(() => {});
  }, []);

  return (
    <SafeAreaProvider>
      <StatusBar barStyle="light-content" backgroundColor="#0A0E1A" />
      <AppNavigator />
    </SafeAreaProvider>
  );
}

export default App;
