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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.e90pult.R
import ru.e90pult.core.FwVersion
import ru.e90pult.core.ObsKey
import ru.e90pult.core.Signal
import ru.e90pult.data.BalanceType
import ru.e90pult.data.ProfileEntity
import ru.e90pult.data.RunState
import ru.e90pult.engine.Engine

private fun num(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()
private fun numText(v: Double): String = if (v == 0.0) "" else ru.e90pult.core.Describe.volts(v)

@Composable
fun BalanceScreen(state: AppState, live: Live) {
    val p = live.profile
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        ScreenHeader("Баланс SIM сигнализации", onBack = { state.back() })
        if (p == null) Text("Нет профиля", color = Pal.TextDim) else BalanceBody(state, live, p)
    }
}

@Composable
private fun BalanceBody(state: AppState, live: Live, p: ProfileEntity) {
    val g = state.graph
    val eventsFlow = remember(p.id, live.emu) { g.db.balance().recent(p.id, live.emu) }
    val events by eventsFlow.collectAsState(initial = emptyList())
    val view by produceState<Engine.BalanceView?>(null, p, live.emu, events, live.latest) {
        value = g.engine.balanceView(p, live.emu)
    }
    var topup by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf("") }
    var ussd by remember(p.id) { mutableStateOf(p.ussd) }
    var price by remember(p.id) { mutableStateOf(numText(p.smsPrice)) }
    var fee by remember(p.id) { mutableStateOf(numText(p.monthlyFee)) }
    var traffic by remember(p.id) { mutableStateOf(numText(p.trafficPerDay)) }
    var warn by remember(p.id) { mutableStateOf(numText(p.balanceWarn)) }
    var remind by remember(p.id) { mutableStateOf(p.balanceRemindDays.toString()) }

    Column {
        val v = view
        val est = v?.estimate
        val last = v?.last
        CardBox {
            if (last == null || est == null) {
                Text("Баланс ещё не известен", color = Pal.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("Запросите его у модуля или введите вручную.", color = Pal.TextDim, fontSize = 13.sp)
            } else {
                Text("Сейчас примерно", color = Pal.TextDim, fontSize = 13.sp)
                Text(
                    "≈ " + Fmt.money(est.value), fontSize = 30.sp, fontWeight = FontWeight.Bold,
                    color = if (v?.low == true) Pal.RedText else Pal.GreenText
                )
                Text(
                    (if (last.type == BalanceType.MANUAL) "Введён вручную: " else "От модуля: ") +
                        "${Fmt.money(last.amount)} · ${Fmt.dateTime(last.timestamp)}",
                    color = Pal.Text, fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                val days = String.format("%.1f", est.days).replace('.', ',')
                Text(
                    "Расчёт за $days дн.: SMS от модуля −${Fmt.money(est.smsCost)}, абонплата −${Fmt.money(est.feeCost)}, " +
                        "трафик −${Fmt.money(est.trafficCost)}, пополнения +${Fmt.money(est.adjustments)}",
                    color = Pal.TextDim, fontSize = 12.sp
                )
                if (p.smsPrice == 0.0) {
                    Text("Цена SMS не задана — расход на SMS не учитывается.", color = Pal.Yellow, fontSize = 12.sp)
                }
            }
        }
        if (v?.stale == true) {
            Spacer(Modifier.height(8.dp))
            Banner("Баланс не проверяли больше ${p.balanceRemindDays} дн. Оценка может расходиться с реальностью.", Pal.Yellow)
        }
        Spacer(Modifier.height(10.dp))
        val def = live.def("*100#")
        PrimaryButton("Запросить баланс (${p.ussd.ifBlank { "*100#" }})", enabled = def != null) {
            def?.let { state.tap(it, live) }
        }
        Text(
            "Модуль сам выполнит USSD-запрос и пришлёт ответ оператора — это одна SMS с SIM сигнализации. " +
                "Описано для ПО Q2 и новее; на I7 не проверено. Если ответа нет — проверьте баланс через личный кабинет " +
                "оператора и введите его вручную.",
            color = Pal.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
        )

        SectionTitle("Ручной ввод")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field("Пополнил на, ₽", topup, { topup = it.take(8) }, Modifier.weight(1f), keyboard = KeyboardType.Decimal)
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                val a = num(topup)
                if (a == null || a <= 0) state.toast("Введите сумму") else {
                    g.scope.launch { g.engine.addBalanceEvent(p.id, BalanceType.TOPUP, a) }
                    topup = ""
                }
            }) { Text("Добавить") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field("Текущий баланс, ₽", manual, { manual = it.take(9) }, Modifier.weight(1f), keyboard = KeyboardType.Decimal)
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                val a = num(manual)
                if (a == null) state.toast("Введите сумму") else {
                    g.scope.launch { g.engine.addBalanceEvent(p.id, BalanceType.MANUAL, a) }
                    manual = ""
                }
            }) { Text("Записать") }
        }

        SectionTitle("Стоимость для оценки")
        Field("USSD-запрос баланса", ussd, { ussd = it.take(20) }, keyboard = KeyboardType.Phone, supporting = "МегаФон: *100#")
        Field("Цена одной SMS с SIM сигнализации, ₽", price, { price = it.take(8) }, keyboard = KeyboardType.Decimal)
        Field("Абонплата в месяц, ₽", fee, { fee = it.take(8) }, keyboard = KeyboardType.Decimal)
        Field(
            "Трафик GPRS в сутки, ₽", traffic, { traffic = it.take(8) }, keyboard = KeyboardType.Decimal,
            supporting = "Если GPRS включён (буква G, T или A в ответе), модуль может тратить трафик"
        )
        Field("Предупреждать, если оценка ниже, ₽", warn, { warn = it.take(8) }, keyboard = KeyboardType.Decimal)
        Field("Напоминать проверить через, дней", remind, { remind = it.filter { c -> c.isDigit() }.take(3) }, keyboard = KeyboardType.Number)
        Spacer(Modifier.height(8.dp))
        PrimaryButton("Сохранить") {
            val u = ussd.trim()
            if (!Regex("^[*#][0-9*#]*#$").matches(u)) {
                state.toast("USSD должен начинаться с * или # и заканчиваться #")
            } else {
                g.scope.launch {
                    val cur = g.db.profiles().get(p.id) ?: return@launch
                    g.db.profiles().update(
                        cur.copy(
                            ussd = u,
                            smsPrice = num(price) ?: 0.0,
                            monthlyFee = num(fee) ?: 0.0,
                            trafficPerDay = num(traffic) ?: 0.0,
                            balanceWarn = num(warn) ?: 0.0,
                            balanceRemindDays = remind.toIntOrNull()?.coerceIn(1, 365) ?: 14
                        )
                    )
                }
                state.toast("Сохранено")
            }
        }

        SectionTitle("История")
        if (events.isEmpty()) Text("Пусто", color = Pal.TextDim, fontSize = 13.sp)
        CardBox {
            events.forEachIndexed { i, e ->
                if (i > 0) HorizontalDivider(color = Pal.Border)
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (e.type) {
                                BalanceType.TOPUP -> "Пополнение +" + Fmt.money(e.amount)
                                BalanceType.MANUAL -> "Введён вручную: " + Fmt.money(e.amount)
                                else -> "От модуля: " + Fmt.money(e.amount)
                            },
                            color = Pal.Text, fontSize = 14.sp
                        )
                        Text(Fmt.dateTime(e.timestamp) + if (e.emulated) " · эмуляция" else "", color = Pal.TextDim, fontSize = 12.sp)
                    }
                    if (e.type != BalanceType.REPORTED) {
                        TextButton(onClick = { g.scope.launch { g.db.balance().delete(e.id) } }) { Text("Удалить", color = Pal.RedText) }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun FirmwareScreen(state: AppState, live: Live) {
    val g = state.graph
    val pid = live.settings.activeProfileId
    val updFlow = remember(pid) { g.db.runs().latestUpdate(pid) }
    val upd by updFlow.collectAsState(initial = null)
    val fwObs = live.obs(ObsKey.FW)
    val armed = live.obs(ObsKey.ARMED)
    val gsm = live.obs(ObsKey.GSM_LEVEL)?.num?.toInt()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        ScreenHeader("ПО модуля", onBack = { state.back() })
        CardBox {
            Text("Версия ПО GSM-модуля", color = Pal.TextDim, fontSize = 13.sp)
            Text(live.fw?.toString() ?: "неизвестна", color = Pal.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(
                if (fwObs != null) "из ответа от ${Fmt.dateTime(fwObs.timestamp)}" else "Отправьте 09, чтобы узнать версию",
                color = Pal.TextDim, fontSize = 12.sp
            )
            live.obs(ObsKey.HW)?.let { Text("Аппаратный код: ${it.text}", color = Pal.TextDim, fontSize = 12.sp) }
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Узнать версию (09)") { live.def("09")?.let { state.tap(it, live) } }
        }
        if (live.settings.newsCodes.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Banner(
                live.settings.newsCodes.mapNotNull { live.def(it) }.joinToString("\n") { "• ${it.name} (${it.code})" },
                Pal.GreenText, R.drawable.ic_chip, "Что появилось: ${live.settings.newsFrom} → ${live.settings.newsTo}"
            )
            TextButton(onClick = { g.prefs.update { it.copy(newsCodes = emptyList()) } }) { Text("Скрыть") }
        }

        SectionTitle("Функции по версиям ПО")
        val byVersion = live.defs.filter { it.minFwVersion != null }
            .groupBy { it.minFwVersion!! }
            .toSortedMap()
        CardBox {
            byVersion.entries.forEachIndexed { i, (ver, defs) ->
                if (i > 0) HorizontalDivider(color = Pal.Border)
                val ok = FwVersion.isAtLeast(live.fw, ver)
                Row(Modifier.padding(vertical = 6.dp)) {
                    Text(ver.toString(), color = if (ok) Pal.GreenText else Pal.TextDim, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(48.dp))
                    Column(Modifier.weight(1f)) {
                        defs.forEach { d ->
                            Text(
                                d.name + " (${d.code})" + if (d.notApplicable != null) " — неприменимо к E90" else "",
                                color = if (ok && d.notApplicable == null) Pal.Text else Pal.TextFaint, fontSize = 13.sp
                            )
                        }
                    }
                    Pill(if (ok) "есть" else "нужно обновить", if (ok) Pal.GreenText else Pal.TextDim)
                }
            }
        }
        Text(
            "По базе знаний StarLine версии с P5 и новее выходили только для встроенных модулей HB03, FG63 и более новых. " +
                "Потолок вашего модуля назовёт сам модуль в SMS о ходе обновления.",
            color = Pal.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
        )

        SectionTitle("Обновление по SMS (00591)")
        CardBox {
            Check("Машина стоит, телефон не в машине", if (live.moving || live.inCar) false else true)
            Check(
                "Охрана снята" + (armed?.let { " (по ответу от ${Fmt.short(it.timestamp)})" } ?: " — нет данных"),
                when (armed?.num) { 0.0 -> true; 1.0 -> false; else -> null }
            )
            Check(
                "Сигнал GSM не ниже 15" + (gsm?.let { " (сейчас $it)" } ?: " — нет данных"),
                gsm?.let { Signal.quality(it) != ru.e90pult.core.SignalQuality.BAD }
            )
            Check("Версия модуля известна", live.fw != null)
            Text(
                "Обновление идёт через сервер StarLine по GPRS. Если сервер недоступен, придёт «Команда не выполнена». " +
                    "Несколько минут модуль ничего не делает, кроме обновления; после него придёт приветственное SMS с новой версией.",
                color = Pal.TextDim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp)
            )
            val def = live.defs.firstOrNull { it.code == "00591" }
            PrimaryButton("Обновить ПО модуля (00591)", color = Pal.Red, enabled = def != null) {
                when {
                    def == null -> Unit
                    armed?.num == 1.0 -> state.info = InfoMessage("Сначала снимите с охраны", "Обновлять ПО модуля можно только при снятой охране.")
                    else -> state.openCommand(def)
                }
            }
        }
        upd?.let { r ->
            SectionTitle("Последнее обновление")
            CardBox(onClick = { state.open(Screen.Run(r.id)) }) {
                Text("${Fmt.dateTime(r.createdAt)}: ${r.fwBefore.ifEmpty { "?" }} → ${r.fwAfter.ifEmpty { "?" }}", color = Pal.Text)
                Text(
                    when {
                        r.fwAfter.isNotEmpty() -> "Завершено"
                        r.state == RunState.FAILED -> "Не отправлено: ${r.error}"
                        r.sessionUntil > System.currentTimeMillis() -> "Идёт, ждём SMS до ${Fmt.time(r.sessionUntil)}"
                        else -> "Не подтверждено приветствием"
                    },
                    color = Pal.TextDim, fontSize = 13.sp
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Check(text: String, ok: Boolean?) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        when (ok) {
            true -> Ico(R.drawable.ic_check, Pal.GreenText, 18.dp)
            false -> Ico(R.drawable.ic_close, Pal.RedText, 18.dp)
            null -> Ico(R.drawable.ic_warning, Pal.Yellow, 18.dp)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, color = Pal.Text, fontSize = 14.sp)
    }
}
