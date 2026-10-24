package ru.e90pult.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.e90pult.core.Availability
import ru.e90pult.core.BlockKind
import ru.e90pult.core.Built
import ru.e90pult.core.CommandKind
import ru.e90pult.core.ObsKey
import ru.e90pult.core.ParamType
import ru.e90pult.core.Phone
import ru.e90pult.core.Rules
import ru.e90pult.safety.PinStore

/** Все диалоги приложения: команда, разрешение, сообщение, ПИН, быстрое подтверждение. */
@Composable
fun AppDialogs(state: AppState, live: Live) {
    state.commandRequest?.let { req ->
        CommandSheet(state, live, req) { state.commandRequest = null }
    }
    state.quickConfirm?.let { q ->
        // Условия проверяются заново, пока окно открыто: машина могла тронуться.
        val a = Rules.availability(q.def, live.ctx())
        val blocked = a as? Availability.Disabled
        ConfirmDialog(
            title = "${q.def.name} (${q.def.code})",
            text = q.text + (blocked?.let { "\n\nСейчас недоступно: ${it.reason}" } ?: ""),
            confirmEnabled = blocked == null,
            onConfirm = {
                state.quickConfirm = null
                when (val now = Rules.availability(q.def, live.ctx())) {
                    is Availability.Disabled -> state.info = InfoMessage(q.def.name, now.reason)
                    Availability.Enabled -> state.send(q.def)
                }
            },
            onDismiss = { state.quickConfirm = null }
        )
    }
    state.confirm?.let { c ->
        ConfirmDialog(
            title = c.title,
            text = c.text,
            confirmText = c.confirmText,
            onConfirm = {
                state.confirm = null
                c.onConfirm()
            },
            onDismiss = { state.confirm = null }
        )
    }
    state.permissionAsk?.let { ask ->
        AlertDialog(
            onDismissRequest = {
                state.permissionAsk = null
                ask.onAnswer(false)
            },
            title = { Text("Нужно разрешение") },
            text = { Text(ask.why) },
            confirmButton = {
                TextButton(onClick = {
                    state.permissionAsk = null
                    ask.onAnswer(true)
                }) { Text("Разрешить") }
            },
            dismissButton = {
                TextButton(onClick = {
                    state.permissionAsk = null
                    ask.onAnswer(false)
                }) { Text("Не сейчас") }
            },
            containerColor = Pal.Surface
        )
    }
    state.info?.let { m ->
        AlertDialog(
            onDismissRequest = { state.info = null },
            title = { Text(m.title) },
            text = { Text(m.text) },
            confirmButton = { TextButton(onClick = { state.info = null }) { Text("Понятно") } },
            containerColor = Pal.Surface
        )
    }
    state.pinPrompt?.let { cb ->
        PinDialog(state) { ok ->
            state.pinPrompt = null
            cb(ok)
        }
    }
}

@Composable
fun PinDialog(state: AppState, onResult: (Boolean) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { onResult(false) },
        title = { Text("ПИН-код") },
        text = {
            Field(
                label = "ПИН-код приложения",
                value = pin,
                onChange = { pin = it.filter { c -> c.isDigit() }.take(8) },
                keyboard = KeyboardType.NumberPassword,
                password = true,
                isError = error != null,
                supporting = error
            )
        },
        confirmButton = {
            TextButton(onClick = {
                when (val c = state.graph.pin.check(pin)) {
                    PinStore.Check.Ok -> onResult(true)
                    is PinStore.Check.Wrong -> {
                        error = "Неверный ПИН, осталось попыток: ${c.attemptsLeft}"
                        pin = ""
                    }
                    is PinStore.Check.Locked -> {
                        error = "Слишком много попыток. Подождите ${c.seconds} с"
                        pin = ""
                    }
                }
            }) { Text("Открыть") }
        },
        dismissButton = { TextButton(onClick = { onResult(false) }) { Text("Отмена") } },
        containerColor = Pal.Surface
    )
}

