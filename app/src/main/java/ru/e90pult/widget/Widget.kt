package ru.e90pult.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.widget.RemoteViews
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.e90pult.App
import ru.e90pult.R
import ru.e90pult.core.CommandDef
import ru.e90pult.core.Describe
import ru.e90pult.core.ObsKey
import ru.e90pult.core.Rules
import ru.e90pult.data.AppDatabase
import ru.e90pult.data.CommandRepo
import ru.e90pult.data.Prefs
import ru.e90pult.engine.Engine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Виджет: 2–4 кнопки и последний статус. Опасные и критичные команды с виджета не отправляются,
 * «Стоп» тоже: вне приложения не определить, едет ли машина.
 */
class PultWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        val g = App.graph(context)
        g.scope.launch {
            try {
                g.widgets.updateNow()
            } finally {
                pending.finish()
            }
        }
    }
}

class WidgetUpdater(
    private val context: Context,
    private val db: AppDatabase,
    private val prefs: Prefs,
    private val commands: CommandRepo,
    private val scope: CoroutineScope
) {
    companion object {
        const val EXTRA_CODE = "code"
        private val BUTTON_IDS = intArrayOf(R.id.w_btn1, R.id.w_btn2, R.id.w_btn3, R.id.w_btn4)

        /** Можно ли поставить команду на виджет. «Стоп» запрещён в движении, а с виджета движение не определить. */
        fun allowedOnWidget(d: CommandDef): Boolean =
            !d.dangerous && !d.critical && d.notApplicable == null && d.params.isEmpty() && d.code != Rules.STOP_CODE

        fun buttonColor(d: CommandDef): Int = when (d.code) {
            "11" -> R.drawable.wbtn_green
            "10" -> R.drawable.wbtn_blue
            "21" -> R.drawable.wbtn_orange
            "20" -> R.drawable.wbtn_red
            else -> R.drawable.wbtn_gray
        }

        fun shortName(d: CommandDef): String = when (d.code) {
            "11" -> "Охрана"
            "10" -> "Снять"
            "21" -> "Запуск"
            "20" -> "Стоп"
            "09" -> "Статус"
            "00551" -> "Связь"
            "41" -> "Состояние"
            else -> d.name
        }
    }

    fun requestUpdate() {
        scope.launch { updateNow() }
    }

    /**
     * Перерисовывать виджет при смене эмулятора, активного профиля, кнопок и при правке профилей —
     * чтобы он не показывал «эмуляцию» или чужую машину после переключения.
     */
    fun observe() {
        scope.launch {
            val settings = prefs.state
                .map { listOf(it.emulator, it.activeProfileId, it.widgetCodes, it.widgetConfirm) }
                .distinctUntilChanged()
            combine(settings, db.profiles().all()) { a, b -> a to b }.collectLatest { updateNow() }
        }
    }

    suspend fun updateNow() {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, PultWidgetProvider::class.java))
        if (ids == null || ids.isEmpty()) return
        val s = prefs.value
        val profile = db.profiles().get(s.activeProfileId) ?: db.profiles().allNow().firstOrNull()
        val views = RemoteViews(context.packageName, R.layout.widget_pult)
        val open = PendingIntent.getActivity(
            context, 1,
            Intent(context, ru.e90pult.ui.MainActivity::class.java).setData(Uri.parse("e90pult://widget/open")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setOnClickPendingIntent(R.id.w_head, open)
        if (profile == null) {
            views.setTextViewText(R.id.w_title, context.getString(R.string.app_name))
            views.setTextViewText(R.id.w_state, "Нет профиля")
            views.setTextViewText(R.id.w_time, "Откройте приложение")
            BUTTON_IDS.forEach { views.setViewVisibility(it, android.view.View.GONE) }
            manager.updateAppWidget(ids, views)
            return
        }
        val latest = db.obs().latest(profile.id, s.emulator).first().associateBy { it.obsKey }
        val armed = latest[ObsKey.ARMED.name]
        views.setTextViewText(R.id.w_title, profile.name + if (s.emulator) " · эмуляция" else "")
        views.setTextViewText(
            R.id.w_state,
            when (armed?.num) {
                1.0 -> "Охрана включена"
                0.0 -> "Охрана выключена"
                else -> "Охрана: нет данных"
            }
        )
        views.setTextColor(
            R.id.w_state,
            when (armed?.num) {
                1.0 -> 0xFF33D17A.toInt()
                0.0 -> 0xFFFFB020.toInt()
                else -> 0xFF9AA7BD.toInt()
            }
        )
        val newest = latest.values.maxByOrNull { it.timestamp }
        val gsm = latest[ObsKey.GSM_LEVEL.name]?.text
        val bat = latest[ObsKey.BATTERY.name]?.num?.let { Describe.volts(it) + " В" }
        val line = listOfNotNull(
            newest?.let { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(Date(it.timestamp)) },
            gsm?.let { "GSM $it" },
            bat
        ).joinToString(" · ")
        views.setTextViewText(R.id.w_time, line.ifEmpty { "Статус ещё не получен" })

        val defs = commands.forProfile(profile).associateBy { it.code }
        val chosen = s.widgetCodes.mapNotNull { defs[it] }.filter { allowedOnWidget(it) }.take(4)
        BUTTON_IDS.forEachIndexed { i, id ->
            val d = chosen.getOrNull(i)
            if (d == null) {
                views.setViewVisibility(id, android.view.View.GONE)
            } else {
                views.setViewVisibility(id, android.view.View.VISIBLE)
                views.setTextViewText(id, shortName(d))
                views.setInt(id, "setBackgroundResource", buttonColor(d))
                views.setOnClickPendingIntent(id, actionIntent(d, s.widgetConfirm && d.control))
            }
        }
        withContext(Dispatchers.Main) { manager.updateAppWidget(ids, views) }
    }

    private fun actionIntent(d: CommandDef, confirm: Boolean): PendingIntent {
        val data = Uri.parse("e90pult://widget/" + Uri.encode(d.code))
        return if (confirm) {
            val i = Intent(context, WidgetConfirmActivity::class.java)
                .setData(data)
                .putExtra(EXTRA_CODE, d.code)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            PendingIntent.getActivity(context, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else {
            val i = Intent(context, WidgetActionReceiver::class.java).setData(data).putExtra(EXTRA_CODE, d.code)
            PendingIntent.getBroadcast(context, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}

/** Нажатие кнопки виджета без подтверждения. */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getStringExtra(WidgetUpdater.EXTRA_CODE) ?: return
        val pending = goAsync()
        val g = App.graph(context)
        g.scope.launch {
            try {
                val msg = sendFromWidget(context, code)
                withContext(Dispatchers.Main) { Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** Пауза между отправками одной и той же команды с виджета (двойное нажатие, повторное подтверждение). */
        private const val REPEAT_GUARD_MS = 5_000L
        private val lastSent = HashMap<String, Long>()

        /** true — команду с этим кодом можно отправить сейчас (и время отправки запоминается). */
        private fun takeSlot(code: String): Boolean = synchronized(lastSent) {
            val now = SystemClock.elapsedRealtime()
            val prev = lastSent[code]
            if (prev != null && now - prev < REPEAT_GUARD_MS) return false
            lastSent[code] = now
            true
        }

        /** Отправка с виджета; опасные, критичные команды и «Стоп» отклоняются. */
        suspend fun sendFromWidget(context: Context, code: String): String {
            val g = App.graph(context)
            val pid = g.prefs.value.activeProfileId
            val profile = g.db.profiles().get(pid) ?: g.db.profiles().allNow().firstOrNull()
                ?: return "Нет профиля машины"
            val def = g.commands.find(profile, code) ?: return "Команда $code не найдена в таблице"
            if (!WidgetUpdater.allowedOnWidget(def)) return "Эта команда с виджета не отправляется — откройте приложение"
            if (!takeSlot(code)) return "«${def.name}» уже отправлена — дождитесь ответа"
            return when (val r = g.engine.send(profile.id, def)) {
                is Engine.SendResult.Ok -> "Отправлено: ${def.name}" + (r.warning?.let { "\n$it" } ?: "")
                is Engine.SendResult.Error -> r.message
            }
        }
    }
}
