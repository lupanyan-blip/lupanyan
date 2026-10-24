package ru.e90pult.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.e90pult.R
import ru.e90pult.core.CommandKind

/** Раздел «Критичное»: вход по ПИН-коду, недоступен в движении (кроме выключения антиограбления). */
@Composable
fun CriticalScreen(state: AppState, live: Live) {
    val g = state.graph
    val unlocked = rememberCriticalUnlocked(state)
    var setPin by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        ScreenHeader("Критичное", subtitle = "Защита от случайного использования", onBack = { state.back() }) {
            if (unlocked) TextButton(onClick = { state.lockCritical() }) { Text("Закрыть") }
        }
        SafetyCard(state, live)
        Spacer(Modifier.height(10.dp))
        when {
            !g.pin.hasPin() -> {
                Banner("Задайте ПИН-код приложения: без него раздел не открыть.", Pal.Yellow, title = "Нет ПИН-кода")
                Spacer(Modifier.height(8.dp))
                PrimaryButton("Задать ПИН-код") { setPin = true }
            }
            !unlocked -> {
                Banner(
                    "Команды раздела меняют номера, пароли и настройки модуля. Вход — ПИН-кодом или отпечатком, " +
                        "в движении и при подключении к Bluetooth машины команды недоступны.",
                    Pal.RedText, R.drawable.ic_key, "Раздел закрыт"
                )
                Spacer(Modifier.height(8.dp))
                PrimaryButton("Открыть", color = Pal.Red) { state.unlockCritical { } }
            }
            else -> Text(
                "Открыто на 5 минут или до ухода приложения с экрана.",
                color = Pal.GreenText, fontSize = 13.sp
            )
        }

        val antiOff = live.defs.firstOrNull { it.kind == CommandKind.ANTIHIJACK_OFF }
        val anti = live.def("31")
        SectionTitle("Антиограбление")
        CardBox {
            if (anti != null) CommandRow(anti, live) { state.openCommand(anti) }
            if (antiOff != null) {
                HorizontalDivider(color = Pal.Border)
                CommandRow(antiOff, live) { state.openCommand(antiOff, ConfirmMode.NORMAL) }
                Text(
                    "Выключение антиограбления доступно всегда, в том числе в движении. Нужен пароль PW в профиле.",
                    color = Pal.TextDim, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp)
                )
            }
        }

        val critical = live.defs.filter { it.critical && it.code != "31" }
        critical.groupBy { it.group }.forEach { (group, defs) ->
            SectionTitle(group)
            CardBox {
                defs.forEachIndexed { i, d ->
                    if (i > 0) HorizontalDivider(color = Pal.Border)
                    CommandRow(d, live) { state.openCommand(d) }
                }
            }
        }

        SectionTitle("Настройки профиля")
        CardBox {
            NavRow(R.drawable.ic_sim, "Номер сигнализации, SIM, пароль PW", "Меняются только в открытом разделе на стоянке") {
                live.profile?.let { state.open(Screen.ProfileEdit(it.id)) }
            }
            HorizontalDivider(color = Pal.Border)
            NavRow(R.drawable.ic_edit, "Редактировать таблицу команд") { state.open(Screen.CommandsEditor) }
        }
        Spacer(Modifier.height(16.dp))
    }
    if (setPin) SetPinDialog(state) { setPin = false }
}

/** Состояние определения движения и кнопки выдачи разрешений. */
@Composable
fun SafetyCard(state: AppState, live: Live) {
    val g = state.graph
    val s = live.safety
    val (title, sub, color) = when {
        live.moving -> Triple("В движении", "Скорость по GPS: ${s.speedKmh?.toInt() ?: "?"} км/ч", Pal.Orange)
        live.inCar -> Triple("Телефон в машине", "Подключён по Bluetooth к «${s.btName}»", Pal.Orange)
        live.settings.motionGps && s.gpsFresh() -> Triple("Стоит", "Скорость по GPS: ${s.speedKmh?.toInt() ?: 0} км/ч", Pal.GreenText)
        else -> Triple(
            "Движение не определяется",
            "Нет свежих данных GPS; " + if (s.btConfigured) "Bluetooth машины не подключён" else "Bluetooth машины не выбран",
            Pal.Yellow
        )
    }
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ico(R.drawable.ic_speed, color, 30.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = color, fontWeight = FontWeight.SemiBold)
                Text(sub, color = Pal.TextDim, fontSize = 12.sp)
            }
        }
        if (live.settings.motionGps && !s.gpsPermission) {
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Разрешить геолокацию") {
                state.withPermissions(
                    Perms.LOCATION,
                    "Геолокация нужна только для одного: по скорости телефона понять, что машина едет, " +
                        "и закрыть опасные команды. Работает, пока приложение на экране; координаты никуда не отправляются."
                ) { g.safety.restart() }
            }
        } else if (live.settings.motionGps && !s.gpsProviderOn) {
            Spacer(Modifier.height(6.dp))
            Text("GPS в телефоне выключен — скорость не определить.", color = Pal.Yellow, fontSize = 12.sp)
        }
        if (live.settings.motionBt && !s.btConfigured) {
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { live.profile?.let { state.open(Screen.ProfileEdit(it.id)) } }) {
                Text("Выбрать Bluetooth машины в профиле")
            }
        }
    }
}

@Composable
fun SetPinDialog(state: AppState, onDone: () -> Unit) {
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = Pal.Surface,
        title = { Text("Новый ПИН-код") },
        text = {
            Column {
                Field("ПИН-код (4–8 цифр)", p1, { p1 = it.filter { c -> c.isDigit() }.take(8) }, keyboard = KeyboardType.NumberPassword, password = true)
                Spacer(Modifier.height(6.dp))
                Field("Повторите ПИН-код", p2, { p2 = it.filter { c -> c.isDigit() }.take(8) }, keyboard = KeyboardType.NumberPassword, password = true)
                err?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = Pal.RedText, fontSize = 13.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text("ПИН-код хранится только в виде хеша. Забыли — переустановите приложение.", color = Pal.TextDim, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    p1.length !in 4..8 -> err = "Нужно от 4 до 8 цифр"
                    p1 != p2 -> err = "ПИН-коды не совпадают"
                    else -> {
                        state.graph.pin.setPin(p1)
                        state.toast("ПИН-код задан")
                        onDone()
                    }
                }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Отмена") } }
    )
}
