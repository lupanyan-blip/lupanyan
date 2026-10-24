package ru.e90pult.sms

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsMessage
import kotlinx.coroutines.launch
import ru.e90pult.App

enum class Delivery { OK, PENDING, FAILED }

/** Отчёты «отправлено» и «доставлено». */
class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val runId = intent.getLongExtra(SmsGateway.EXTRA_RUN, -1)
        val smsId = intent.getLongExtra(SmsGateway.EXTRA_SMS, -1)
        if (runId < 0 || smsId < 0) return
        val code = resultCode
        val action = intent.action
        val pending = goAsync()
        val g = App.graph(context)
        g.scope.launch {
            try {
                when (action) {
                    SmsGateway.ACTION_SENT ->
                        g.engine.onSent(runId, smsId, code == Activity.RESULT_OK, sendError(code))
                    SmsGateway.ACTION_DELIVERED ->
                        g.engine.onDelivered(runId, smsId, deliveryStatus(intent, code))
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        fun sendError(code: Int): String = when (code) {
            Activity.RESULT_OK -> ""
            1 -> "общая ошибка (нет денег на SIM телефона, сбой сети)"
            2 -> "радиомодуль выключен (режим полёта)"
            3 -> "ошибка формирования SMS"
            4 -> "нет сети"
            5 -> "превышен лимит отправки SMS системы"
            6 -> "номер запрещён настройкой FDN"
            7, 8 -> "отправка на этот номер запрещена"
            else -> "код $code"
        }

        fun deliveryStatus(intent: Intent, code: Int): Delivery {
            val pdu = intent.getByteArrayExtra("pdu")
            val format = intent.getStringExtra("format")
            if (pdu == null) return if (code == Activity.RESULT_OK) Delivery.OK else Delivery.FAILED
            val msg = try {
                SmsMessage.createFromPdu(pdu, format)
            } catch (e: Exception) {
                null
            } ?: return if (code == Activity.RESULT_OK) Delivery.OK else Delivery.FAILED
            val st = msg.status
            if (format == "3gpp2") return if (st == 0) Delivery.OK else Delivery.FAILED
            // TP-Status: 0x00–0x1F — доставлено, 0x20–0x3F — центр ещё пытается, 0x40 и выше — не доставлено.
            return when {
                st < 0x20 -> Delivery.OK
                st < 0x40 -> Delivery.PENDING
                else -> Delivery.FAILED
            }
        }
    }
}

/** Приём SMS. Многочастные SMS система передаёт одним broadcast — части склеиваются по порядку. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
        if (messages.isEmpty()) return
        val receivedAt = System.currentTimeMillis()
        val grouped = messages.groupBy { it.displayOriginatingAddress ?: it.originatingAddress ?: "" }
        val pending = goAsync()
        val g = App.graph(context)
        g.scope.launch {
            try {
                for ((from, parts) in grouped) {
                    val body = parts.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }
                    val sc = parts.minOf { it.timestampMillis }
                    g.engine.onIncoming(from, body, parts.size, receivedAt, sc, "RECEIVER")
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/** Окончание ожидания ответа и сеанса обновления ПО. */
class TimeoutReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val runId = intent.getLongExtra(EXTRA_RUN, -1)
        if (runId < 0) return
        val type = intent.getStringExtra(EXTRA_TYPE) ?: TYPE_REPLY
        val pending = goAsync()
        val g = App.graph(context)
        g.scope.launch {
            try {
                if (type == TYPE_SESSION) g.engine.onSessionTimeout(runId) else g.engine.onTimeout(runId)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_RUN = "run"
        const val EXTRA_TYPE = "type"
        const val TYPE_REPLY = "reply"
        const val TYPE_SESSION = "session"

        private fun pi(context: Context, runId: Long, type: String): PendingIntent {
            val intent = Intent(context, TimeoutReceiver::class.java)
                .setData(Uri.parse("e90pult://timeout/$type/$runId"))
                .putExtra(EXTRA_RUN, runId)
                .putExtra(EXTRA_TYPE, type)
            return PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /** Неточный будильник, разрешённый и в режиме сна: точность — до нескольких минут. */
        fun schedule(context: Context, runId: Long, at: Long, type: String) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(context, runId, type))
        }

        fun cancel(context: Context, runId: Long, type: String) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            am.cancel(pi(context, runId, type))
        }
    }
}

/**
 * После перезагрузки: сначала досканировать входящие (ответы, пришедшие, пока телефон был выключен),
 * затем просроченные ожидания → «нет ответа», будильники — заново.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val g = App.graph(context)
        g.scope.launch {
            try {
                g.engine.scanInbox()
                g.engine.reconcile()
                g.widgets.updateNow()
            } finally {
                pending.finish()
            }
        }
    }
}

/** Чтение входящих из системной базы SMS (нужно READ_SMS). */
class InboxScanner(private val context: Context) {

    data class Row(val address: String, val body: String, val date: Long, val dateSent: Long)

    fun canRead(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.READ_SMS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    fun read(since: Long): List<Row> {
        if (!canRead()) return emptyList()
        val out = mutableListOf<Row>()
        val projection = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT)
        try {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                projection,
                "${Telephony.Sms.DATE} >= ?",
                arrayOf(since.toString()),
                "${Telephony.Sms.DATE} ASC"
            )?.use { c ->
                val iA = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val iB = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val iD = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val iS = c.getColumnIndexOrThrow(Telephony.Sms.DATE_SENT)
                while (c.moveToNext()) {
                    out += Row(c.getString(iA) ?: "", c.getString(iB) ?: "", c.getLong(iD), c.getLong(iS))
                }
            }
        } catch (e: Exception) {
            // Нет доступа или база недоступна — досканирование просто пропускается.
        }
        return out
    }
}
