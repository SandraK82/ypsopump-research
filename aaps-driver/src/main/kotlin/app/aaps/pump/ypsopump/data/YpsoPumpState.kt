package app.aaps.pump.ypsopump.data

import app.aaps.pump.ypsopump.ble.YpsoBleManager.ConnectionState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the current state of the YpsoPump connection and pump data.
 * Thread-safe via @Volatile annotations.
 */
@Singleton
class YpsoPumpState @Inject constructor() {

    // -- Connection State --
    @Volatile var connectionState: ConnectionState = ConnectionState.DISCONNECTED
    @Volatile var serialNumber: String = ""
    @Volatile var pumpAddress: String = ""

    // -- Pump Status --
    @Volatile var batteryPercent: Int = 0
    @Volatile var reservoirUnits: Double = 0.0
    @Volatile var isSuspended: Boolean = false
    @Volatile var isBolusingInProgress: Boolean = false
    @Volatile var isTbrActive: Boolean = false

    // -- Firmware --
    @Volatile var firmwareVersion: String = ""
    @Volatile var masterVersion: String = ""
    @Volatile var supervisorVersion: String = ""

    // -- Active Delivery --
    @Volatile var activeBasalRate: Double = 0.0
    @Volatile var activeTbrPercent: Int = 100
    @Volatile var activeTbrRemainingMinutes: Int = 0
    @Volatile var activeBolusRemaining: Double = 0.0

    // -- Profiles --
    val profileA: FloatArray = FloatArray(24) // 24 hourly basal rates
    val profileB: FloatArray = FloatArray(24) // alternate profile
    @Volatile var isProfileAActive: Boolean = true

    // -- Timestamps --
    @Volatile var lastConnectionTime: Long = 0L
    @Volatile var lastStatusTime: Long = 0L
    @Volatile var keyExchangeTime: Long = 0L

    // -- Error Tracking --
    @Volatile var lastErrorCode: Int = 0
    @Volatile var lastErrorMessage: String = ""

    val isConnected: Boolean
        get() = connectionState == ConnectionState.CONNECTED

    val isInitialized: Boolean
        get() = serialNumber.isNotEmpty() && firmwareVersion.isNotEmpty()

    fun reset() {
        connectionState = ConnectionState.DISCONNECTED
        batteryPercent = 0
        reservoirUnits = 0.0
        isSuspended = false
        isBolusingInProgress = false
        isTbrActive = false
        activeBasalRate = 0.0
        activeTbrPercent = 100
        activeTbrRemainingMinutes = 0
        activeBolusRemaining = 0.0
        lastErrorCode = 0
        lastErrorMessage = ""
    }
}
