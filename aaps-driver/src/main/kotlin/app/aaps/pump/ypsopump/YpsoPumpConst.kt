package app.aaps.pump.ypsopump

import java.util.UUID

/**
 * YpsoPump BLE constants: UUIDs, command indices, error codes.
 * Derived from reverse engineering of CamAPS FX v1.4(190).111.
 */
object YpsoPumpConst {

    // -- BLE Service UUIDs --
    val SCAN_FILTER_UUID: UUID = UUID.fromString("669a0c20-0008-969e-e211-ffffffffffff")
    val GENERAL_SERVICE_UUID: UUID = UUID.fromString("669a0c20-0008-969e-e211-eeeeeeeeeeee")
    val DATA_CHAR_A_UUID: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcff0000000a")
    val DATA_CHAR_B_UUID: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcff0000000b")
    val CONTROL_CHAR_UUID: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcff000000ff")
    val PUMP_SPECIFIC_UUID: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbeb0147bc5")
    val SECONDARY_SCAN_UUID: UUID = UUID.fromString("fb349b5f-8000-0080-0010-0000adde0000")
    val SECONDARY_DATA_UUID: UUID = UUID.fromString("fb349b5f-8000-0080-0010-0000feda0002")

    // Standard BLE CCCD for notifications
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // -- Device Name Pattern --
    const val DEVICE_NAME_PREFIX = "mylife YpsoPump"

    // -- Crypto Constants --
    const val KEY_SIZE = 32           // Curve25519 / XChaCha20 key size
    const val NONCE_SIZE = 24         // XChaCha20 extended nonce
    const val TAG_SIZE = 16           // Poly1305 auth tag
    const val COUNTER_DATA_SIZE = 12  // rebootCounter(4) + writeCounter(8)
    const val KEY_EXCHANGE_READ_SIZE = 64   // challenge(32) + pumpPubKey(32)
    const val KEY_EXCHANGE_WRITE_SIZE = 116 // encrypted payload from backend
    const val KEY_EXPIRY_DAYS = 28

    // -- Connection Timeouts --
    const val CONNECT_TIMEOUT_MS = 30_000L
    const val COMMAND_TIMEOUT_MS = 10_000L
    const val SCAN_TIMEOUT_MS = 30_000L

    // -- SharedPreferences Keys --
    const val PREF_SHARED_KEY = "ypso_shared_key"
    const val PREF_PRIVATE_KEY = "ypso_private_key"
    const val PREF_PUMP_PUBLIC_KEY = "ypso_pump_public_key"
    const val PREF_WRITE_COUNTER = "ypso_write_counter"
    const val PREF_READ_COUNTER = "ypso_read_counter"
    const val PREF_REBOOT_COUNTER = "ypso_reboot_counter"
    const val PREF_KEY_DATE = "ypso_key_date"
    const val PREF_PUMP_SERIAL = "ypso_pump_serial"
}
