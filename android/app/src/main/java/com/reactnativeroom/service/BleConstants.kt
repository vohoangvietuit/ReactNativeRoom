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

    // BLE message types for game control
    const val MSG_PLAYER_JOINED = "PLAYER_JOINED"
    const val MSG_GAME_START = "GAME_START"
    const val MSG_GAME_OVER = "GAME_OVER"
    const val MSG_GAME_RESET = "GAME_RESET"

    // Channel ID for foreground notification
    const val NOTIFICATION_CHANNEL_ID = "caro_ble_channel"
    const val NOTIFICATION_ID = 1001
}