/** Диалог команды: параметры, предпросмотр SMS и подтверждение по уровню опасности. */
@Composable
fun CommandSheet(state: AppState, live: Live, req: CommandRequest, onDismiss: () -> Unit) {
    val def = req.def
    val params = remember(def.code) {
        mutableStateMapOf<String, String>().apply { def.params.forEach { p -> put(p.key, p.default ?: "") } }
    }
    var typed by remember(def.code) { mutableStateOf("") }
    var standStill by remember(def.code) { mutableStateOf(false) }

    val emergency = req.mode == ConfirmMode.EMERGENCY
    val avail = Rules.availability(def, live.ctx())
    val motionBlocked = avail is Availability.Disabled && avail.kind == BlockKind.MOTION
    val emergencyAllowed = def.code == Rules.ANTIHIJACK_CODE && motionBlocked
    val passes = avail is Availability.Enabled || (emergency && emergencyAllowed)
    val unlocked = rememberCriticalUnlocked(state)
    val needsUnlock = (def.critical || emergency) && !unlocked
    val unknownMotion = def.critical && !emergency && live.safety.unknown()
    val needsCode = def.critical && !emergency
    val profile = live.profile
    val preview: Built? = profile?.let { state.graph.engine.preview(it, def, params.toMap()) }
    // Обновление ПО — только при снятой охране (по последнему известному статусу).
    val armedBlock = def.kind == CommandKind.FIRMWARE_UPDATE && live.obs(ObsKey.ARMED)?.num == 1.0
    val ready = preview is Built.Ok && passes && !needsUnlock && !armedBlock &&
        (!unknownMotion || standStill) && (!needsCode || typed.trim().equals(def.code, ignoreCase = true))

    val doSend = {
        state.commandRequest = null
        state.send(def, params.toMap(), emergency = emergency)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Pal.Surface,
        title = {
            Column {
                Text(def.name, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Код: ${def.code}", color = Pal.TextDim, fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    VerifiedPill(def.verified)
                    if (def.minFw != null) {
                        Spacer(Modifier.width(6.dp))
                        Pill("ПО ${def.minFw}+", Pal.TextDim)
                    }
                }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                def.note?.let {
                    Text(it, color = Pal.TextDim, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                }
                def.cbRequirement?.let {
                    Banner("Требование к центральному блоку: $it", Pal.TextDim)
                    Spacer(Modifier.height(8.dp))
                }
                if (avail is Availability.Disabled && !(emergency && emergencyAllowed)) {
                    Banner(avail.reason, Pal.RedText)
                    Spacer(Modifier.height(8.dp))
                }
                if (armedBlock) {
                    Banner("По последнему статусу охрана включена. Обновлять ПО модуля можно только при снятой охране.", Pal.RedText)
                    Spacer(Modifier.height(8.dp))
                }
                def.params.forEach { p ->
                    Field(
                        label = p.label,
                        value = params[p.key] ?: "",
                        onChange = { v -> params[p.key] = v.filter { it.isDigit() || it == '+' }.take(16) },
                        keyboard = if (p.type == ParamType.PHONE) KeyboardType.Phone else KeyboardType.Number
                    )
                    Spacer(Modifier.height(6.dp))
                }
                when (preview) {
                    is Built.Ok -> Text(
                        "Будет отправлено SMS «${preview.text}»" +
                            if (live.emu) " (эмуляция, без отправки)" else " на ${Phone.pretty(profile?.alarmPhone)}",
                        color = Pal.Text, fontSize = 14.sp
                    )
                    is Built.Error -> Text(preview.message, color = Pal.RedText, fontSize = 14.sp)
                    null -> Text("Нет профиля машины", color = Pal.RedText)
                }
                if (def.control) {
                    Spacer(Modifier.height(6.dp))
                    Text("Управляющая команда: автоматически не повторяется, повтор — только вручную.", color = Pal.TextDim, fontSize = 12.sp)
                }
                if (needsUnlock) {
                    Spacer(Modifier.height(8.dp))
                    Banner("Критичная команда: сначала откройте доступ ПИН-кодом.", Pal.Yellow)
                    TextButton(onClick = { state.unlockCritical { } }) { Text("Открыть ПИН-кодом") }
                }
                if (unknownMotion) {
                    Spacer(Modifier.height(6.dp))
                    SwitchRow(
                        title = "Машина стоит",
                        subtitle = "Движение не определить: нет свежих данных GPS, Bluetooth машины не настроен",
                        checked = standStill
                    ) { standStill = it }
                }
                if (needsCode) {
                    Spacer(Modifier.height(6.dp))
                    Field(label = "Для подтверждения введите код: ${def.code}", value = typed, onChange = { typed = it.take(16) })
                }
                Spacer(Modifier.height(12.dp))
                when {
                    emergency -> HoldButton("Удерживайте 5 секунд", 5000, ready, Pal.Red) { doSend() }
                    def.critical -> HoldButton("Удерживайте 3 секунды", 3000, ready, Pal.Red) { doSend() }
                    def.dangerous -> HoldButton("Удерживайте 2 секунды", 2000, ready, Pal.Orange) { doSend() }
                    else -> PrimaryButton("Отправить", enabled = ready) { doSend() }
                }
                if (emergencyAllowed && !emergency) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        state.unlockCritical { ok ->
                            if (ok) state.commandRequest = req.copy(mode = ConfirmMode.EMERGENCY)
                        }
                    }) { Text("Аварийно: включить в движении (ПИН и удержание 5 с)", color = Pal.RedText) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}
