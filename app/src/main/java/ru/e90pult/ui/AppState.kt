package ru.e90pult.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.e90pult.Graph
import ru.e90pult.R
import ru.e90pult.core.Availability
import ru.e90pult.core.CommandContext
import ru.e90pult.core.CommandDef
import ru.e90pult.core.FwVersion
import ru.e90pult.core.ObsKey
import ru.e90pult.core.Rules
import ru.e90pult.data.ObservationEntity
import ru.e90pult.data.ProfileEntity
import ru.e90pult.data.Settings
import ru.e90pult.engine.Engine
import ru.e90pult.safety.BiometricGate
import ru.e90pult.safety.SafetyState

sealed class Screen {
    object Home : Screen()
    object Journal : Screen()
    object Charts : Screen()
    object Map : Screen()
    object More : Screen()
    data class Run(val runId: Long) : Screen()
    object State : Screen()
    object Service : Screen()
    object Critical : Screen()
    object Balance : Screen()
    object Firmware : Screen()
    object Commands : Screen()
    object CommandsEditor : Screen()
    object Profiles : Screen()
    /** id = 0 — новый профиль. */
    data class ProfileEdit(val id: Long) : Screen()
    object Counter : Screen()
    object Settings : Screen()
    object Debug : Screen()
    object About : Screen()
}

enum class Tab(val title: String, val icon: Int, val root: Screen) {
    HOME("Главная", R.drawable.ic_home, Screen.Home),
    JOURNAL("Журнал", R.drawable.ic_list, Screen.Journal),
    CHARTS("Графики", R.drawable.ic_chart, Screen.Charts),
    MAP("Карта", R.drawable.ic_place, Screen.Map),
    MORE("Ещё", R.drawable.ic_more, Screen.More)
}

object Perms {
    val SMS = listOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
    val PHONE = listOf(Manifest.permission.READ_PHONE_STATE)
    val NOTIFY = if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
    val LOCATION = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    val BT = if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()
}

/** Способ подтверждения команды в диалоге. */
enum class ConfirmMode { NORMAL, CRITICAL, EMERGENCY }

data class CommandRequest(val def: CommandDef, val mode: ConfirmMode)
data class PermissionAsk(val perms: List<String>, val why: String, val onAnswer: (Boolean) -> Unit)
data class InfoMessage(val title: String, val text: String)
data class QuickConfirm(val def: CommandDef, val text: String)
data class ConfirmRequest(val title: String, val text: String, val confirmText: String, val onConfirm: () -> Unit)

