package ru.e90pult.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.e90pult.core.Describe
import ru.e90pult.core.EmuMode
import ru.e90pult.core.FwVersion
import ru.e90pult.core.ResponseParser
import ru.e90pult.safety.BiometricGate
import ru.e90pult.widget.WidgetUpdater

@Composable
fun SettingsScreen(state: AppState, live: Live) {
    val g = state.graph
    val context = LocalContext.current
    val s = live.settings
    var timeout by remember { mutableStateOf(s.replyTimeoutMin.toString()) }
    var speed by remember { mutableStateOf(s.speedThresholdKmh.toString()) }
    var token by remember { mutableStateOf(s.openCellIdToken) }
    var setPin by remember { mutableStateOf(false) }
    var askRemovePin by remember { mutableStateOf(false) }
    /** Ослабить защиту можно только в открытом «Критичном» (если ПИН задан) и на стоянке. Проверка — в момент нажатия. */
    fun weaken(action: () -> Unit) {
        when {
            live.moving || live.inCar -> state.info = InfoMessage("Недоступно", "Ослабить защиту можно только на стоянке.")
            state.protectedEditAllowed(live, allowWithoutPin = true) -> action()
            else -> state.unlockCritical { ok -> if (ok && state.protectedEditAllowed(live)) action() }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        ScreenHeader("Настройки", onBack = { state.back() })

        SectionTitle("Ожидание ответа")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field("Сколько ждать ответ, минут (1–60)", timeout, { timeout = it.filter { c -> c.isDigit() }.take(2) },
                Modifier.weight(1f), keyboard = KeyboardType.Number)
            androidx.compose.material3.TextButton(onClick = {
                val v = timeout.toIntOrNull()?.coerceIn(1, 60) ?: 5
                timeout = v.toString()
                g.prefs.update { it.copy(replyTimeoutMin = v) }
                state.toast("Сохранено")
            }) { Text("OK") }
        }
        Text("Управляющие команды автоматически не переотправляются.", color = Pal.TextDim, fontSize = 12.sp)

        SectionTitle("Защита в движении")
        CardBox {
            SwitchRow("По скорости GPS телефона", "Только пока приложение на экране", s.motionGps) { on ->
                if (on) {
                    g.prefs.update { it.copy(motionGps = true) }
                    state.withPermissions(
                        Perms.LOCATION,
                        "Геолокация нужна, чтобы по скорости телефона понять, что машина едет, и закрыть опасные команды."
                    ) { g.safety.restart() }
                } else {
                    weaken {
                        g.prefs.update { it.copy(motionGps = false) }
                        g.safety.restart()
                    }
                }
            }
            SwitchRow("По Bluetooth машины", "Подключён к магнитоле — значит, телефон в машине", s.motionBt) { on ->
                if (on) {
                    g.prefs.update { it.copy(motionBt = true) }
                    g.safety.restart()
                } else {
                    weaken {
                        g.prefs.update { it.copy(motionBt = false) }
                        g.safety.restart()
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Field("Порог скорости, км/ч", speed, { speed = it.filter { c -> c.isDigit() }.take(2) }, Modifier.weight(1f), keyboard = KeyboardType.Number)
                androidx.compose.material3.TextButton(onClick = {
                    val v = speed.toIntOrNull()?.coerceIn(3, 60) ?: 10
                    val apply = {
                        speed = v.toString()
                        g.prefs.update { it.copy(speedThresholdKmh = v) }
                        state.toast("Сохранено")
                    }
                    if (v > s.speedThresholdKmh) weaken(apply) else apply()
                }) { Text("OK") }
            }
        }

        SectionTitle("ПИН-код и отпечаток")
        CardBox {
            if (g.pin.hasPin()) {
                NavRow(ru.e90pult.R.drawable.ic_key, "Сменить ПИН-код") { weaken { setPin = true } }
                NavRow(ru.e90pult.R.drawable.ic_delete, "Удалить ПИН-код", "Раздел «Критичное» станет недоступен") { weaken { askRemovePin = true } }
            } else {
                NavRow(ru.e90pult.R.drawable.ic_key, "Задать ПИН-код", "Нужен для раздела «Критичное»") { setPin = true }
            }
            if (BiometricGate.available(context)) {
                SwitchRow("Вход по отпечатку", "Вместо ПИН-кода, если система позволяет", s.biometric) { on ->
                    // Включение отпечатка добавляет второй способ входа — это ослабление защиты.
                    if (on) weaken { g.prefs.update { it.copy(biometric = true) } }
                    else g.prefs.update { it.copy(biometric = false) }
                }
            } else {
                Text(
                    if (Build.VERSION.SDK_INT < 28) "Отпечаток поддерживается с Android 9." else "Отпечаток в системе не настроен.",
                    color = Pal.TextDim, fontSize = 12.sp
                )
            }
        }

        SectionTitle("Виджет рабочего стола")
        CardBox {
            Text(
                "Кнопки (2–4). Опасные и критичные команды на виджет не ставятся. «Стоп» тоже: с виджета не определить, едет ли машина.",
                color = Pal.TextDim, fontSize = 12.sp
            )
            val allowed = live.defs.filter { WidgetUpdater.allowedOnWidget(it) }
            allowed.forEach { d ->
                val checked = d.code in s.widgetCodes
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = { on ->
                        val next = if (on) s.widgetCodes + d.code else s.widgetCodes - d.code
                        when {
                            next.size > 4 -> state.toast("Не больше 4 кнопок")
                            next.size < 2 -> state.toast("Нужно хотя бы 2 кнопки")
                            else -> g.prefs.update { it.copy(widgetCodes = next) }
                        }
                    })
                    Text("${d.name} (${d.code})", color = Pal.Text, fontSize = 14.sp)
                }
            }
            SwitchRow("Подтверждать управляющие команды", "Охрана, снятие, запуск — через окно подтверждения", s.widgetConfirm) { on ->
                if (on) {
                    g.prefs.update { it.copy(widgetConfirm = true) }
                } else {
                    weaken { g.prefs.update { it.copy(widgetConfirm = false) } }
                }
            }
        }

        SectionTitle("OpenCellID")
        Field("API-токен (необязательно)", token, { token = it.trim().take(64) }, password = true,
            supporting = "Для приблизительных координат соты. Интернет нужен только для этого запроса.")
        androidx.compose.material3.TextButton(onClick = {
            g.prefs.update { it.copy(openCellIdToken = token) }
            state.toast("Токен сохранён")
        }) { Text("Сохранить токен") }

        SectionTitle("Уведомления и фон")
        CardBox {
            val notifOk = g.notifier.canPost()
            Text(if (notifOk) "Уведомления разрешены" else "Уведомления запрещены", color = if (notifOk) Pal.GreenText else Pal.Yellow)
            if (!notifOk && Perms.NOTIFY.isNotEmpty()) {
                SecondaryButton("Разрешить уведомления") {
                    state.withPermissions(Perms.NOTIFY, "Уведомления — о тревогах, ответах сигнализации и «нет ответа».") { }
                }
            }
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Досканировать входящие за сутки") {
                state.withPermissions(listOf(android.Manifest.permission.READ_SMS), "Чтение входящих — чтобы найти ответы, пришедшие, пока приложение было выгружено.") { res ->
                    if (res.values.all { it }) g.scope.launch {
                        g.engine.scanInbox()
                        withContext(Dispatchers.Main) { state.toast("Готово") }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Настройки экономии батареи") {
                try {
                    context.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: ActivityNotFoundException) {
                    state.toast("Откройте настройки батареи вручную")
                }
            }
            Text(
                "На Xiaomi, Huawei и подобных разрешите автозапуск и снимите ограничения батареи — иначе система может не будить приложение при входящих SMS.",
                color = Pal.TextDim, fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(16.dp))
    }
    if (setPin) SetPinDialog(state) { setPin = false }
    if (askRemovePin) {
        ConfirmDialog(
            title = "Удалить ПИН-код?",
            text = "Без ПИН-кода раздел «Критичное» не откроется.",
            confirmText = "Удалить",
            onConfirm = {
                askRemovePin = false
                g.pin.clearPin()
                state.lockCritical()
                state.toast("ПИН-код удалён")
            },
            onDismiss = { askRemovePin = false }
        )
    }
}

@Composable
fun DebugScreen(state: AppState, live: Live) {
    val g = state.graph
    val s = live.settings
    var delay by remember { mutableStateOf(s.emuDelaySec.toString()) }
    var fwAfter by remember { mutableStateOf(s.emuFwAfterUpdate) }
    var sample by remember { mutableStateOf("GSM:17(-)Команда не выполненаОхрана выкл.Замки откр.Откр.") }
    val modes = EmuMode.entries

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        ScreenHeader("Отладка", onBack = { state.back() })
        CardBox {
            SwitchRow(
                "Эмулятор ответов (без машины)",
                "SMS не отправляются; ответы строятся по образцам из ТЗ и документации",
                s.emulator
            ) { on -> g.prefs.update { it.copy(emulator = on) } }
            Spacer(Modifier.height(8.dp))
            Text("Режим ответа", color = Pal.TextDim, fontSize = 12.sp)
            Segmented(modes.map { it.title }, modes.indexOfFirst { it.name == s.emuMode }.coerceAtLeast(0)) { i ->
                g.prefs.update { it.copy(emuMode = modes[i].name) }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Field("Задержка ответа, с", delay, { delay = it.filter { c -> c.isDigit() }.take(3) }, Modifier.weight(1f), keyboard = KeyboardType.Number)
                androidx.compose.material3.TextButton(onClick = {
                    g.prefs.update { it.copy(emuDelaySec = delay.toIntOrNull()?.coerceIn(1, 120) ?: 5) }
                }) { Text("OK") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Field("Версия после эмулированного 00591", fwAfter, { fwAfter = it.uppercase().take(3) }, Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = {
                    if (FwVersion.parse(fwAfter) == null) state.toast("Формат: буква и цифра, например M8")
                    else g.prefs.update { it.copy(emuFwAfterUpdate = fwAfter) }
                }) { Text("OK") }
            }
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Эмулировать входящее без команды (событие)") {
                g.scope.launch { g.engine.emulateUnsolicited(s.activeProfileId) }
            }
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Очистить данные эмуляции") {
                g.scope.launch {
                    g.engine.clearEmulated()
                    withContext(Dispatchers.Main) { state.toast("Данные эмуляции удалены") }
                }
            }
        }
        SectionTitle("Проверка разбора SMS")
        Field("Текст SMS", sample, { sample = it }, singleLine = false)
        val p = remember(sample) { ResponseParser.parse(sample) }
        CardBox {
            Text(Describe.short(p), color = Pal.GreenText, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            p.fields.forEach { (k, v) -> Text("$k → $v", color = Pal.Text, fontSize = 13.sp) }
            if (p.unknown.isNotEmpty()) Text("Нераспознано: " + p.unknown.joinToString(" | "), color = Pal.Yellow, fontSize = 13.sp)
        }
        Spacer(Modifier.height(16.dp))
    }
}
