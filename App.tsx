/**
 * Caro (Gomoku) BLE Sync
 * Multiplayer board game over Bluetooth Low Energy
 */

import React from 'react';
import {StatusBar, useColorScheme} from 'react-native';
import {SafeAreaProvider} from 'react-native-safe-area-context';
import {AppNavigator} from './src/navigation/AppNavigator';

function App() {
  const isDarkMode = useColorScheme() === 'dark';

  return (
    <SafeAreaProvider>
      <StatusBar barStyle="light-content" backgroundColor="#0A0E1A" />
      <AppNavigator />
    </SafeAreaProvider>
  );
}

export default App;
