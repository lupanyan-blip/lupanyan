package ru.e90pult.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.e90pult.R
import ru.e90pult.core.ObsKey
import ru.e90pult.core.Phone
import ru.e90pult.data.ProfileEntity
import ru.e90pult.sms.SimInfo

/** Первый запуск: разрешения, номер сигнализации, SIM, проверочная команда 09. */
@Composable
fun OnboardingScreen(state: AppState, live: Live) {
    val g = state.graph
    var permTick by remember { mutableStateOf(0) }
    var name by remember(live.profile?.id) { mutableStateOf(live.profile?.name ?: "Koleos") }
    var phone by remember(live.profile?.id) { mutableStateOf(live.profile?.alarmPhone ?: "") }
    var sims by remember { mutableStateOf<List<SimInfo>?>(null) }
    var testStartedAt by remember { mutableStateOf(0L) }
    // permTick читается, чтобы после ответа на запрос разрешений экран перерисовался.
    val smsOk = permTick >= 0 && Perms.SMS.all { state.granted(it) }
    val profile = live.profile
    val profileOk = profile != null && Phone.isValid(profile.alarmPhone)
    val fwObs = live.obs(ObsKey.FW)
    val testOk = testStartedAt > 0 && fwObs != null && fwObs.timestamp >= testStartedAt - 60_000

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(24.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Ico(R.drawable.ic_car_lock, Pal.GreenText, 72.dp)
            Spacer(Modifier.height(8.dp))
            Text("E90 Пульт", color = Pal.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("Управление StarLine E90 через SMS", color = Pal.TextDim, fontSize = 14.sp)
            Text(
                "Работает без сервера и интернета. Только SMS.", color = Pal.TextDim, fontSize = 13.sp,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp)
            )
        }
        Spacer(Modifier.height(20.dp))

        StepCard(1, "Разрешения", smsOk) {
            Text(
                "SMS — отправка команд и приём ответов. Чтение входящих — чтобы не терять ответы, пришедшие, пока приложение " +
                    "было выгружено. Состояние телефона — выбор SIM. Уведомления — тревоги и ответы. Каждое разрешение можно не давать.",
                color = Pal.TextDim, fontSize = 13.sp
            )
            if (!smsOk) {
                Spacer(Modifier.height(8.dp))
                PrimaryButton("Разрешить") {
                    state.withPermissions(Perms.SMS + Perms.PHONE + Perms.NOTIFY, "Приложение работает только через SMS. Разрешения запрашиваются один раз.") {
                        permTick++
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        StepCard(2, "Номер сигнализации и SIM", profileOk) {
            Field("Название машины", name, { name = it.take(40) })
            Spacer(Modifier.height(6.dp))
            Field(
                "Номер SIM сигнализации", phone, { phone = it.take(20) }, keyboard = KeyboardType.Phone,
                isError = phone.isNotBlank() && !Phone.isValid(phone), supporting = "SIM внутри сигнализации, формат +7…"
            )
            Spacer(Modifier.height(6.dp))
            val chosen = profile?.let { if (it.subscriptionId < 0) "SIM по умолчанию" else it.simLabel.ifEmpty { "SIM №${it.subscriptionId}" } }
            Text(
                "SIM телефона для отправки: ${chosen ?: "по умолчанию"}. Номер этой SIM должен быть записан в модуль как M1.",
                color = Pal.TextDim, fontSize = 12.sp
            )
            TextButton(onClick = {
                state.withPermissions(Perms.PHONE, "Список SIM-карт — чтобы выбрать, с какой отправлять команды.") { sims = g.gateway.sims() }
            }) { Text("Выбрать SIM") }
            sims?.let { list ->
                if (list.isEmpty()) Text("SIM не найдены или нет разрешения — будет SIM по умолчанию.", color = Pal.TextDim, fontSize = 12.sp)
                list.forEach { sim ->
                    TextButton(onClick = {
                        g.scope.launch {
                            val p = g.db.profiles().get(live.settings.activeProfileId)
                            if (p != null) g.db.profiles().update(p.copy(subscriptionId = sim.subId, simLabel = sim.title))
                        }
                        sims = null
                    }, enabled = profile != null) { Text(sim.title) }
                }
                if (profile == null && list.isNotEmpty()) Text("Сначала сохраните номер сигнализации.", color = Pal.Yellow, fontSize = 12.sp)
            }
            Spacer(Modifier.height(6.dp))
            PrimaryButton("Сохранить") {
                when {
                    name.isBlank() -> state.toast("Введите название")
                    !Phone.isValid(phone) -> state.toast("Номер — в формате +7XXXXXXXXXX")
                    else -> g.scope.launch {
                        val norm = Phone.normalize(phone)
                        val p = g.db.profiles().get(live.settings.activeProfileId)
                        if (p == null) {
                            val id = g.db.profiles().insert(ProfileEntity(name = name.trim(), alarmPhone = norm))
                            g.prefs.update { it.copy(activeProfileId = id) }
                        } else {
                            g.db.profiles().update(p.copy(name = name.trim(), alarmPhone = norm))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        StepCard(3, "Проверочная команда 09", testOk) {
            Text(
                "Отправьте 09 и дождитесь ответа: версия ПО модуля, уровень GSM, напряжение АКБ. " +
                    "При слабом сигнале ответ может идти несколько минут.",
                color = Pal.TextDim, fontSize = 13.sp
            )
            Spacer(Modifier.height(8.dp))
            when {
                testOk -> Text(
                    "Ответ получен: ПО ${fwObs?.text}. Готово! Приложение настроено.",
                    color = Pal.GreenText, fontWeight = FontWeight.SemiBold
                )
                testStartedAt > 0 -> Text("SMS отправлено, ждём ответ…", color = Pal.Blue)
                else -> Unit
            }
            Spacer(Modifier.height(6.dp))
            PrimaryButton(if (testStartedAt > 0) "Отправить 09 ещё раз" else "Отправить 09", enabled = profileOk && smsOk) {
                live.def("09")?.let {
                    testStartedAt = System.currentTimeMillis()
                    state.send(it, openRun = false)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton(if (testOk) "Начать" else "Продолжить без проверки", color = if (testOk) Pal.Green else Pal.Blue) {
            g.prefs.update { it.copy(onboardingDone = true) }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = {
            g.scope.launch {
                if (g.db.profiles().allNow().isEmpty()) {
                    val id = g.db.profiles().insert(ProfileEntity(name = name.ifBlank { "Koleos" }, alarmPhone = ""))
                    g.prefs.update { it.copy(activeProfileId = id) }
                }
                g.prefs.update { it.copy(emulator = true, onboardingDone = true) }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Режим отладки без машины (эмулятор)") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StepCard(n: Int, title: String, done: Boolean, content: @Composable () -> Unit) {
    CardBox(border = if (done) Pal.Green else Pal.Border) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(if (done) Pal.Green else Pal.BlueSoft),
                contentAlignment = Alignment.Center
            ) {
                if (done) Ico(R.drawable.ic_check, Pal.Text, 18.dp) else Text("$n", color = Pal.Text, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(12.dp))
            Text(title, color = Pal.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}
