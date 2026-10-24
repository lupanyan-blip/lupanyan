package ru.e90pult.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Общие настройки приложения (не профиля). */
data class Settings(
    val activeProfileId: Long = 0,
    /** Ожидание ответа, минут. */
    val replyTimeoutMin: Int = 5,
    /** Лимиты отправленных SMS (части), 0 — без лимита. */
    val smsLimitDay: Int = 50,
    val smsLimitMonth: Int = 500,
    val openCellIdToken: String = "",
    val emulator: Boolean = false,
    val emuMode: String = "SUCCESS",
    val emuDelaySec: Int = 5,
    val emuFwAfterUpdate: String = "M8",
    val motionGps: Boolean = true,
    val motionBt: Boolean = true,
    val speedThresholdKmh: Int = 10,
    val pinHash: String = "",
    val pinSalt: String = "",
    /** Неверных вводов ПИН подряд и блокировка — переживают перезапуск приложения. */
    val pinFails: Int = 0,
    val pinLockedUntil: Long = 0,
    val biometric: Boolean = true,
    val widgetCodes: List<String> = listOf("11", "10", "21", "09"),
    val widgetConfirm: Boolean = true,
    val onboardingDone: Boolean = false,
    /** Коды команд, ставших доступными после смены версии ПО модуля (показывается один раз). */
    val newsCodes: List<String> = emptyList(),
    val newsFrom: String = "",
    val newsTo: String = "",
    val lastBalanceWarnDay: Long = 0,
    val lastLimitWarnDay: Long = 0,
    val lastInboxScan: Long = 0
) {
    val hasPin: Boolean get() = pinHash.isNotEmpty()
}

class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state.asStateFlow()
    val value: Settings get() = _state.value

    @Synchronized
    fun update(block: (Settings) -> Settings) {
        val next = block(_state.value)
        write(next)
        _state.value = next
    }

    private fun list(s: String?): List<String> =
        s?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    private fun read(): Settings {
        val d = Settings()
        return Settings(
            activeProfileId = sp.getLong("activeProfileId", d.activeProfileId),
            replyTimeoutMin = sp.getInt("replyTimeoutMin", d.replyTimeoutMin),
            smsLimitDay = sp.getInt("smsLimitDay", d.smsLimitDay),
            smsLimitMonth = sp.getInt("smsLimitMonth", d.smsLimitMonth),
            openCellIdToken = sp.getString("openCellIdToken", d.openCellIdToken) ?: "",
            emulator = sp.getBoolean("emulator", d.emulator),
            emuMode = sp.getString("emuMode", d.emuMode) ?: d.emuMode,
            emuDelaySec = sp.getInt("emuDelaySec", d.emuDelaySec),
            emuFwAfterUpdate = sp.getString("emuFwAfterUpdate", d.emuFwAfterUpdate) ?: d.emuFwAfterUpdate,
            motionGps = sp.getBoolean("motionGps", d.motionGps),
            motionBt = sp.getBoolean("motionBt", d.motionBt),
            speedThresholdKmh = sp.getInt("speedThresholdKmh", d.speedThresholdKmh),
            pinHash = sp.getString("pinHash", "") ?: "",
            pinSalt = sp.getString("pinSalt", "") ?: "",
            pinFails = sp.getInt("pinFails", 0),
            pinLockedUntil = sp.getLong("pinLockedUntil", 0),
            biometric = sp.getBoolean("biometric", d.biometric),
            widgetCodes = if (sp.contains("widgetCodes")) list(sp.getString("widgetCodes", "")) else d.widgetCodes,
            widgetConfirm = sp.getBoolean("widgetConfirm", d.widgetConfirm),
            onboardingDone = sp.getBoolean("onboardingDone", d.onboardingDone),
            newsCodes = list(sp.getString("newsCodes", "")),
            newsFrom = sp.getString("newsFrom", "") ?: "",
            newsTo = sp.getString("newsTo", "") ?: "",
            lastBalanceWarnDay = sp.getLong("lastBalanceWarnDay", 0),
            lastLimitWarnDay = sp.getLong("lastLimitWarnDay", 0),
            lastInboxScan = sp.getLong("lastInboxScan", 0)
        )
    }

    private fun write(s: Settings) {
        sp.edit()
            .putLong("activeProfileId", s.activeProfileId)
            .putInt("replyTimeoutMin", s.replyTimeoutMin)
            .putInt("smsLimitDay", s.smsLimitDay)
            .putInt("smsLimitMonth", s.smsLimitMonth)
            .putString("openCellIdToken", s.openCellIdToken)
            .putBoolean("emulator", s.emulator)
            .putString("emuMode", s.emuMode)
            .putInt("emuDelaySec", s.emuDelaySec)
            .putString("emuFwAfterUpdate", s.emuFwAfterUpdate)
            .putBoolean("motionGps", s.motionGps)
            .putBoolean("motionBt", s.motionBt)
            .putInt("speedThresholdKmh", s.speedThresholdKmh)
            .putString("pinHash", s.pinHash)
            .putString("pinSalt", s.pinSalt)
            .putInt("pinFails", s.pinFails)
            .putLong("pinLockedUntil", s.pinLockedUntil)
            .putBoolean("biometric", s.biometric)
            .putString("widgetCodes", s.widgetCodes.joinToString(","))
            .putBoolean("widgetConfirm", s.widgetConfirm)
            .putBoolean("onboardingDone", s.onboardingDone)
            .putString("newsCodes", s.newsCodes.joinToString(","))
            .putString("newsFrom", s.newsFrom)
            .putString("newsTo", s.newsTo)
            .putLong("lastBalanceWarnDay", s.lastBalanceWarnDay)
            .putLong("lastLimitWarnDay", s.lastLimitWarnDay)
            .putLong("lastInboxScan", s.lastInboxScan)
            .apply()
    }
}