/** Навигация, диалоги, разрешения, отправка команд из интерфейса. */
class AppState(
    val graph: Graph,
    val activity: ComponentActivity,
    private val uiScope: CoroutineScope
) {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    var tab by mutableStateOf(Tab.HOME)
        private set
    val current: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1 || tab != Tab.HOME

    val snackbar = SnackbarHostState()
    var commandRequest by mutableStateOf<CommandRequest?>(null)
    var permissionAsk by mutableStateOf<PermissionAsk?>(null)
    var info by mutableStateOf<InfoMessage?>(null)
    var quickConfirm by mutableStateOf<QuickConfirm?>(null)
    var confirm by mutableStateOf<ConfirmRequest?>(null)
    var pinPrompt by mutableStateOf<((Boolean) -> Unit)?>(null)

    var permissionLauncher: ((Array<String>) -> Unit)? = null
    private var permissionCallback: ((Map<String, Boolean>) -> Unit)? = null

    // ---------- Навигация ----------

    fun open(s: Screen) {
        stack.add(s)
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else if (tab != Tab.HOME) selectTab(Tab.HOME)
    }

    fun selectTab(t: Tab) {
        tab = t
        stack.clear()
        stack.add(t.root)
    }

    fun handleOpen(target: String) {
        when {
            target.startsWith("run:") -> target.removePrefix("run:").toLongOrNull()?.let {
                if (it > 0) {
                    selectTab(Tab.HOME)
                    open(Screen.Run(it))
                }
            }
            target == "balance" -> { selectTab(Tab.MORE); open(Screen.Balance) }
            target == "firmware" -> { selectTab(Tab.MORE); open(Screen.Firmware) }
            target == "counter" -> { selectTab(Tab.MORE); open(Screen.Counter) }
            else -> selectTab(Tab.JOURNAL)
        }
    }

    fun toast(msg: String) {
        uiScope.launch { snackbar.showSnackbar(msg) }
    }

    // ---------- Разрешения ----------

    fun granted(p: String): Boolean = activity.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun onPermissionResult(result: Map<String, Boolean>) {
        val cb = permissionCallback
        permissionCallback = null
        cb?.invoke(result)
    }

    /** Запрос разрешений в момент первой нужды — сначала объяснение, затем системный диалог. */
    fun withPermissions(perms: List<String>, why: String, onResult: (Map<String, Boolean>) -> Unit) {
        val missing = perms.filter { !granted(it) }
        if (missing.isEmpty()) {
            onResult(perms.associateWith { true })
            return
        }
        permissionAsk = PermissionAsk(missing, why) { agreed ->
            if (!agreed) {
                onResult(perms.associateWith { granted(it) })
            } else {
                permissionCallback = { _ -> onResult(perms.associateWith { granted(it) }) }
                val launcher = permissionLauncher
                if (launcher != null) launcher(missing.toTypedArray()) else onResult(perms.associateWith { granted(it) })
            }
        }
    }

    private fun ensureSendPermissions(onReady: () -> Unit) {
        if (graph.prefs.value.emulator) {
            onReady()
            return
        }
        withPermissions(
            Perms.SMS + Perms.NOTIFY,
            "SMS — отправка команд на номер сигнализации и приём её ответов. Чтение входящих — чтобы не терять " +
                "ответы, пришедшие, пока приложение было выгружено. Уведомления — о тревогах, ответах и «нет ответа»."
        ) { res ->
            if (res[Manifest.permission.SEND_SMS] == true) {
                if (res[Manifest.permission.RECEIVE_SMS] != true) toast("Без приёма SMS ответы не будут видны в приложении")
                onReady()
            } else {
                toast("Без разрешения на отправку SMS команды не отправить")
            }
        }
    }

    // ---------- Команды ----------

    /** Время последней отправки по каждой команде — для защиты от двойного нажатия. */
    private val lastSendAt = HashMap<String, Long>()

    /** Защита от двойного нажатия: та же команда в течение 3 секунд не отправляется повторно. */
    private fun duplicateTap(key: String): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        val prev = lastSendAt[key]
        if (prev != null && now - prev < 3_000) {
            toast("Команда уже отправлена")
            return true
        }
        lastSendAt.entries.removeAll { now - it.value > 60_000 }
        lastSendAt[key] = now
        return false
    }

    /**
     * Отправка; по результату — экран «Отправка команды». Работает в области приложения, а не экрана.
     * Все проверки (версия ПО, движение, Bluetooth, ПИН) движок повторяет перед отправкой.
     */
    fun send(def: CommandDef, params: Map<String, String> = emptyMap(), openRun: Boolean = true, emergency: Boolean = false) {
        if (duplicateTap("send:" + def.code + params.toSortedMap())) return
        ensureSendPermissions {
            val pid = graph.prefs.value.activeProfileId
            graph.scope.launch {
                val r = graph.engine.send(pid, def, params, emergency)
                withContext(Dispatchers.Main) {
                    when (r) {
                        is Engine.SendResult.Ok -> {
                            if (openRun) open(Screen.Run(r.runId))
                            r.warning?.let { toast(it) }
                        }
                        is Engine.SendResult.Error -> info = InfoMessage("Команда не отправлена", r.message)
                    }
                }
            }
        }
    }

    /** Повтор вручную тем же текстом SMS. */
    fun resend(runId: Long) {
        if (duplicateTap("resend:$runId")) return
        ensureSendPermissions {
            graph.scope.launch {
                val r = graph.engine.resend(runId)
                withContext(Dispatchers.Main) {
                    when (r) {
                        is Engine.SendResult.Ok -> {
                            back()
                            open(Screen.Run(r.runId))
                            r.warning?.let { toast(it) }
                        }
                        is Engine.SendResult.Error -> info = InfoMessage("Команда не отправлена", r.message)
                    }
                }
            }
        }
    }

    /** Нужно ли полное окно команды (параметры, удержание, ПИН), а не короткое подтверждение. */
    fun needsSheet(def: CommandDef): Boolean = def.dangerous || def.critical || def.params.isNotEmpty()

    /** Нажатие плитки главного экрана: недоступна — объяснить; управляющая — короткое подтверждение. */
    fun tap(def: CommandDef, live: Live) {
        when (val a = Rules.availability(def, live.ctx())) {
            is Availability.Disabled -> info = InfoMessage(def.name, a.reason)
            Availability.Enabled -> when {
                needsSheet(def) ->
                    commandRequest = CommandRequest(def, if (def.critical) ConfirmMode.CRITICAL else ConfirmMode.NORMAL)
                def.control -> quickConfirm = QuickConfirm(def, confirmText(def, live))
                else -> send(def)
            }
        }
    }

    /** Смена активного профиля — с подтверждением; в движении недоступна. */
    fun switchProfile(p: ProfileEntity, live: Live) {
        if (p.id == live.settings.activeProfileId) return
        if (live.moving) {
            info = InfoMessage("Недоступно в движении", "Сменить машину можно на стоянке.")
            return
        }
        val number = ru.e90pult.core.Phone.pretty(p.alarmPhone).ifEmpty { "номер не задан" }
        confirm = ConfirmRequest(
            title = "Сделать активной «${p.name}»?",
            text = "Команды будут уходить на номер сигнализации $number.",
            confirmText = "Переключить"
        ) {
            graph.prefs.update { it.copy(activeProfileId = p.id) }
            toast("Активный профиль: ${p.name}")
        }
    }

    fun confirmText(def: CommandDef, live: Live): String {
        val p = live.profile
        val text = if (p != null) {
            when (val b = graph.engine.preview(p, def, emptyMap())) {
                is ru.e90pult.core.Built.Ok -> b.text
                is ru.e90pult.core.Built.Error -> "?"
            }
        } else "?"
        val note = def.note?.let { "\n\n$it" } ?: ""
        val unverified = if (def.verified) "" else "\n\nКоманда не проверена на этой машине."
        return "Будет отправлено SMS «$text»." + note + unverified
    }

    /** Открыть диалог команды (параметры и подтверждение). */
    fun openCommand(def: CommandDef, mode: ConfirmMode = if (def.critical) ConfirmMode.CRITICAL else ConfirmMode.NORMAL) {
        commandRequest = CommandRequest(def, mode)
    }

    // ---------- «Критичное» ----------

    /** Проверка «прямо сейчас» — для обработчиков нажатий. На экранах — [rememberCriticalUnlocked]. */
    fun criticalUnlocked(): Boolean = graph.pin.isUnlocked()

    /**
     * Можно ли сейчас менять защищённое: машина стоит и раздел открыт ПИН-кодом.
     * [allowWithoutPin] — пока ПИН не задан (первый профиль, защита ещё не настроена).
     */
    fun protectedEditAllowed(live: Live, allowWithoutPin: Boolean = false): Boolean =
        !live.moving && !live.inCar && (criticalUnlocked() || (allowWithoutPin && !graph.pin.hasPin()))

    /** Открыть «Критичное»: отпечаток (если включён) или ПИН. */
    fun unlockCritical(onDone: (Boolean) -> Unit) {
        if (!graph.pin.hasPin()) {
            info = InfoMessage("Нет ПИН-кода", "Сначала задайте ПИН-код в разделе «Критичное» или в настройках.")
            onDone(false)
            return
        }
        val finish: (Boolean) -> Unit = { ok ->
            if (ok) graph.pin.unlock()
            onDone(ok)
        }
        val s = graph.prefs.value
        if (s.biometric && Build.VERSION.SDK_INT >= 28 && BiometricGate.available(activity)) {
            BiometricGate.prompt(
                activity, "Вход в «Критичное»",
                onOk = { finish(true) },
                onFallback = { pinPrompt = finish }
            )
        } else {
            pinPrompt = finish
        }
    }

    fun lockCritical() {
        graph.pin.lock()
    }
}

