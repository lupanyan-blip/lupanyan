package ru.e90pult.sms

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager

data class SimInfo(val subId: Int, val slot: Int, val label: String, val carrier: String) {
    val title: String get() = "SIM ${slot + 1}: $label" + if (carrier.isNotBlank() && carrier != label) " ($carrier)" else ""
}

/** Отправка SMS через SmsManager выбранной SIM; отчёты об отправке и доставке приходят в [SmsStatusReceiver]. */
class SmsGateway(private val context: Context) {

    companion object {
        const val ACTION_SENT = "ru.e90pult.action.SMS_SENT"
        const val ACTION_DELIVERED = "ru.e90pult.action.SMS_DELIVERED"
        const val EXTRA_RUN = "run"
        const val EXTRA_SMS = "sms"
        const val EXTRA_PART = "part"

        /** Число частей SMS для текста (как посчитает телефон). */
        fun partsOf(text: String): Int = try {
            SmsMessage.calculateLength(text, false)[0].coerceAtLeast(1)
        } catch (e: Exception) {
            1
        }
    }

    fun hasSendPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    fun hasPhoneStatePermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    /** SIM-карты телефона (нужно разрешение READ_PHONE_STATE). */
    fun sims(): List<SimInfo> {
        if (!hasPhoneStatePermission()) return emptyList()
        val sm = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        return try {
            val list = sm.activeSubscriptionInfoList ?: return emptyList()
            list.map {
                SimInfo(
                    subId = it.subscriptionId,
                    slot = it.simSlotIndex,
                    label = it.displayName?.toString() ?: "SIM ${it.simSlotIndex + 1}",
                    carrier = it.carrierName?.toString() ?: ""
                )
            }.sortedBy { it.slot }
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    private fun manager(subId: Int): SmsManager {
        return if (Build.VERSION.SDK_INT >= 31) {
            val base = context.getSystemService(SmsManager::class.java)
            if (subId >= 0) base.createForSubscriptionId(subId) else base
        } else {
            @Suppress("DEPRECATION")
            if (subId >= 0) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
        }
    }

    private fun statusIntent(action: String, runId: Long, smsId: Long, part: Int): PendingIntent {
        val intent = Intent(context, SmsStatusReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("e90pult://sms/$smsId/$part/${action.substringAfterLast('.')}"))
            .putExtra(EXTRA_RUN, runId)
            .putExtra(EXTRA_SMS, smsId)
            .putExtra(EXTRA_PART, part)
        // Изменяемый PendingIntent нужен, чтобы система добавила PDU отчёта о доставке. Intent явный.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    /**
     * Отправляет текст на номер. Возвращает число частей.
     * Исключения (нет разрешения, неверный номер) пробрасываются вызывающему.
     */
    fun send(runId: Long, smsId: Long, phone: String, text: String, subId: Int): Int {
        val mgr = manager(subId)
        val parts = mgr.divideMessage(text)
        if (parts.size <= 1) {
            mgr.sendTextMessage(
                phone, null, text,
                statusIntent(ACTION_SENT, runId, smsId, 0),
                statusIntent(ACTION_DELIVERED, runId, smsId, 0)
            )
            return 1
        }
        val sent = ArrayList<PendingIntent>(parts.size)
        val delivered = ArrayList<PendingIntent>(parts.size)
        for (i in parts.indices) {
            sent += statusIntent(ACTION_SENT, runId, smsId, i)
            delivered += statusIntent(ACTION_DELIVERED, runId, smsId, i)
        }
        mgr.sendMultipartTextMessage(phone, null, parts, sent, delivered)
        return parts.size
    }
}
