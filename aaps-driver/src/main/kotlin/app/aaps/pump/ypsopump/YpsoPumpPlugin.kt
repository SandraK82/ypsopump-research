package app.aaps.pump.ypsopump

import android.content.Context
import app.aaps.core.data.model.BS
import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.plugin.PluginType
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpPluginBase
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.defs.fillFor
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.keys.Preferences
import app.aaps.pump.ypsopump.ble.YpsoBleManager
import app.aaps.pump.ypsopump.comm.commands.BolusCommand
import app.aaps.pump.ypsopump.comm.commands.StatusCommand
import app.aaps.pump.ypsopump.comm.commands.TbrCommand
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import app.aaps.pump.ypsopump.data.YpsoPumpState
import dagger.android.HasAndroidInjector
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AndroidAPS YpsoPump driver plugin.
 *
 * Implements the Pump interface for the Ypsomed YpsoPump insulin pump.
 * Uses XChaCha20-Poly1305 encrypted BLE communication via Lazysodium.
 *
 * Key exchange is handled externally (via Frida/CamAPS proxy or imported key).
 * See docs/09-bypass-options.md for key acquisition strategies.
 *
 * PumpType.YPSOPUMP already exists in AAPS with:
 *   bolusSize=0.1, baseBasalStep=0.01, baseBasalRange=0.02-40.0
 *   tempBasalType=Percent, extendedBolus=(0.1 step, 15min dur, 12h max)
 */
