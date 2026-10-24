package ru.e90pult.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.e90pult.R
import ru.e90pult.core.Describe
import ru.e90pult.core.LinkStatus
import ru.e90pult.core.ObsKey
import ru.e90pult.core.ResponseParser
import ru.e90pult.core.Signal
import ru.e90pult.data.JournalExport
import ru.e90pult.data.SmsEntity
import ru.e90pult.data.SmsKind
import java.time.LocalDate

/** «Статус автомобиля»: каждое значение со временем получения. */
@Composable
fun StateScreen(state: AppState, live: Live) {
    var more by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        ScreenHeader("Статус автомобиля", onBack = { state.back() }) {
            IconButton(onClick = { live.def("09")?.let { state.tap(it, live) } }) { Ico(R.drawable.ic_refresh) }
        }
        val armed = live.obs(ObsKey.ARMED)
        ValueRow(
            R.drawable.ic_shield, if (armed?.num == 1.0) Pal.GreenText else Pal.Orange, "Охрана",
            armed?.text ?: "нет данных", if (armed?.num == 1.0) Pal.GreenText else if (armed != null) Pal.Orange else Pal.TextDim,
            armed?.timestamp
        )
        Spacer(Modifier.height(8.dp))
        val locks = live.obs(ObsKey.LOCKS)
        ValueRow(
            R.drawable.ic_lock, if (locks?.num == 1.0) Pal.GreenText else Pal.Orange, "Замки",
            locks?.text ?: "нет данных", if (locks?.num == 1.0) Pal.GreenText else if (locks != null) Pal.Orange else Pal.TextDim,
            locks?.timestamp
        )
        Spacer(Modifier.height(8.dp))
        val engine = live.obs(ObsKey.ENGINE)
        ValueRow(
            R.drawable.ic_engine, Pal.RedText, "Двигатель", engine?.text ?: "нет данных",
            if (engine != null) Pal.Text else Pal.TextDim, engine?.timestamp,
            note = if (engine != null) "по словам в ответе модуля; формат не проверен" else null
        )
        Spacer(Modifier.height(8.dp))
        val bat = live.obs(ObsKey.BATTERY)
        val v = bat?.num
        ValueRow(
            R.drawable.ic_battery, Pal.GreenText, "Напряжение АКБ",
            v?.let { Describe.volts(it) + " В" } ?: "нет данных",
            when {
                v == null -> Pal.TextDim
                v < 11.9 -> Pal.RedText
                v < 12.4 -> Pal.Yellow
                else -> Pal.GreenText
            },
            bat?.timestamp
        )
        Spacer(Modifier.height(8.dp))
        val gsm = live.obs(ObsKey.GSM_LEVEL)
        val level = gsm?.num?.toInt()
        val link = live.obs(ObsKey.GSM_LINK)
        ValueRow(
            R.drawable.ic_signal, level?.let { Pal.signal(Signal.quality(it)) } ?: Pal.TextDim, "Уровень GSM",
            level?.let { "$it / 31" + (link?.text?.let { l -> " ($l)" } ?: "") } ?: "нет данных",
            level?.let { Pal.signal(Signal.quality(it)) } ?: Pal.TextDim,
            gsm?.timestamp,
            note = if (level != null && level < 15) "ниже 15 мобильный интернет фактически не работает" else null
        )
        Spacer(Modifier.height(8.dp))
        val ls = LinkStatus.ofCode(link?.text)
        ValueRow(
            R.drawable.ic_cloud, if (ls == LinkStatus.T) Pal.GreenText else Pal.Blue, "Связь с сервером",
            ls?.let { "${it.title} (${it.code})" } ?: (link?.text ?: "нет данных"),
            if (ls == LinkStatus.T) Pal.GreenText else Pal.Text,
            link?.timestamp, note = ls?.hint
        )
        Spacer(Modifier.height(8.dp))
        val fw = live.obs(ObsKey.FW)
        val hw = live.obs(ObsKey.HW)
        ValueRow(
            R.drawable.ic_chip, Pal.Blue, "Версия ПО модуля",
            fw?.text ?: live.profile?.moduleFw?.ifEmpty { null } ?: "нет данных",
            Pal.Text, fw?.timestamp, note = hw?.text?.let { "аппаратный код $it" }
        ) { state.open(Screen.Firmware) }
        Spacer(Modifier.height(8.dp))
        CardBox(onClick = { more = !more }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Прочая информация", color = Pal.Text, modifier = Modifier.weight(1f))
                Ico(R.drawable.ic_expand, Pal.TextDim, 22.dp)
            }
            if (more) {
                listOf(ObsKey.MCC, ObsKey.MNC, ObsKey.LAC, ObsKey.CI, ObsKey.IMEI, ObsKey.BALANCE, ObsKey.RESULT).forEach { k ->
                    val o = live.obs(k)
                    Spacer(Modifier.height(6.dp))
                    Row {
                        Text(k.title, color = Pal.TextDim, fontSize = 13.sp, modifier = Modifier.width(140.dp))
                        Text(o?.text ?: "—", color = Pal.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(Fmt.short(o?.timestamp), color = Pal.TextFaint, fontSize = 11.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Журнал всех SMS с фильтрами и экспортом в CSV. */
@Composable
fun JournalScreen(state: AppState, live: Live) {
    val g = state.graph
    val context = LocalContext.current
    val pid = live.settings.activeProfileId
    val flow = remember(pid, live.emu) { g.db.sms().recent(pid, live.emu, 1000) }
    val all by flow.collectAsState(initial = emptyList())
    var filter by remember { mutableStateOf(0) }
    var expanded by remember { mutableStateOf<Long?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            g.scope.launch {
                val result = try {
                    val sms = g.db.sms().allForExport(pid)
                    val runs = g.db.runs().allForProfile(pid)
                    val bytes = JournalExport.build(sms, runs, live.profile?.name ?: "")
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    }
                    "Журнал сохранён: ${sms.size} SMS"
                } catch (e: Exception) {
                    "Не удалось сохранить: ${e.message}"
                }
                withContext(Dispatchers.Main) { state.toast(result) }
            }
        }
    }
    val list = when (filter) {
        1 -> all.filter { it.outgoing }
        2 -> all.filter { !it.outgoing }
        3 -> all.filter { it.kind == SmsKind.EVENT }
        else -> all
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        ScreenHeader("Журнал SMS", subtitle = "${all.size} сообщений" + if (live.emu) " · с эмуляцией" else "") {
            IconButton(onClick = { exporter.launch("e90-journal-${LocalDate.now()}.csv") }) { Ico(R.drawable.ic_download) }
        }
        Segmented(listOf("Все", "Отправленные", "Полученные", "События"), filter) { filter = it }
        Spacer(Modifier.height(8.dp))
        if (list.isEmpty()) {
            Text("Пока пусто", color = Pal.TextDim, modifier = Modifier.padding(16.dp))
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            items(list, key = { it.id }) { s ->
                SmsRow(s, expanded == s.id) { expanded = if (expanded == s.id) null else s.id }
                HorizontalDivider(color = Pal.Border)
            }
        }
    }
}

@Composable
private fun SmsRow(s: SmsEntity, open: Boolean, onClick: () -> Unit) {
    val (icon, color) = when {
        s.outgoing -> R.drawable.ic_send to Pal.Blue
        s.kind == SmsKind.EVENT -> R.drawable.ic_bell to Pal.Orange
        s.kind == SmsKind.UPDATE -> R.drawable.ic_chip to Pal.Blue
        else -> R.drawable.ic_inbox to Pal.GreenText
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Ico(icon, color, 18.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                s.text, color = Pal.Text, fontSize = 14.sp,
                maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis
            )
            val status = if (s.outgoing) JournalExport.statusTitle(s) else JournalExport.kindTitle(s.kind)
            Text(
                status + (if (s.emulated) " · эмуляция" else "") + (if (s.parts > 1) " · частей: ${s.parts}" else ""),
                color = if (s.status == "SEND_FAILED" || s.status == "DELIVERY_FAILED") Pal.RedText else color,
                fontSize = 12.sp
            )
            if (s.note.isNotEmpty()) Text(s.note, color = Pal.Yellow, fontSize = 12.sp)
            if (open && !s.outgoing) {
                val p = remember(s.text) { ResponseParser.parse(s.text) }
                Spacer(Modifier.height(4.dp))
                Text(Describe.short(p), color = Pal.GreenText, fontSize = 12.sp)
                if (p.unknown.isNotEmpty()) Text("Нераспознано: " + p.unknown.joinToString(" | "), color = Pal.Yellow, fontSize = 12.sp)
                Text("Номер: ${s.phone} · источник: ${s.source}", color = Pal.TextFaint, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(Fmt.short(s.timestamp), color = Pal.TextDim, fontSize = 11.sp)
    }
}
