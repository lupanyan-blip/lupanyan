package ru.e90pult.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.e90pult.R
import ru.e90pult.core.Availability
import ru.e90pult.core.BlockKind
import ru.e90pult.core.CommandDef
import ru.e90pult.core.Rules
import ru.e90pult.engine.Engine

val TILE_CODES = setOf("11", "10", "21", "20", "09", "00551", "41", "*100#")

@Composable
fun MoreScreen(state: AppState, live: Live) {
    val g = state.graph
    val dayStart = remember { Engine.startOfDay() }
    val dayFlow = remember(dayStart) { g.db.sms().sentPartsSince(dayStart) }
    val today by dayFlow.collectAsState(initial = 0)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 6.dp)) {
        ScreenHeader("Ещё")
        NavRow(R.drawable.ic_build, "Сервис", "Редкие и опасные команды — с подтверждением") { state.open(Screen.Service) }
        NavRow(R.drawable.ic_key, "Критичное", "Номера, пароли, сброс, обновление ПО, пересылка SMS — по ПИН-коду", Pal.RedText) {
            state.open(Screen.Critical)
        }
        NavRow(R.drawable.ic_wallet, "Баланс SIM сигнализации", "Запрос и оценка текущего баланса") { state.open(Screen.Balance) }
        NavRow(R.drawable.ic_chip, "ПО модуля и обновление", "Версия модуля: ${live.fw ?: "неизвестна"}") { state.open(Screen.Firmware) }
        NavRow(R.drawable.ic_table, "Таблица команд", "${live.defs.size} команд · JSON") { state.open(Screen.Commands) }
        NavRow(R.drawable.ic_car, "Профили машин", live.profile?.name ?: "нет профиля") { state.open(Screen.Profiles) }
        NavRow(
            R.drawable.ic_sms, "Счётчик SMS",
            "Сегодня $today" + if (live.settings.smsLimitDay > 0) " из ${live.settings.smsLimitDay}" else ""
        ) { state.open(Screen.Counter) }
        NavRow(R.drawable.ic_settings, "Настройки", "Ожидание ответа, защита в движении, ПИН, виджет, OpenCellID") { state.open(Screen.Settings) }
        NavRow(R.drawable.ic_bug, "Отладка", if (live.emu) "Эмулятор ответов включён" else "Эмулятор ответов без машины") {
            state.open(Screen.Debug)
        }
        NavRow(R.drawable.ic_info, "О приложении") { state.open(Screen.About) }
        Spacer(Modifier.height(16.dp))
    }
}

/** Причина недоступности одной строкой для списков. */
fun shortBlock(def: CommandDef, a: Availability): Pair<String, Color>? {
    if (a !is Availability.Disabled) return null
    return when (a.kind) {
        BlockKind.NOT_APPLICABLE -> "неприменимо" to Pal.TextFaint
        BlockKind.FIRMWARE, BlockKind.FIRMWARE_UNKNOWN -> "нужно ПО ${def.minFw}+" to Pal.TextFaint
        BlockKind.M1_ONLY -> "только с М1" to Pal.TextFaint
        BlockKind.PASSWORD -> "нужен пароль PW" to Pal.Yellow
        BlockKind.MOTION -> "в движении недоступно" to Pal.Orange
    }
}

@Composable
fun CommandRow(def: CommandDef, live: Live, onClick: () -> Unit) {
    val a = Rules.availability(def, live.ctx())
    val block = shortBlock(def, a)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            def.code, color = if (block == null) Pal.Text else Pal.TextFaint, fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.width(92.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                def.name, fontSize = 14.sp,
                color = when {
                    block != null -> Pal.TextFaint
                    def.critical -> Pal.RedText
                    def.dangerous -> Pal.Orange
                    else -> Pal.Text
                }
            )
            Row {
                if (block != null) Pill(block.first, block.second) else VerifiedPill(def.verified)
                if (def.dangerous && block == null) {
                    Spacer(Modifier.width(6.dp))
                    Pill(if (def.critical) "критичная" else "опасная", if (def.critical) Pal.RedText else Pal.Orange)
                }
            }
        }
        Ico(R.drawable.ic_chevron, Pal.TextDim, 18.dp)
    }
}

