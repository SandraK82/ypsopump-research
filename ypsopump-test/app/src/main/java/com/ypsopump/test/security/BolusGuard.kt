package com.ypsopump.test.security

import com.ypsopump.test.data.DeliveryMode
import com.ypsopump.test.data.SystemStatusData

/**
 * Pre-flight safety checks for bolus delivery.
 * Implements rate limiting (30s between boluses) and state validation.
 */
class BolusGuard {

    private var lastBolusTimestamp: Long = 0
    private val MIN_BOLUS_INTERVAL_MS = 30_000L
    private val MIN_BATTERY_PERCENT = 10
    private val MIN_BOLUS_UNITS = 0.1f
    private val MAX_BOLUS_UNITS = 30.0f

    data class PreFlightResult(
        val canDeliver: Boolean,
        val failures: List<String>
    )

    fun checkPreFlight(
        amount: Float,
        systemStatus: SystemStatusData?,
        isConnected: Boolean,
        isAuthenticated: Boolean
    ): PreFlightResult {
        val failures = mutableListOf<String>()

        if (!isConnected) failures.add("Pump not connected")
        if (!isAuthenticated) failures.add("Not authenticated")

        if (amount < MIN_BOLUS_UNITS) failures.add("Amount too low (min ${MIN_BOLUS_UNITS}U)")
        if (amount > MAX_BOLUS_UNITS) failures.add("Amount too high (max ${MAX_BOLUS_UNITS}U)")

        val now = System.currentTimeMillis()
        val elapsed = now - lastBolusTimestamp
        if (elapsed < MIN_BOLUS_INTERVAL_MS) {
            val remaining = (MIN_BOLUS_INTERVAL_MS - elapsed) / 1000
            failures.add("Rate limit: wait ${remaining}s")
        }

        if (systemStatus != null) {
            if (systemStatus.deliveryMode == DeliveryMode.STOPPED) {
                failures.add("Pump is STOPPED")
            }
            if (systemStatus.batteryPercent < MIN_BATTERY_PERCENT) {
                failures.add("Battery too low (${systemStatus.batteryPercent}%)")
            }
            if (systemStatus.insulinRemaining < amount) {
                failures.add("Insufficient insulin (${systemStatus.insulinRemaining}U < ${amount}U)")
            }
        } else {
            failures.add("System status unknown - read status first")
        }

        return PreFlightResult(
            canDeliver = failures.isEmpty(),
            failures = failures
        )
    }

    fun recordBolusDelivery() {
        lastBolusTimestamp = System.currentTimeMillis()
    }
}
