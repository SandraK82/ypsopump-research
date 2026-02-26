package app.aaps.pump.ypsopump

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dagger.android.support.DaggerFragment
import app.aaps.pump.ypsopump.data.YpsoPumpState
import javax.inject.Inject

/**
 * UI fragment for the YpsoPump driver tab in AAPS.
 * Displays connection state, battery, reservoir, and active delivery info.
 *
 * TODO: Implement proper ViewBinding layout (res/layout/ypsopump_fragment.xml).
 * For now, this is a placeholder that creates views programmatically.
 */
class YpsoPumpFragment : DaggerFragment() {

    @Inject lateinit var pumpState: YpsoPumpState

    private var statusText: TextView? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // TODO: Replace with inflated XML layout
        val textView = TextView(requireContext()).apply {
            setPadding(32, 32, 32, 32)
            textSize = 14f
        }
        statusText = textView
        return textView
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun updateUI() {
        statusText?.text = buildString {
            appendLine("YpsoPump Driver (Research/WIP)")
            appendLine("═══════════════════════════════")
            appendLine()
            appendLine("Connection: ${pumpState.connectionState}")
            appendLine("Serial: ${pumpState.serialNumber.ifEmpty { "—" }}")
            appendLine("Firmware: ${pumpState.firmwareVersion.ifEmpty { "—" }}")
            appendLine()
            appendLine("Battery: ${pumpState.batteryPercent}%")
            appendLine("Reservoir: ${pumpState.reservoirUnits} U")
            appendLine("Suspended: ${pumpState.isSuspended}")
            appendLine()
            if (pumpState.isTbrActive) {
                appendLine("TBR: ${pumpState.activeTbrPercent}% (${pumpState.activeTbrRemainingMinutes} min)")
            }
            if (pumpState.isBolusingInProgress) {
                appendLine("Bolus in progress: ${pumpState.activeBolusRemaining} U remaining")
            }
            appendLine()
            if (pumpState.lastErrorCode != 0) {
                appendLine("Last error: ${pumpState.lastErrorCode} — ${pumpState.lastErrorMessage}")
            }
            appendLine()
            appendLine("Note: Key exchange must be performed via")
            appendLine("Frida/CamAPS proxy before first connection.")
            appendLine("See docs/09-bypass-options.md")
        }
    }
}
