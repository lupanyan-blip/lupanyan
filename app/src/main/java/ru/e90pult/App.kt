package ru.e90pult

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import ru.e90pult.core.CellLookup
import ru.e90pult.core.CellResult
import ru.e90pult.data.AppDatabase
import ru.e90pult.data.CommandRepo
import ru.e90pult.data.Prefs
import ru.e90pult.engine.Engine
import ru.e90pult.engine.Notifier
import ru.e90pult.safety.PinStore
import ru.e90pult.safety.SafetyMonitor
import ru.e90pult.sms.InboxScanner
import ru.e90pult.sms.SmsGateway
import ru.e90pult.widget.WidgetUpdater
import java.net.HttpURLConnection
import java.net.URL

class App : Application() {

    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        graph.notifier.createChannels()
        graph.engine.onAppStart()
        graph.widgets.observe()
    }

    companion object {
        fun graph(context: Context): Graph = (context.applicationContext as App).graph
    }
}

/** Ручная сборка зависимостей. */
class Graph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = AppDatabase.build(app)
    val prefs = Prefs(app)
    val notifier = Notifier(app)
    val commands = CommandRepo(app)
    val gateway = SmsGateway(app)
    val inbox = InboxScanner(app)
    val widgets = WidgetUpdater(app, db, prefs, commands, scope)
    val safety = SafetyMonitor(app, prefs)
    val pin = PinStore(prefs)
    val engine = Engine(app, db, prefs, notifier, commands, gateway, inbox, widgets, safety, pin, scope)

    /** Запрос координат соты в OpenCellID. */
    suspend fun lookupCell(mcc: Int, mnc: Int, lacHex: String, ciHex: String): CellResult = withContext(Dispatchers.IO) {
        val token = prefs.value.openCellIdToken
        if (token.isBlank()) return@withContext CellResult.Error("Задайте API-токен OpenCellID в настройках")
        val url = CellLookup.url(token, mcc, mnc, lacHex, ciHex)
            ?: return@withContext CellResult.Error("LAC/CI не в шестнадцатеричном виде")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            CellLookup.parse(body, code)
        } catch (e: Exception) {
            CellResult.Error("Нет связи с OpenCellID: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            conn.disconnect()
        }
    }
}
