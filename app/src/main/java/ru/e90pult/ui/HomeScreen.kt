package ru.e90pult.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.e90pult.R
import ru.e90pult.core.Availability
import ru.e90pult.core.BlockKind
import ru.e90pult.core.CommandDef
import ru.e90pult.core.ObsKey
import ru.e90pult.core.Phone
import ru.e90pult.core.Rules
import ru.e90pult.engine.Engine

private data class TileSpec(val code: String, val title: String, val icon: Int, val color: Color)

private val TILES = listOf(
    TileSpec("11", "Охрана", R.drawable.ic_shield, Pal.Green),
    TileSpec("10", "Снять", R.drawable.ic_lock_open, Pal.Blue),
    TileSpec("21", "Запуск", R.drawable.ic_engine, Pal.Orange),
    TileSpec("20", "Стоп", R.drawable.ic_power, Pal.Red),
    TileSpec("09", "Статус (09)", R.drawable.ic_info, Pal.Tile),
    TileSpec("00551", "Связь (00551)", R.drawable.ic_signal, Pal.Tile),
    TileSpec("41", "Состояние (41)", R.drawable.ic_car, Pal.Tile),
    TileSpec("*100#", "Баланс", R.drawable.ic_wallet, Pal.Tile)
)

@Composable
fun HomeScreen(state: AppState, live: Live) {
    val g = state.graph
    val profile = live.profile
    val profilesFlow = remember { g.db.profiles().all() }
    val profiles by profilesFlow.collectAsState(initial = emptyList())
    val pid = live.settings.activeProfileId
    val pendingFlow = remember(pid) { g.db.runs().pendingFlow(pid) }
    val pending by pendingFlow.collectAsState(initial = emptyList())
    val balanceFlow = remember(pid, live.emu) { g.db.balance().recent(pid, live.emu) }
    val balanceEvents by balanceFlow.collectAsState(initial = emptyList())
    val balance by produceState<Engine.BalanceView?>(null, profile, live.emu, live.latest, balanceEvents) {
        value = profile?.let { g.engine.balanceView(it, live.emu) }
    }
    var menu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        ScreenHeader(
            title = profile?.name ?: "Нет профиля",
            subtitle = "StarLine E90 · ПО модуля ${live.fw ?: "?"}" + if (live.emu) " · эмуляция" else ""
        ) {
            Box {
                IconButton(onClick = { menu = true }) { Ico(R.drawable.ic_car, Pal.Text) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    profiles.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.name + if (p.id == pid) "  ✓" else "") },
                            onClick = {
                                menu = false
                                state.switchProfile(p, live)
                            }
                        )
                    }
                    DropdownMenuItem(text = { Text("Профили машин…") }, onClick = {
                        menu = false
                        state.open(Screen.Profiles)
                    })
                }
            }
        }

        if (profile == null) {
            Banner(
                "Добавьте профиль машины: номер SIM сигнализации и SIM телефона для отправки.",
                Pal.Yellow, title = "Нет профиля"
            ) { state.open(Screen.ProfileEdit(0)) }
        } else {
            HomeBody(state, live, profile, pending, balance)
        }
    }
}

@Composable
private fun HomeBody(
    state: AppState,
    live: Live,
    profile: ru.e90pult.data.ProfileEntity,
    pending: List<ru.e90pult.data.CommandRunEntity>,
    balance: Engine.BalanceView?
) {
    val g = state.graph
    Column {
        StatusHeader(live)

        Spacer(Modifier.height(8.dp))
        if (live.emu) {
            Banner("SMS не отправляются, ответы эмулируются по образцам.", Pal.Blue, R.drawable.ic_bug, "Режим отладки") {
                state.open(Screen.Debug)
            }
            Spacer(Modifier.height(8.dp))
        }
        if (!live.emu && !Phone.isValid(profile.alarmPhone)) {
            Banner("Укажите номер SIM сигнализации в профиле.", Pal.RedText, title = "Нет номера сигнализации") {
                state.open(Screen.ProfileEdit(profile.id))
            }
            Spacer(Modifier.height(8.dp))
        }
        if (live.moving) {
            val sp = live.safety.speedKmh?.let { " (${it.toInt()} км/ч)" } ?: ""
            Banner("«Критичное» и «Стоп» недоступны.", Pal.Orange, R.drawable.ic_speed, "Машина в движении$sp")
            Spacer(Modifier.height(8.dp))
        } else if (live.inCar) {
            Banner("Пока телефон подключён к «${live.safety.btName}», раздел «Критичное» недоступен.", Pal.Orange, R.drawable.ic_bluetooth, "Телефон в машине")
            Spacer(Modifier.height(8.dp))
        }
        if (live.settings.newsCodes.isNotEmpty()) {
            val names = live.settings.newsCodes.mapNotNull { live.def(it)?.name }.joinToString()
            Banner(
                "Стали доступны: $names", Pal.GreenText, R.drawable.ic_chip,
                "ПО модуля обновилось: ${live.settings.newsFrom} → ${live.settings.newsTo}"
            ) { state.open(Screen.Firmware) }
            TextButton(onClick = { g.prefs.update { it.copy(newsCodes = emptyList()) } }) { Text("Скрыть") }
            Spacer(Modifier.height(4.dp))
        }
        pending.forEach { r ->
            Banner(
                "${r.name}: отправлено ${Fmt.time(r.sentAt ?: r.createdAt)}, ждём до ${Fmt.time(r.deadline)}",
                Pal.Blue, R.drawable.ic_send, "Ожидание ответа"
            ) { state.open(Screen.Run(r.id)) }
            Spacer(Modifier.height(8.dp))
        }

        // Плитки команд
        val defs = TILES.mapNotNull { t -> live.def(t.code)?.let { t to it } }
        defs.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (spec, def) ->
                    val a = Rules.availability(def, live.ctx())
                    Tile(
                        title = spec.title,
                        icon = spec.icon,
                        color = spec.color,
                        modifier = Modifier.weight(1f),
                        dimmed = a is Availability.Disabled,
                        caption = tileCaption(def, a, balance)
                    ) { state.tap(def, live) }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
        }

        // Баланс
        BalanceCard(balance) { state.open(Screen.Balance) }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Статус автомобиля", Modifier.weight(1f)) { state.open(Screen.State) }
            SecondaryButton("Сервис", Modifier.weight(1f)) { state.open(Screen.Service) }
        }
        Spacer(Modifier.height(16.dp))
    }
}

