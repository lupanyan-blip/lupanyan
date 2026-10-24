package ru.e90pult.core

import kotlin.math.roundToLong

enum class SignalQuality { BAD, MEDIUM, GOOD }

object Signal {
    /** Меньше 15 — красный, 15–20 — жёлтый, больше 20 — зелёный. */
    fun quality(level: Int): SignalQuality = when {
        level < 15 -> SignalQuality.BAD
        level <= 20 -> SignalQuality.MEDIUM
        else -> SignalQuality.GOOD
    }
}

/** Ключи наблюдений: каждое значение хранится со временем получения и ссылкой на SMS. */
enum class ObsKey(val title: String) {
    ARMED("Охрана"),
    LOCKS("Замки"),
    ENGINE("Двигатель"),
    BATTERY("Напряжение АКБ"),
    GSM_LEVEL("Уровень GSM"),
    GSM_LINK("Связь с сервером"),
    FW("Версия ПО модуля"),
    HW("Аппаратный код модуля"),
    MCC("MCC"),
    MNC("MNC"),
    LAC("LAC"),
    CI("CI"),
    IMEI("IMEI"),
    BALANCE("Баланс SIM"),
    RESULT("Результат команды");

    companion object {
        fun of(name: String): ObsKey? = entries.firstOrNull { it.name == name }
    }
}

data class Obs(val key: ObsKey, val text: String, val num: Double? = null)

object Observations {

    /** Значения для карточки состояния и графиков. Баланс — только из ответа на запрос баланса. */
    fun from(p: ParsedResponse, isBalanceReply: Boolean): List<Obs> {
        val out = mutableListOf<Obs>()
        p.armed?.let { out += Obs(ObsKey.ARMED, if (it) "Включена" else "Выключена", if (it) 1.0 else 0.0) }
        p.locksClosed?.let { out += Obs(ObsKey.LOCKS, if (it) "Закрыты" else "Открыты", if (it) 1.0 else 0.0) }
        p.engineRunning?.let { out += Obs(ObsKey.ENGINE, if (it) "Работает" else "Заглушен", if (it) 1.0 else 0.0) }
        p.battery?.let { out += Obs(ObsKey.BATTERY, fmt2(it), it) }
        p.gsmLevel?.let { out += Obs(ObsKey.GSM_LEVEL, it.toString(), it.toDouble()) }
        p.linkRaw?.let { out += Obs(ObsKey.GSM_LINK, it.toString()) }
        p.fwRaw?.let { out += Obs(ObsKey.FW, p.fw?.toString() ?: it) }
        p.hwCode?.let { out += Obs(ObsKey.HW, it) }
        p.mcc?.let { out += Obs(ObsKey.MCC, it.toString(), it.toDouble()) }
        p.mnc?.let { out += Obs(ObsKey.MNC, it.toString().padStart(2, '0'), it.toDouble()) }
        p.lac?.let { out += Obs(ObsKey.LAC, it) }
        p.ci?.let { out += Obs(ObsKey.CI, it) }
        p.imei?.let { out += Obs(ObsKey.IMEI, it) }
        p.result?.let { out += Obs(ObsKey.RESULT, it.title) }
        val bal = if (isBalanceReply) p.balanceForBalanceReply else p.balance
        bal?.let { out += Obs(ObsKey.BALANCE, fmt2(it), it) }
        return out
    }

    fun fmt2(v: Double): String {
        val cents = (v * 100).roundToLong()
        val sign = if (cents < 0) "-" else ""
        val a = kotlin.math.abs(cents)
        return "$sign${a / 100}.${(a % 100).toString().padStart(2, '0')}"
    }
}

/** Короткое описание разобранного ответа для уведомлений и списков. */
object Describe {

    fun volts(v: Double): String = Observations.fmt2(v).replace('.', ',')

    fun short(p: ParsedResponse, isBalanceReply: Boolean = false): String {
        val parts = mutableListOf<String>()
        if (p.greeting) parts += "Модуль перезапущен (приветствие)"
        val r = p.result
        if (r == CmdResult.ERROR) parts += p.errorText ?: r.title else if (r != null) parts += r.title
        p.armed?.let { parts += if (it) "охрана вкл." else "охрана выкл." }
        p.locksClosed?.let { parts += if (it) "замки закрыты" else "замки открыты" }
        p.engineRunning?.let { parts += if (it) "двигатель работает" else "двигатель заглушен" }
        p.fwRaw?.let { parts += "ПО $it" }
        p.gsmLevel?.let { lvl -> parts += "GSM $lvl" + (p.linkRaw?.let { " ($it)" } ?: "") }
        p.battery?.let { parts += "АКБ ${volts(it)} В" }
        val bal = if (isBalanceReply) p.balanceForBalanceReply else p.balance
        bal?.let { parts += "баланс ${volts(it)} ₽" }
        p.pinCode?.let { parts += "пин-код $it" }
        if (p.gprsUnavailable) parts += "GPRS недоступен"
        if (p.unknown.isNotEmpty()) parts += p.unknown.joinToString(" ")
        return if (parts.isEmpty()) p.raw else parts.joinToString(" · ")
    }
}

/** Оценка текущего баланса SIM сигнализации. */
data class BalanceInputs(
    val lastAmount: Double,
    val lastAt: Long,
    /** Частей SMS, отправленных модулем начиная с ответа с балансом (включая его). */
    val moduleSmsParts: Int,
    val smsPrice: Double,
    val monthlyFee: Double,
    val trafficPerDay: Double,
    /** Пополнения (+) после замера. */
    val adjustments: Double,
    val now: Long
)

data class BalanceEstimate(
    val value: Double,
    val days: Double,
    val smsCost: Double,
    val feeCost: Double,
    val trafficCost: Double,
    val adjustments: Double
)

object BalanceMath {
    const val DAY_MS = 86_400_000L

    fun estimate(i: BalanceInputs): BalanceEstimate {
        val days = (i.now - i.lastAt).coerceAtLeast(0L) / DAY_MS.toDouble()
        val sms = i.moduleSmsParts.coerceAtLeast(0) * i.smsPrice
        val fee = i.monthlyFee * 12.0 / 365.25 * days
        val traffic = i.trafficPerDay * days
        val value = i.lastAmount - sms - fee - traffic + i.adjustments
        return BalanceEstimate(r2(value), days, r2(sms), r2(fee), r2(traffic), r2(i.adjustments))
    }

    private fun r2(v: Double): Double = (v * 100).roundToLong() / 100.0
}