@Singleton
class YpsoPumpPlugin @Inject constructor(
    private val injector: HasAndroidInjector,
    aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val sp: SP,
    private val rxBus: RxBus,
    private val context: Context,
    commandQueue: CommandQueue,
    private val pumpSync: PumpSync,
    private val bleManager: YpsoBleManager,
    private val sessionCrypto: SessionCrypto,
    private val pumpState: YpsoPumpState
) : PumpPluginBase(
    pluginDescription = PluginDescription()
        .mainType(PluginType.PUMP)
        .fragmentClass(YpsoPumpFragment::class.java.name)
        .pluginIcon(app.aaps.core.ui.R.drawable.ic_ypsopump) // TODO: add icon resource
        .pluginName(R.string.ypsopump)
        .shortName(R.string.ypsopump_shortname)
        .preferencesId(R.xml.pref_ypsopump)
        .description(R.string.ypsopump_description),
    pumpType = PumpType.YPSOPUMP,
    injector = injector,
    aapsLogger = aapsLogger,
    commandQueue = commandQueue,
    rh = rh
), Pump {

    private val pumpDescription = PumpDescription().fillFor(PumpType.YPSOPUMP)

    // -- Pump Interface: Connection --

    override fun connect(reason: String) {
        aapsLogger.info(LTag.PUMP, "connect($reason)")
        if (bleManager.isConnected) return

        // Check if we have a shared key
        if (!sessionCrypto.isInitialized) {
            val storedKey = sp.getString(YpsoPumpConst.PREF_SHARED_KEY, "")
            if (storedKey.isNotEmpty()) {
                sessionCrypto.sharedKey = storedKey.chunked(2)
                    .map { it.toInt(16).toByte() }.toByteArray()
                sessionCrypto.writeCounter = sp.getLong(YpsoPumpConst.PREF_WRITE_COUNTER, 0L)
                sessionCrypto.readCounter = sp.getLong(YpsoPumpConst.PREF_READ_COUNTER, 0L)
                sessionCrypto.rebootCounter = sp.getInt(YpsoPumpConst.PREF_REBOOT_COUNTER, 0)
            }
        }

        if (!sessionCrypto.isInitialized) {
            aapsLogger.error(LTag.PUMP, "No shared key configured — key exchange required")
            return
        }

        val address = sp.getString(YpsoPumpConst.PREF_PUMP_SERIAL, "")
        if (address.isEmpty()) {
            bleManager.startScan { device ->
                bleManager.connect(device)
                pumpState.pumpAddress = device.address
            }
        } else {
            // Reconnect to known device
            bleManager.startScan { device ->
                bleManager.connect(device)
            }
        }
    }

    override fun disconnect(reason: String) {
        aapsLogger.info(LTag.PUMP, "disconnect($reason)")
        // Save counters
        sp.putLong(YpsoPumpConst.PREF_WRITE_COUNTER, sessionCrypto.writeCounter)
        sp.putLong(YpsoPumpConst.PREF_READ_COUNTER, sessionCrypto.readCounter)
        sp.putInt(YpsoPumpConst.PREF_REBOOT_COUNTER, sessionCrypto.rebootCounter)
        bleManager.disconnect()
    }

    override fun stopConnecting() {
        bleManager.disconnect()
    }

    override fun isConnected(): Boolean = bleManager.isConnected
    override fun isConnecting(): Boolean =
        pumpState.connectionState == YpsoBleManager.ConnectionState.SCANNING ||
        pumpState.connectionState == YpsoBleManager.ConnectionState.CONNECTING

    override fun isInitialized(): Boolean = pumpState.isInitialized
    override fun isSuspended(): Boolean = pumpState.isSuspended
    override fun isBusy(): Boolean = pumpState.isBolusingInProgress

    // -- Pump Interface: Status --

    override fun getPumpStatus(reason: String) {
        aapsLogger.info(LTag.PUMP, "getPumpStatus($reason)")
        // This runs on a background thread via CommandQueue
        // TODO: implement async BLE command execution
        // For now, structure:
        // val statusCmd = StatusCommand()
        // val response = bleManager.sendCommand(STATUS_UUID, statusCmd.encode())
        // statusCmd.decode(response)
        // pumpState.batteryPercent = statusCmd.batteryPercent
        // pumpState.reservoirUnits = statusCmd.reservoirUnits
        // ...
    }

    override val baseBasalRate: Double
        get() = pumpState.activeBasalRate

    override val reservoirLevel: Double
        get() = pumpState.reservoirUnits

    override val batteryLevel: Int
        get() = pumpState.batteryPercent

    // -- Pump Interface: Treatment --

    override fun deliverTreatment(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult {
        aapsLogger.info(LTag.PUMP, "deliverTreatment(${detailedBolusInfo.insulin}U)")

        val result = PumpEnactResult(injector)

        if (detailedBolusInfo.insulin <= 0) {
            result.success(false).comment("Invalid bolus amount")
            return result
        }

        // TODO: implement async BLE command
        // val cmd = BolusCommand(detailedBolusInfo.insulin)
        // val response = bleManager.sendCommand(BOLUS_UUID, cmd.encode())
        // cmd.decode(response)
        // if (cmd.success) {
        //     result.success(true).enacted(true).bolusDelivered(cmd.deliveredUnits)
        //     pumpSync.syncBolusWithPumpId(...)
        // }

        result.success(false).comment("Not yet implemented — key exchange required first")
        return result
    }

    override fun stopBolusDelivering() {
        aapsLogger.info(LTag.PUMP, "stopBolusDelivering()")
        // val cmd = BolusCommand(0.0, start = false)
        // bleManager.sendCommand(BOLUS_UUID, cmd.encode())
    }

    // -- Pump Interface: TBR --

    override fun setTempBasalAbsolute(
        absoluteRate: Double, durationInMinutes: Int,
        profile: app.aaps.core.interfaces.profile.Profile,
        enforceNew: Boolean,
        tbrType: PumpSync.TemporaryBasalType
    ): PumpEnactResult {
        aapsLogger.info(LTag.PUMP, "setTempBasalAbsolute(${absoluteRate}U/h, ${durationInMinutes}min)")

        // Convert absolute rate to percentage
        val currentBasal = profile.getBasal()
        val percent = if (currentBasal > 0) ((absoluteRate / currentBasal) * 100).toInt() else 100

        val result = PumpEnactResult(injector)

        // TODO: implement via BLE
        // val cmd = TbrCommand(percent, durationInMinutes)
        // ...

        result.success(false).comment("Not yet implemented")
        return result
    }

    override fun setTempBasalPercent(
        percent: Int, durationInMinutes: Int,
        profile: app.aaps.core.interfaces.profile.Profile,
        enforceNew: Boolean,
        tbrType: PumpSync.TemporaryBasalType
    ): PumpEnactResult {
        aapsLogger.info(LTag.PUMP, "setTempBasalPercent(${percent}%, ${durationInMinutes}min)")

        val result = PumpEnactResult(injector)
        result.success(false).comment("Not yet implemented")
        return result
    }

    override fun cancelTempBasal(enforceNew: Boolean): PumpEnactResult {
        aapsLogger.info(LTag.PUMP, "cancelTempBasal()")

        val result = PumpEnactResult(injector)
        // val cmd = TbrCommand(100, 0, start = false)
        result.success(false).comment("Not yet implemented")
        return result
    }

    // -- Pump Interface: Extended Bolus --

    override fun setExtendedBolus(insulin: Double, durationInMinutes: Int): PumpEnactResult {
        val result = PumpEnactResult(injector)
        result.success(false).comment("Extended bolus not yet implemented")
        return result
    }

    override fun cancelExtendedBolus(): PumpEnactResult {
        val result = PumpEnactResult(injector)
        result.success(false).comment("Not yet implemented")
        return result
    }

    // -- Pump Interface: Profile --

    override fun setNewBasalProfile(profile: app.aaps.core.interfaces.profile.Profile): PumpEnactResult {
        aapsLogger.info(LTag.PUMP, "setNewBasalProfile()")
        val result = PumpEnactResult(injector)
        // TODO: write 24 hourly basal rates to pump via SETTING_VALUE commands
        result.success(false).comment("Not yet implemented")
        return result
    }

    override fun isThisProfileSet(profile: app.aaps.core.interfaces.profile.Profile): Boolean {
        // Compare profile values with pump state
        return false // TODO: implement
    }

    // -- Pump Interface: History --

    override fun loadTDDs(): PumpEnactResult {
        val result = PumpEnactResult(injector)
        // TODO: read TDD via COUNTER_ID/COUNTER_VALUE commands
        result.success(false)
        return result
    }

    // -- Pump Interface: Identity --

    override fun manufacturer(): ManufacturerType = ManufacturerType.Ypsomed
    override fun model(): PumpType = PumpType.YPSOPUMP
    override fun serialNumber(): String = pumpState.serialNumber

    // -- Pump Interface: Misc --

    override fun shortStatus(veryShort: Boolean): String {
        val sb = StringBuilder()
        if (pumpState.isConnected) {
            sb.append("Connected")
            sb.append(" | Battery: ${pumpState.batteryPercent}%")
            sb.append(" | Reservoir: ${pumpState.reservoirUnits}U")
            if (pumpState.isTbrActive) {
                sb.append(" | TBR: ${pumpState.activeTbrPercent}% (${pumpState.activeTbrRemainingMinutes}min)")
            }
        } else {
            sb.append("Disconnected")
        }
        return sb.toString()
    }

    override val isFakingTempsByExtendedBoluses: Boolean = false

    override fun canHandleDST(): Boolean = true

    override fun timezoneOrDSTChanged(timeChangeType: PumpSync.TimeChangeType) {
        // TODO: update pump time via SYSTEM_DATE / SYSTEM_TIME commands
    }

    override fun getCustomActions(): List<Any> = emptyList()
}