/** Открыт ли раздел «Критичное» — наблюдаемое значение, само закрывается по истечении срока. */
@Composable
fun rememberCriticalUnlocked(state: AppState): Boolean {
    val until by state.graph.pin.unlockedUntil.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(until) {
        now = System.currentTimeMillis()
        if (until > now) {
            delay(until - now + 50)
            now = System.currentTimeMillis()
        }
    }
    return until > now
}

/** Снимок данных для экранов: настройки, профиль, последние значения, безопасность. */
data class Live(
    val settings: Settings,
    val profile: ProfileEntity?,
    val latest: Map<String, ObservationEntity>,
    val safety: SafetyState,
    val fw: FwVersion?,
    val defs: List<CommandDef>,
    val hasPassword: Boolean
) {
    val emu: Boolean get() = settings.emulator
    val moving: Boolean get() = settings.motionGps && safety.moving
    val inCar: Boolean get() = settings.motionBt && safety.inCar

    fun ctx(): CommandContext = CommandContext(fw, profile?.passwordMode ?: false, hasPassword, moving, inCar)

    fun def(code: String): CommandDef? = defs.firstOrNull { it.code == code }

    fun obs(key: ObsKey): ObservationEntity? = latest[key.name]
}

@Composable
fun rememberLive(state: AppState): Live {
    val g = state.graph
    val settings by g.prefs.state.collectAsState()
    val pid = settings.activeProfileId
    val profileFlow = remember(pid) { g.db.profiles().observe(pid) }
    val profile by profileFlow.collectAsState(initial = null)
    val latestFlow = remember(pid, settings.emulator) { g.db.obs().latest(pid, settings.emulator) }
    val latestList by latestFlow.collectAsState(initial = emptyList())
    val safety by g.safety.state.collectAsState()
    val latest = remember(latestList) {
        latestList.groupBy { it.obsKey }.mapValues { (_, v) -> v.maxBy { it.timestamp } }
    }
    val p = profile
    val fw = FwVersion.parse(latest[ObsKey.FW.name]?.text) ?: FwVersion.parse(p?.moduleFw)
    val defs = remember(p?.commandsJson) { g.commands.forProfile(p) }
    val hasPw = remember(p?.passwordEnc) { p != null && g.engine.password(p) != null }
    return Live(settings, p, latest, safety, fw, defs, hasPw)
}
