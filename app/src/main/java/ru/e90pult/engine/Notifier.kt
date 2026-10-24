package ru.e90pult.engine

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import ru.e90pult.R
import ru.e90pult.ui.MainActivity

class Notifier(private val context: Context) {

    companion object {
        const val CH_EVENTS = "events"
        const val CH_REPLIES = "replies"
        const val CH_WARN = "warnings"

        const val ID_BALANCE = 1
        const val ID_LIMIT = 2
        const val ID_UPDATE = 3
        const val ID_FW_NEWS = 4
        const val ID_REPLY_BASE = 100_000
        const val ID_WARN_BASE = 200_000
        const val ID_EVENT_BASE = 300_000
    }

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_EVENTS, "События и тревоги", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "SMS от сигнализации без отправленной команды"
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REPLIES, "Ответы на команды", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Ответы сигнализации на отправленные команды"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_WARN, "Предупреждения", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Нет ответа, ошибка отправки, баланс, лимит SMS"
            }
        )
    }

    fun canPost(): Boolean = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** [open] — что открыть по нажатию: "journal", "run:<id>", "balance", "firmware". */
    fun post(id: Int, channel: String, title: String, text: String, open: String = "journal") {
        if (!canPost()) return
        // Свой адрес у каждого уведомления — PendingIntent не совпадёт с виджетом и другими уведомлениями.
        val intent = Intent(context, MainActivity::class.java)
            .setData(Uri.parse("e90pult://notification/$id"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_OPEN, open)
        val pi = PendingIntent.getActivity(
            context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_car)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
        if (channel == CH_EVENTS) builder.setCategory(Notification.CATEGORY_MESSAGE)
        try {
            context.getSystemService(NotificationManager::class.java)?.notify(id, builder.build())
        } catch (e: SecurityException) {
            // Разрешение отозвано — уведомление пропускаем.
        }
    }

    /** Снять уведомление, ставшее неактуальным (например, «нет ответа», когда ответ всё-таки пришёл). */
    fun cancel(id: Int) {
        try {
            context.getSystemService(NotificationManager::class.java)?.cancel(id)
        } catch (e: Exception) {
            // Нечего снимать.
        }
    }
}
