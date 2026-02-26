package app.aaps.pump.ypsopump.ble

import app.aaps.pump.ypsopump.YpsoPumpConst
import app.aaps.pump.ypsopump.comm.YpsoCommandCodes
import java.util.UUID

/**
 * GATT UUID mapping for YpsoPump characteristics.
 *
 * Each command index maps to a specific GATT characteristic UUID.
 * The base UUID pattern is 669a0c20-0008-969e-e211-fcXXXXXXXXXX.
 *
 * Note: The exact per-command UUIDs were not fully extracted from the
 * obfuscated YpsoCommandChars.java. This mapping uses the known UUIDs
 * and must be completed via BLE service discovery or further RE.
 */
object GattAttributes {

    // Known service UUIDs
    val SCAN_FILTER_SERVICE = YpsoPumpConst.SCAN_FILTER_UUID
    val GENERAL_SERVICE = YpsoPumpConst.GENERAL_SERVICE_UUID

    // Known characteristic UUIDs
    val DATA_CHAR_A = YpsoPumpConst.DATA_CHAR_A_UUID
    val DATA_CHAR_B = YpsoPumpConst.DATA_CHAR_B_UUID
    val CONTROL_CHAR = YpsoPumpConst.CONTROL_CHAR_UUID
    val PUMP_SPECIFIC = YpsoPumpConst.PUMP_SPECIFIC_UUID

    // CCCD for enabling notifications
    val CCCD = YpsoPumpConst.CCCD_UUID

    /**
     * Discover and map command indices to characteristic UUIDs at runtime.
     * Called after GATT service discovery completes.
     *
     * The mapping is built dynamically because the exact UUID-to-command
     * relationship requires runtime analysis of the GATT table.
     */
    class CharacteristicMap {
        private val commandToUuid = mutableMapOf<YpsoCommandCodes, UUID>()
        private val uuidToCommand = mutableMapOf<UUID, YpsoCommandCodes>()

        fun put(command: YpsoCommandCodes, uuid: UUID) {
            commandToUuid[command] = uuid
            uuidToCommand[uuid] = command
        }

        fun getUuid(command: YpsoCommandCodes): UUID? = commandToUuid[command]
        fun getCommand(uuid: UUID): YpsoCommandCodes? = uuidToCommand[uuid]

        val isEmpty: Boolean get() = commandToUuid.isEmpty()
    }
}