private fun tileCaption(def: CommandDef, a: Availability, balance: Engine.BalanceView?): String? {
    if (a is Availability.Disabled) {
        return when (a.kind) {
            BlockKind.MOTION -> "недоступно в движении"
            BlockKind.FIRMWARE, BlockKind.FIRMWARE_UNKNOWN -> "нужно ПО ${def.minFw}+"
            BlockKind.M1_ONLY -> "только с М1"
            BlockKind.PASSWORD -> "нужен пароль PW"
            BlockKind.NOT_APPLICABLE -> "неприменимо"
        }
    }
    if (def.code == "*100#") {
        val est = balance?.estimate ?: return if (def.verified) null else "не проверено"
        return "≈ " + Fmt.money(est.value)
    }
    return if (def.verified) null else "не проверено"
}

@Composable
private fun StatusHeader(live: Live) {
    val armed = live.obs(ObsKey.ARMED)
    val engine = live.obs(ObsKey.ENGINE)
    val color = when (armed?.num) {
        1.0 -> Pal.GreenText
        0.0 -> Pal.Orange
        else -> Pal.TextFaint
    }
    val title = when (armed?.num) {
        1.0 -> "Охрана включена"
        0.0 -> "Охрана выключена"
        else -> "Охрана: нет данных"
    }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(width = 190.dp, height = 120.dp)) {
                val stroke = 9.dp.toPx()
                val d = size.width - stroke
                drawArc(
                    color = color,
                    startAngle = 160f,
                    sweepAngle = 220f,
                    useCenter = false,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(d, d),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
            Ico(R.drawable.ic_car_lock, color, 70.dp, Modifier.padding(top = 10.dp))
        }
        Text(title, color = color, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(
            "Двигатель: " + (engine?.text?.lowercase() ?: "нет данных"),
            color = Pal.Text, fontSize = 14.sp
        )
        val newest = live.latest.values.maxByOrNull { it.timestamp }
        Text(
            if (newest != null) "по SMS от ${Fmt.dateTime(newest.timestamp)}" else "Отправьте «Статус (09)», чтобы получить данные",
            color = Pal.TextDim, fontSize = 12.sp
        )
    }
}

@Composable
private fun BalanceCard(balance: Engine.BalanceView?, onClick: () -> Unit) {
    CardBox(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ico(R.drawable.ic_wallet, Pal.Blue, 26.dp)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Баланс SIM сигнализации", color = Pal.Text, fontSize = 15.sp)
                val last = balance?.last
                val est = balance?.estimate
                if (last == null || est == null) {
                    Text("Ещё не запрашивался", color = Pal.TextDim, fontSize = 13.sp)
                } else {
                    Text(
                        "Последний: ${Fmt.money(last.amount)} (${Fmt.short(last.timestamp)}) · сейчас ≈ ${Fmt.money(est.value)}",
                        color = if (balance?.low == true) Pal.RedText else Pal.TextDim, fontSize = 13.sp
                    )
                    if (balance?.stale == true) Text("Давно не проверяли — запросите баланс", color = Pal.Yellow, fontSize = 12.sp)
                }
            }
            Ico(R.drawable.ic_chevron, Pal.TextDim, 20.dp)
        }
    }
}
