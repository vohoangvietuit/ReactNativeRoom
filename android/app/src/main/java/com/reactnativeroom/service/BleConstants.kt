package com.reactnativeroom.service

import java.util.UUID

object BleConstants {
    // Custom UUIDs for Caro Game BLE service
    val CARO_SERVICE_UUID: UUID       = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    val MOVE_NOTIFY_CHAR_UUID: UUID   = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567891")
    val MOVE_WRITE_CHAR_UUID: UUID    = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567892")
    val FULL_SYNC_READ_CHAR_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567893")
    val GAME_CONTROL_CHAR_UUID: UUID  = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567894")

    // Client Characteristic Configuration Descriptor (standard BLE)
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val MTU_SIZE = 512

    // BLE message types for game control (host → challenger broadcasts)
    const val MSG_PLAYER_JOINED = "PLAYER_JOINED"
    const val MSG_GAME_START = "GAME_START"
    const val MSG_GAME_OVER = "GAME_OVER"
    const val MSG_GAME_RESET = "GAME_RESET"
    const val MSG_PLAYER_LEFT = "PLAYER_LEFT"
    const val MSG_GAME_CANCEL = "GAME_CANCEL"
    const val MSG_HEARTBEAT = "HEARTBEAT"
    const val MSG_AUTH_CHALLENGE = "AUTH_CHALLENGE"  // host → challenger: AUTH_CHALLENGE:gameId:1/0
    const val MSG_AUTH_OK = "AUTH_OK"                // host → challenger: passkey accepted
    const val MSG_AUTH_FAIL = "AUTH_FAIL"            // host → challenger: passkey rejected
    const val MSG_RECONNECTING = "RECONNECTING"      // local synthetic event for overlay
    const val MSG_RECONNECTED = "RECONNECTED"        // local synthetic event on success

    // BLE message types sent challenger → host (writes to GAME_CONTROL char)
    const val MSG_AUTH = "AUTH"                      // AUTH:{hash8}
    const val MSG_PLAYER_READY = "PLAYER_READY"      // PLAYER_READY:{deviceId}:{deviceName}
    const val MSG_SUBS_COMPLETE = "SUBS_COMPLETE"    // challenger → host: all subscriptions done

    // Channel ID for foreground notification
    const val NOTIFICATION_CHANNEL_ID = "caro_ble_channel"
    const val NOTIFICATION_ID = 1001
}
