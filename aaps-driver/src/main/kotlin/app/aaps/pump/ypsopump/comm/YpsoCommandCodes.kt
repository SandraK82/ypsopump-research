package app.aaps.pump.ypsopump.comm

/**
 * All 33 YpsoPump BLE command codes (indices 0-32).
 * Source: YpsoCommandChars.java (854 lines, uk.ac.cam.ap.ypsomed_15x.ypsomed)
 */
enum class YpsoCommandCodes(val index: Int, val description: String) {

    // -- Base Service (0-4) --
    PUMP_BASE_SERVICE_VERSION(0, "Pump base service version"),
    MASTER_SOFTWARE_VERSION(1, "Master software version"),
    SUPERVISOR_SOFTWARE_VERSION(2, "Supervisor software version"),
    AUTHORIZATION_PASSWORD(3, "Authorization password"),
    PUMP_COUNTRY_CODE(4, "Pump country code"),

    // -- Settings Service (5-9) --
    SETTINGS_SERVICE_VERSION(5, "Settings service version"),
    SETTING_ID(6, "Setting identifier"),
    SETTING_VALUE(7, "Setting value"),
    SYSTEM_DATE(8, "System date"),
    SYSTEM_TIME(9, "System time"),

    // -- History Service (10-25) --
    HISTORY_SERVICE_VERSION(10, "History service version"),
    ALARM_ENTRY_COUNT(11, "Alarm entry count"),
    ALARM_ENTRY_INDEX(12, "Alarm entry index"),
    ALARM_ENTRY_VALUE(13, "Alarm entry value"),
    EVENT_ENTRY_COUNT(14, "Event entry count"),
    EVENT_ENTRY_INDEX(15, "Event entry index"),
    EVENT_ENTRY_VALUE(16, "Event entry value"),
    SYSTEM_ENTRY_COUNT(17, "System entry count"),
    COMPLAINT_ENTRY_COUNT(18, "Complaint entry count"),
    SYSTEM_ENTRY_INDEX(19, "System entry index"),
    COMPLAINT_ENTRY_INDEX(20, "Complaint entry index"),
    SYSTEM_ENTRY_VALUE(21, "System entry value"),
    COMPLAINT_ENTRY_VALUE(22, "Complaint entry value"),
    COUNTER_ID(23, "Counter identifier"),
    COUNTER_VALUE(24, "Counter value"),
    CLEAR_HISTORY_PASSWORD(25, "Clear history password"),

    // -- Control Service (26-32) --
    CONTROL_SERVICE_VERSION(26, "Control service version"),
    START_STOP_BOLUS(27, "Start/stop bolus"),
    GET_BOLUS_STATUS(28, "Get bolus status"),
    START_STOP_TBR(29, "Start/stop TBR"),
    GET_SYSTEM_STATUS(30, "Get system status"),
    BOLUS_STATUS_NOTIFICATION(31, "Bolus status notification"),
    VIRTUAL_CHAR(32, "Virtual characteristic");

    companion object {
        fun fromIndex(index: Int): YpsoCommandCodes? = entries.find { it.index == index }
    }
}
