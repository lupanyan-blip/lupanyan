package ru.e90pult.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Профиль машины. */
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Номер SIM сигнализации (нормализованный, +7…). */
    val alarmPhone: String,
    /** subscriptionId SIM телефона для отправки; -1 — SIM по умолчанию. */
    val subscriptionId: Int = -1,
    val simLabel: String = "",
    /** Пароль PW, зашифрованный ключом Android Keystore. */
    val passwordEnc: String = "",
    /** Отправлять команды с паролем (с номеров M2–M4 или при включённом 00961). */
    val passwordMode: Boolean = false,
    /** «#» в конце: 0 — авто (с ПО O7), 1 — всегда, 2 — никогда. */
    val hashMode: Int = 0,
    /** Последняя известная версия ПО GSM-модуля (из ответа 09). */
    val moduleFw: String = "",
    /** Версия ПО центрального блока, если известна (вводится вручную). */
    val cbFw: String = "",
    /** Bluetooth-устройство машины (магнитола): подключение = «телефон в машине». */
    val btAddress: String = "",
    val btName: String = "",
    val ussd: String = "*100#",
    val smsPrice: Double = 0.0,
    val monthlyFee: Double = 0.0,
    val trafficPerDay: Double = 0.0,
    val balanceWarn: Double = 50.0,
    val balanceRemindDays: Int = 14,
    /** Своя таблица команд (JSON); пусто — таблица по умолчанию. */
    val commandsJson: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

object SmsKind {
    const val COMMAND = "COMMAND"
    const val REPLY = "REPLY"
    const val EVENT = "EVENT"
    const val UPDATE = "UPDATE"
}

object SmsStatus {
    const val SENDING = "SENDING"
    const val SENT = "SENT"
    const val SEND_FAILED = "SEND_FAILED"
    const val DELIVERED = "DELIVERED"
    const val DELIVERY_FAILED = "DELIVERY_FAILED"
    const val RECEIVED = "RECEIVED"
}

/** Каждое SMS — целиком, как есть. */
@Entity(
    tableName = "sms",
    indices = [Index("profileId"), Index("timestamp"), Index("runId")]
)
data class SmsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val outgoing: Boolean,
    val phone: String,
    val text: String,
    /** Исходящее — время отправки; входящее — время получения телефоном. */
    val timestamp: Long,
    /** Время SMS-центра для входящих (0 — неизвестно). */
    val scTimestamp: Long = 0,
    val parts: Int = 1,
    val kind: String,
    val runId: Long? = null,
    val status: String = "",
    val emulated: Boolean = false,
    /** RECEIVER, INBOX, EMULATOR, APP */
    val source: String = "",
    val note: String = ""
)

object RunState {
    const val SENDING = "SENDING"
    const val SENT = "SENT"
    const val DELIVERED = "DELIVERED"
    const val REPLIED = "REPLIED"
    const val NO_REPLY = "NO_REPLY"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"

    val PENDING = listOf(SENDING, SENT, DELIVERED)
}

/** Отправка команды и её судьба: отправлено → доставлено → ответ / нет ответа. */
@Entity(tableName = "runs", indices = [Index("profileId"), Index("state")])
data class CommandRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val code: String,
    val name: String,
    val smsText: String,
    /** CommandKind.name */
    val kind: String,
    val control: Boolean,
    val createdAt: Long,
    val sentAt: Long? = null,
    val deliveredAt: Long? = null,
    val replyAt: Long? = null,
    val replySmsId: Long? = null,
    val replyText: String = "",
    /** Конец окна ожидания ответа. */
    val deadline: Long,
    val state: String,
    val partsTotal: Int = 1,
    val partsSent: Int = 0,
    val partsDelivered: Int = 0,
    val error: String = "",
    val emulated: Boolean = false,
    /** Для 00591: до какого времени SMS считаются частью сеанса обновления. */
    val sessionUntil: Long = 0,
    val fwBefore: String = "",
    val fwAfter: String = ""
)

/** Значение состояния со временем получения. */
@Entity(tableName = "obs", indices = [Index(value = ["profileId", "obsKey", "timestamp"])])
data class ObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    /** ObsKey.name */
    val obsKey: String,
    val text: String,
    val num: Double? = null,
    val timestamp: Long,
    val smsId: Long? = null,
    val emulated: Boolean = false
)

object BalanceType {
    /** Баланс, полученный от модуля (ответ на USSD или оповещение). */
    const val REPORTED = "REPORTED"
    /** Пополнение — прибавляется к оценке. */
    const val TOPUP = "TOPUP"
    /** Баланс, введённый вручную. */
    const val MANUAL = "MANUAL"
}

@Entity(tableName = "balance", indices = [Index("profileId")])
data class BalanceEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val type: String,
    val amount: Double,
    val timestamp: Long,
    val smsId: Long? = null,
    val emulated: Boolean = false
)