@Composable
fun ServiceScreen(state: AppState, live: Live) {
    val list = live.defs.filter { it.code !in TILE_CODES && !it.critical }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        ScreenHeader("Сервис", onBack = { state.back() })
        Banner(
            "Некоторые команды требуют подтверждения удержанием и могут повлиять на работу сигнализации или автомобиля. " +
                "Не проверенные на машине команды отмечены.",
            Pal.RedText, title = "Опасные команды"
        )
        list.groupBy { it.group }.forEach { (group, defs) ->
            SectionTitle(group)
            CardBox {
                defs.forEachIndexed { i, d ->
                    if (i > 0) HorizontalDivider(color = Pal.Border)
                    CommandRow(d, live) { state.openCommand(d) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun CounterScreen(state: AppState, live: Live) {
    val g = state.graph
    val dayStart = remember { Engine.startOfDay() }
    val monthStart = remember { Engine.startOfMonth() }
    val dayFlow = remember(dayStart) { g.db.sms().sentPartsSince(dayStart) }
    val monthFlow = remember(monthStart) { g.db.sms().sentPartsSince(monthStart) }
    val today by dayFlow.collectAsState(initial = 0)
    val month by monthFlow.collectAsState(initial = 0)
    var dayLimit by remember { mutableStateOf(live.settings.smsLimitDay.toString()) }
    var monthLimit by remember { mutableStateOf(live.settings.smsLimitMonth.toString()) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        ScreenHeader("Счётчик SMS", onBack = { state.back() })
        CounterCard("Сегодня", today, live.settings.smsLimitDay)
        Spacer(Modifier.height(10.dp))
        CounterCard("За месяц", month, live.settings.smsLimitMonth)
        Spacer(Modifier.height(10.dp))
        Text(
            "Считаются части SMS, отправленные с этого телефона из приложения (без эмуляции). " +
                "SMS, которые отправляет модуль, оплачиваются с SIM сигнализации — они учтены в оценке баланса.",
            color = Pal.TextDim, fontSize = 12.sp
        )
        SectionTitle("Лимиты (0 — без лимита)")
        Field("Лимит в день", dayLimit, { dayLimit = it.filter { c -> c.isDigit() }.take(4) }, keyboard = KeyboardType.Number)
        Spacer(Modifier.height(6.dp))
        Field("Лимит в месяц", monthLimit, { monthLimit = it.filter { c -> c.isDigit() }.take(5) }, keyboard = KeyboardType.Number)
        Spacer(Modifier.height(10.dp))
        PrimaryButton("Сохранить") {
            g.prefs.update {
                it.copy(smsLimitDay = dayLimit.toIntOrNull() ?: 0, smsLimitMonth = monthLimit.toIntOrNull() ?: 0, lastLimitWarnDay = 0)
            }
            state.toast("Лимиты сохранены")
        }
        Spacer(Modifier.height(8.dp))
        Text("При достижении лимита придёт уведомление (не чаще раза в день).", color = Pal.TextDim, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun CounterCard(title: String, count: Int, limit: Int) {
    CardBox {
        Row {
            Text(title, color = Pal.Text, modifier = Modifier.weight(1f))
            Text(if (limit > 0) "$count / $limit" else "$count", color = Pal.Text, fontWeight = FontWeight.SemiBold)
        }
        if (limit > 0) {
            val f = (count.toFloat() / limit).coerceIn(0f, 1f)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { f },
                modifier = Modifier.fillMaxWidth(),
                color = if (count >= limit) Pal.RedText else Pal.GreenText,
                trackColor = Pal.Border
            )
            Spacer(Modifier.height(4.dp))
            Text("${(f * 100).toInt()}%", color = Pal.TextDim, fontSize = 12.sp)
        }
    }
}

@Composable
fun AboutScreen(state: AppState) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        ScreenHeader("О приложении", onBack = { state.back() })
        CardBox {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Ico(R.drawable.ic_car_lock, Pal.GreenText, 48.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("E90 Пульт", color = Pal.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("Управление StarLine E90 через SMS · v1.0.0", color = Pal.TextDim, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Неофициальное приложение. Работает без сервера StarLine и без интернета — только SMS " +
                    "с номера владельца (M1). Интернет используется лишь для необязательного запроса в OpenCellID.",
                color = Pal.Text, fontSize = 14.sp
            )
        }
        SectionTitle("Что проверено на машине")
        CardBox {
            Text("• 09 — версия ПО и статус\n• 00551 — уровень сигнала и статус связи с сервером", color = Pal.Text, fontSize = 14.sp)
        }
        SectionTitle("Что не проверено")
        CardBox {
            Text(
                "• Все остальные команды взяты из документации StarLine и помечены «не проверено».\n" +
                    "• Запрос баланса через модуль (*100#) описан для ПО Q2 и новее.\n" +
                    "• Формат ответа на 41, SMS о ходе обновления, тревожные SMS — образцов нет, показываются как есть.\n" +
                    "• Состояние двигателя определяется только по явным словам в ответе.",
                color = Pal.Text, fontSize = 14.sp
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}
