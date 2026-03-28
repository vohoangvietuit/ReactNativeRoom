# Caro BLE — Bluetooth Multiplayer Gomoku

A React Native **Gomoku (Caro)** game that uses **Bluetooth Low Energy (BLE)** for real-time peer-to-peer multiplayer — no internet or server required. Built with a custom native Android **TurboModule**, Room database, and a BLE GATT server/client.

---

## Features

- 15×15 Gomoku board with 5-in-a-row win detection
- Real-time BLE multiplayer: Host advertises, Joiner scans and connects automatically
- Persistent game state via Room (SQLite) — survives app backgrounding
- Foreground BLE service keeps the connection alive while the app is minimised
- Winning-cells highlight animation and draw detection

---

## Tech Stack

| Layer | Technology |
|---|---|
| UI | React Native (TypeScript) |
| Navigation | React Navigation v7 |
| Native module | Kotlin TurboModule (`CaroGameModule`) |
| BLE | Android GATT Server/Client (`BluetoothLeAdvertiser`, `BluetoothLeScanner`) |
| Persistence | Room (SQLite) via `CaroDatabase` |
| Serialisation | Kotlin Serialization (JSON) |

---

## Getting Started

### Prerequisites

- Node.js ≥ 18, Java 17, Android SDK (API 26+)
- React Native environment set up — see the [official guide](https://reactnative.dev/docs/set-up-your-environment)

### Install dependencies

```sh
npm install
```

### Start Metro

```sh
npm start
```

### Run on Android (emulator or device)

```sh
npm run android
```

> **Note:** BLE advertising does not work on emulators. Use a real Android device to test multiplayer. See the [Real Device](#running-on-a-real-android-device) section below.

---

## Project Structure

```
src/
  components/
    game/        # GameBoard, GameHUD, GameOverModal
    ui/          # Button, Card, Badge
  hooks/
    useCaroGame.ts       # All game state & native bridge calls
  navigation/
    AppNavigator.tsx     # Stack navigator
  screens/
    HomeScreen.tsx       # Main menu
    LobbyScreen.tsx      # BLE host/join lobby
    GameScreen.tsx       # Active game board
    HowToPlayScreen.tsx  # Rules
  specs/
    NativeCaroGame.ts    # TurboModule TypeScript spec
  theme/
    index.ts             # Colors, spacing, typography

android/app/src/main/java/com/reactnativeroom/
  turbo/          # CaroGameModule (TurboModule), CaroGamePackage
  service/        # CaroBleService (GATT server + client), BleConstants
  repository/     # CaroRepository, MoveResult
  database/       # Room: CaroDatabase, CaroDao, CaroMove, GameSession
  game/           # WinChecker
```

---

## How to Play

1. **Host Game** — Device A advertises a BLE game session and waits in the lobby
2. **Join Game** — Device B scans and auto-connects to the nearest host
3. The host taps **Start Match** once the challenger appears
4. Players alternate turns — **X** (host) goes first, **O** (challenger) goes second
5. First to place **5 in a row** (horizontal, vertical, or diagonal) wins

---

## Running on a Real Android Device

> **Important:** The multiplayer Bluetooth (BLE) features **require a real Android device**. They will not work on emulators or the iOS Simulator because those environments do not support BLE advertising.

### Prerequisites

- Android device running **Android 8.0 (API 26) or later**
- Two Android devices to test full multiplayer (one host, one joiner)
- USB cable or Wi-Fi ADB

### Step 1 — Enable Developer Options on your phone

1. Open **Settings → About phone**
2. Tap **Build number** 7 times until you see "You are now a developer!"
3. Go back to **Settings → Developer options**
4. Turn on **USB debugging**

### Step 2 — Connect your device

**Via USB:**
```sh
# Plug in the USB cable, then verify device is detected
adb devices
```
You should see your device listed as `device` (not `unauthorized`). If it says `unauthorized`, unlock your phone and tap **Allow** on the USB debugging prompt.

**Via Wi-Fi (Android 11+):**
1. In Developer options, tap **Wireless debugging → Pair device with QR code** (or use the 6-digit code)
2. Then run:
```sh
adb pair <ip>:<port>   # from the pairing dialog
adb connect <ip>:<port>  # use the connection port shown
adb devices  # confirm device appears
```

### Step 3 — Grant Bluetooth permissions on first launch

On **Android 12+**, the app requests these runtime permissions on first launch:
- `BLUETOOTH_SCAN`
- `BLUETOOTH_CONNECT`
- `BLUETOOTH_ADVERTISE`

Tap **Allow** for each prompt. If you accidentally denied them, go to **Settings → Apps → ReactNativeRoom → Permissions → Nearby devices** and enable them manually.

### Step 4 — Build and run on the device

Make sure Metro is running (`npm start`), then in a second terminal:

```sh
# Build debug APK and deploy to connected device
npm run android

# Build release
npx react-native run-android --mode release

# OR target a specific device if you have multiple connected
npx react-native run-android --deviceId <device-serial>
```

To get your device serial:
```sh
adb devices
```

### Step 5 — Test multiplayer BLE flow

1. **Device A (Host):** Tap **Host Game** — the lobby starts advertising via BLE and displays a Game ID
2. **Device B (Joiner):** Tap **Join Game** — scans for nearby hosts and connects automatically
3. Once Device B shows as **Challenger** in Device A's lobby, tap **Start Match** on Device A
4. Both devices navigate to the game board — Device A plays as **X**, Device B as **O**

> **Tip:** Keep both devices within ~10 metres of each other with Bluetooth enabled for reliable BLE connectivity.

### Installing a signed APK directly

If you want to share the app without a cable:

```sh
# Build a debug APK
cd android && ./gradlew assembleDebug

# APK location:
# android/app/build/outputs/apk/debug/app-debug.apk

# Install via ADB
adb install android/app/build/outputs/apk/debug/app-debug.apk
```

---

## Troubleshooting

| Problem | Fix |
|---|---|
| `adb devices` shows `unauthorized` | Unlock phone, tap **Allow** on the USB debugging dialog |
| App crashes on "Host Game" | Ensure Bluetooth is enabled and all Nearby Device permissions are granted |
| "Host Game" shows Bluetooth Unavailable | You are on an emulator — use a real physical device |
| Devices don't discover each other | Keep within ~10 m; restart Bluetooth on both; ensure no other app uses the same BLE service UUID |
| Build fails with Kotlin error | Run `cd android && ./gradlew clean` then retry `npm run android` |
| Metro bundler not found | Run `npm start` in the project root before running the Android command |

---

## Development Tips

**Reload the app:**
- **Android:** Press <kbd>R</kbd> twice, or <kbd>Cmd ⌘</kbd> + <kbd>M</kbd> → Reload
- **iOS Simulator:** Press <kbd>R</kbd>

**View native logs:**
```sh
adb logcat -s CaroBleService CaroGameModule ReactNativeJS
```

**Clean build:**
```sh
cd android && ./gradlew clean && cd .. && npm run android
```
