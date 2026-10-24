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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.e90pult.R
import ru.e90pult.core.Phone
import ru.e90pult.data.ProfileEntity
import ru.e90pult.data.SecretBox
import ru.e90pult.sms.SimInfo

@Composable
fun ProfilesScreen(state: AppState, live: Live) {
    val g = state.graph
    val flow = remember { g.db.profiles().all() }
    val profiles by flow.collectAsState(initial = emptyList())
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        ScreenHeader("Профили машин", onBack = { state.back() })
        profiles.forEach { p ->
            val active = p.id == live.settings.activeProfileId
            CardBox(onClick = { state.switchProfile(p, live) }, border = if (active) Pal.Blue else Pal.Border) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Ico(R.drawable.ic_car, if (active) Pal.GreenText else Pal.TextDim, 30.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, color = Pal.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "StarLine E90 · ${Phone.pretty(p.alarmPhone).ifEmpty { "номер не задан" }} · ПО ${p.moduleFw.ifEmpty { "?" }}",
                            color = Pal.TextDim, fontSize = 12.sp
                        )
                    }
                    if (active) Pill("активный", Pal.GreenText)
                    IconButton(onClick = { state.open(Screen.ProfileEdit(p.id)) }) { Ico(R.drawable.ic_edit, Pal.TextDim) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        SecondaryButton("Добавить профиль") {
            // Новый профиль — это новый номер сигнализации: как и правка номера, только на стоянке и по ПИН-коду.
            when {
                live.moving || live.inCar -> state.info = InfoMessage(
                    "Недоступно", "Добавить профиль можно только на стоянке, когда телефон не подключён к машине."
                )
                state.protectedEditAllowed(live, allowWithoutPin = true) -> state.open(Screen.ProfileEdit(0))
                else -> state.unlockCritical { ok -> if (ok) state.open(Screen.ProfileEdit(0)) }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun ProfileEditScreen(state: AppState, live: Live, id: Long) {
    val g = state.graph
    var loaded by remember(id) { mutableStateOf<Pair<Boolean, ProfileEntity?>>(false to null) }
    LaunchedEffect(id) {
        loaded = true to (if (id > 0) g.db.profiles().get(id) else null)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        val (ready, p) = loaded
        ScreenHeader(if (id == 0L) "Новый профиль" else "Профиль машины", subtitle = p?.name, onBack = { state.back() })
        when {
            !ready -> Text("Загрузка…", color = Pal.TextDim)
            id > 0 && p == null -> Text("Профиль не найден", color = Pal.TextDim)
            else -> ProfileForm(state, live, p, id == 0L)
        }
    }
}

@Composable
private fun ProfileForm(state: AppState, live: Live, p: ProfileEntity?, isNew: Boolean) {
    val g = state.graph
    val unlocked = rememberCriticalUnlocked(state)
    // Защищённые поля: номер, SIM, пароль, Bluetooth машины. Новый профиль без ПИН-кода — только пока ПИН не задан.
    val critOk = !live.moving && !live.inCar && (unlocked || (isNew && !g.pin.hasPin()))
    var name by remember(p?.id) { mutableStateOf(p?.name ?: "Koleos") }
    var phone by remember(p?.id) { mutableStateOf(p?.alarmPhone ?: "") }
    var subId by remember(p?.id) { mutableStateOf(p?.subscriptionId ?: -1) }
    var simLabel by remember(p?.id) { mutableStateOf(p?.simLabel ?: "") }
    var password by remember(p?.id) { mutableStateOf(p?.let { g.engine.password(it) } ?: "") }
    var pwMode by remember(p?.id) { mutableStateOf(p?.passwordMode ?: false) }
    var hashMode by remember(p?.id) { mutableStateOf(p?.hashMode ?: 0) }
    var cbFw by remember(p?.id) { mutableStateOf(p?.cbFw ?: "") }
    var btAddress by remember(p?.id) { mutableStateOf(p?.btAddress ?: "") }
    var btName by remember(p?.id) { mutableStateOf(p?.btName ?: "") }
    var simPicker by remember { mutableStateOf<List<SimInfo>?>(null) }
    var btPicker by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var askDelete by remember { mutableStateOf(false) }

    Column {
        if (!critOk) {
            Banner(
                "Номер сигнализации, SIM, пароль и Bluetooth машины меняются только в открытом разделе «Критичное» и на стоянке.",
                Pal.RedText, R.drawable.ic_key, "Защищённые настройки"
            )
            Spacer(Modifier.height(8.dp))
            when {
                live.moving || live.inCar -> Text("Сейчас недоступно: машина в движении или телефон подключён к ней.", color = Pal.Orange, fontSize = 13.sp)
                g.pin.hasPin() -> PrimaryButton("Открыть ПИН-кодом", color = Pal.Red) { state.unlockCritical { } }
                else -> SecondaryButton("Задать ПИН-код в «Критичном»") { state.open(Screen.Critical) }
            }
        }
        SectionTitle("Машина")
        Field("Название", name, { name = it.take(40) })
        Spacer(Modifier.height(6.dp))
        Field(
            "Версия ПО центрального блока (если известна)", cbFw, { cbFw = it.take(12) },
            supporting = "Например T1 — для сверки с требованиями команд"
        )
        SectionTitle("Связь")
        Field(
            "Номер SIM сигнализации", phone, { phone = it.take(20) }, keyboard = KeyboardType.Phone, enabled = critOk,
            isError = phone.isNotBlank() && !Phone.isValid(phone), supporting = "SIM внутри сигнализации (МегаФон), формат +7…"
        )
        Spacer(Modifier.height(8.dp))
        CardBox(onClick = if (critOk) {
            {
                state.withPermissions(
                    Perms.PHONE,
                    "Список SIM-карт телефона — чтобы выбрать, с какой SIM отправлять команды. Номер этой SIM записан в модуль как M1."
                ) { res ->
                    if (res.values.all { it }) simPicker = g.gateway.sims() else state.toast("Без разрешения будет SIM по умолчанию")
                }
            }
        } else null) {
            Text("SIM телефона для отправки", color = Pal.TextDim, fontSize = 12.sp)
            Text(if (subId < 0) "По умолчанию (SIM для SMS в настройках телефона)" else simLabel.ifEmpty { "SIM №$subId" }, color = Pal.Text)
            Text("Номер этой SIM должен быть записан в модуль как M1", color = Pal.TextDim, fontSize = 12.sp)
        }
        SectionTitle("Пароль")
        Field(
            "Пароль PW (4 цифры)", password, { password = it.filter { c -> c.isDigit() }.take(4) },
            keyboard = KeyboardType.NumberPassword, password = true, enabled = critOk,
            supporting = "Хранится зашифрованным. Нужен для режима пароля и выключения антиограбления (1000PW)"
        )
        SwitchRow(
            "Отправлять команды с паролем",
            "Для номеров M2–M4 или если в модуле включён пароль для M1 (00961). Формат: код, 00 и PW — например 10001234.",
            pwMode, enabled = critOk
        ) { pwMode = it }
        SectionTitle("Символ # в конце SMS")
        Segmented(listOf("Авто (с ПО O7)", "Всегда", "Никогда"), hashMode) { hashMode = it }
        Text(
            "С ПО O1/O7 модуль понимает # в конце — это помогает при задержках доставки цифровых SMS. На I7 «Авто» не добавляет #.",
            color = Pal.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)
        )
        SectionTitle("Защита в движении")
        CardBox(onClick = if (critOk) {
            {
                state.withPermissions(
                    Perms.BT,
                    "Bluetooth — чтобы выбрать магнитолу машины: пока телефон к ней подключён, опасные команды закрыты."
                ) { res ->
                    if (res.values.all { it }) btPicker = g.safety.bondedDevices() else state.toast("Нет разрешения Bluetooth")
                }
            }
        } else null) {
            Text("Bluetooth машины (магнитола)", color = Pal.TextDim, fontSize = 12.sp)
            Text(if (btAddress.isEmpty()) "Не выбрано" else "$btName ($btAddress)", color = Pal.Text)
            Text("Пока телефон подключён к этому устройству, раздел «Критичное» недоступен", color = Pal.TextDim, fontSize = 12.sp)
        }
        Spacer(Modifier.height(14.dp))
        PrimaryButton("Сохранить", enabled = !isNew || critOk) {
            // Проверка в момент нажатия: доступ мог закрыться, машина — тронуться.
            val critNow = state.protectedEditAllowed(live, allowWithoutPin = isNew)
            val critChanged = p != null && (
                Phone.normalize(phone) != p.alarmPhone || subId != p.subscriptionId ||
                    password != (g.engine.password(p) ?: "") || pwMode != p.passwordMode || btAddress != p.btAddress
                )
            when {
                isNew && !critNow -> state.info = InfoMessage(
                    "Не сохранено", "Новый профиль сохраняется только на стоянке и в открытом разделе «Критичное»."
                )
                name.isBlank() -> state.toast("Введите название")
                phone.isNotBlank() && !Phone.isValid(phone) -> state.toast("Номер сигнализации — в формате +7XXXXXXXXXX")
                password.isNotEmpty() && password.length != 4 -> state.toast("Пароль PW — 4 цифры")
                pwMode && password.length != 4 -> state.toast("Для режима пароля укажите PW")
                else -> g.scope.launch {
                    val normPhone = if (phone.isBlank()) "" else Phone.normalize(phone)
                    if (isNew || p == null) {
                        val nid = g.db.profiles().insert(
                            ProfileEntity(
                                name = name.trim(), alarmPhone = normPhone, subscriptionId = subId, simLabel = simLabel,
                                passwordEnc = SecretBox.encrypt(password), passwordMode = pwMode, hashMode = hashMode,
                                cbFw = cbFw.trim(), btAddress = btAddress, btName = btName
                            )
                        )
                        g.prefs.update { it.copy(activeProfileId = nid) }
                    } else {
                        val cur = g.db.profiles().get(p.id) ?: return@launch
                        var upd = cur.copy(name = name.trim(), hashMode = hashMode, cbFw = cbFw.trim())
                        if (critNow) {
                            upd = upd.copy(
                                alarmPhone = normPhone, subscriptionId = subId, simLabel = simLabel,
                                passwordEnc = SecretBox.encrypt(password), passwordMode = pwMode,
                                btAddress = btAddress, btName = btName
                            )
                        }
                        g.db.profiles().update(upd)
                    }
                    withContext(Dispatchers.Main) {
                        state.toast(
                            if (critChanged && !critNow) "Сохранено без защищённых настроек: раздел закрыт или машина не на стоянке"
                            else "Сохранено"
                        )
                        state.back()
                    }
                }
            }
        }
        if (!isNew && p != null) {
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Удалить профиль", enabled = critOk) { askDelete = true }
        }
        Spacer(Modifier.height(16.dp))
    }

    simPicker?.let { sims ->
        PickerDialog(
            title = "SIM для отправки",
            items = listOf(-1 to "По умолчанию (SIM для SMS в настройках телефона)") + sims.map { it.subId to it.title },
            onPick = { (sid, label) ->
                subId = sid
                simLabel = if (sid < 0) "" else label
                simPicker = null
            },
            onDismiss = { simPicker = null }
        )
    }
    btPicker?.let { devices ->
        PickerDialog(
            title = "Bluetooth машины",
            items = listOf("" to "Не выбрано") + devices.map { (n, a) -> a to "$n ($a)" },
            empty = "Нет сопряжённых устройств. Сначала сопрягите телефон с магнитолой в настройках Bluetooth.",
            onPick = { (addr, _) ->
                btAddress = addr
                btName = devices.firstOrNull { it.second == addr }?.first ?: ""
                btPicker = null
            },
            onDismiss = { btPicker = null }
        )
    }
    if (askDelete && p != null) {
        ConfirmDialog(
            title = "Удалить профиль «${p.name}»?",
            text = "Удалятся профиль, его журнал SMS, история значений и баланса.",
            confirmText = "Удалить",
            onConfirm = {
                askDelete = false
                if (!state.protectedEditAllowed(live)) {
                    state.info = InfoMessage("Не удалено", "Удалить профиль можно только на стоянке и в открытом разделе «Критичное».")
                } else {
                    g.scope.launch {
                        g.engine.deleteProfile(p.id)
                        withContext(Dispatchers.Main) { state.back() }
                    }
                }
            },
            onDismiss = { askDelete = false }
        )
    }
}

@Composable
private fun <T> PickerDialog(
    title: String,
    items: List<Pair<T, String>>,
    onPick: (Pair<T, String>) -> Unit,
    onDismiss: () -> Unit,
    empty: String? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Pal.Surface,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                items.forEach { item ->
                    Text(
                        item.second, color = Pal.Text, fontSize = 15.sp,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(item) }.padding(vertical = 12.dp)
                    )
                }
                if (items.size <= 1 && empty != null) Text(empty, color = Pal.TextDim, fontSize = 13.sp)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
