package ru.e90pult.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.e90pult.R
import ru.e90pult.core.CommandDef
import ru.e90pult.core.CommandTableJson

@Composable
fun CommandsScreen(state: AppState, live: Live) {
    val g = state.graph
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val json = g.commands.jsonFor(live.profile)
            g.scope.launch {
                val msg = try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    }
                    "Таблица сохранена"
                } catch (e: Exception) {
                    "Не удалось сохранить: ${e.message}"
                }
                withContext(Dispatchers.Main) { state.toast(msg) }
            }
        }
    }
    val list: List<CommandDef> = when (tab) {
        0 -> live.defs.filter { it.code in TILE_CODES }
        1 -> live.defs.filter { it.code !in TILE_CODES && !it.critical }
        else -> live.defs.filter { it.critical }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        ScreenHeader(
            "Таблица команд",
            subtitle = if (live.profile?.commandsJson.isNullOrBlank()) "таблица по умолчанию" else "своя таблица профиля",
            onBack = { state.back() }
        ) {
            IconButton(onClick = { exporter.launch("e90-commands.json") }) { Ico(R.drawable.ic_download) }
        }
        Segmented(listOf("Основные", "Сервис", "Критичные"), tab) { tab = it }
        Spacer(Modifier.height(8.dp))
        CardBox {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Text("Код", color = Pal.TextDim, fontSize = 12.sp, modifier = Modifier.width(84.dp))
                Text("Название", color = Pal.TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("ПО", color = Pal.TextDim, fontSize = 12.sp, modifier = Modifier.width(34.dp))
                Text("Опасн.", color = Pal.TextDim, fontSize = 12.sp, modifier = Modifier.width(48.dp))
            }
            HorizontalDivider(color = Pal.Border)
            list.forEach { d ->
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                    Text(
                        d.code, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(84.dp),
                        color = if (d.critical) Pal.RedText else Pal.Text
                    )
                    Column(Modifier.weight(1f)) {
                        Text(d.name, color = Pal.Text, fontSize = 13.sp)
                        Text(
                            d.group + (if (d.verified) " · проверена" else " · не проверена") +
                                (d.notApplicable?.let { " · неприменима" } ?: ""),
                            color = if (d.verified) Pal.GreenText else Pal.TextDim, fontSize = 11.sp
                        )
                    }
                    Text(d.minFw ?: "—", color = Pal.TextDim, fontSize = 12.sp, modifier = Modifier.width(34.dp))
                    Text(
                        when {
                            d.critical -> "крит."
                            d.dangerous -> "да"
                            else -> "—"
                        },
                        color = when {
                            d.critical -> Pal.RedText
                            d.dangerous -> Pal.Orange
                            else -> Pal.TextDim
                        },
                        fontSize = 12.sp, modifier = Modifier.width(48.dp)
                    )
                }
                HorizontalDivider(color = Pal.Border)
            }
        }
        Spacer(Modifier.height(10.dp))
        PrimaryButton("Редактировать таблицу (JSON)") { state.open(Screen.CommandsEditor) }
        Spacer(Modifier.height(6.dp))
        Text(
            "Правка таблицы — критичное действие: только в открытом разделе «Критичное» и на стоянке.",
            color = Pal.TextDim, fontSize = 12.sp
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun CommandsEditorScreen(state: AppState, live: Live) {
    val g = state.graph
    val context = LocalContext.current
    val profile = live.profile
    val unlocked = rememberCriticalUnlocked(state)
    val allowed = unlocked && !live.moving && !live.inCar
    var text by remember(profile?.id) { mutableStateOf(g.commands.jsonFor(profile)) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var askReset by remember { mutableStateOf(false) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            g.scope.launch {
                val r = try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    }
                } catch (e: Exception) {
                    null
                }
                withContext(Dispatchers.Main) {
                    if (r != null) {
                        text = r.removePrefix("﻿")
                        message = "Файл загружен в редактор — проверьте и сохраните" to true
                    } else {
                        message = "Не удалось прочитать файл" to false
                    }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        ScreenHeader("Редактор таблицы", subtitle = profile?.name, onBack = { state.back() })
        if (!allowed) {
            Banner(
                when {
                    live.moving -> "Недоступно в движении."
                    live.inCar -> "Недоступно, пока телефон подключён к машине по Bluetooth."
                    else -> "Правка таблицы команд — критичное действие. Откройте доступ ПИН-кодом."
                },
                Pal.RedText, R.drawable.ic_key, "Только на стоянке и по ПИН-коду"
            )
            Spacer(Modifier.height(8.dp))
            if (!live.moving && !live.inCar && g.pin.hasPin()) {
                PrimaryButton("Открыть", color = Pal.Red) { state.unlockCritical { } }
            } else if (!g.pin.hasPin()) {
                Text("Сначала задайте ПИН-код в разделе «Критичное».", color = Pal.TextDim, fontSize = 13.sp)
            }
        }
        Text(
            "Поля: code, name, group, dangerous, critical, minFw, cbRequirement, notApplicable, verified, template, " +
                "params, m1Only, control, hashAllowed, note, source, kind. В template: {PW} — «00»+пароль в режиме " +
                "пароля, {PWRAW} — пароль, {USSD} — запрос баланса, {КЛЮЧ} — параметр.",
            color = Pal.TextDim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp)
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            enabled = allowed,
            modifier = Modifier.fillMaxWidth(),
            minLines = 14,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Pal.Text)
        )
        message?.let { (m, ok) ->
            Spacer(Modifier.height(6.dp))
            Text(m, color = if (ok) Pal.GreenText else Pal.RedText, fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row {
            SecondaryButton("Проверить", Modifier.weight(1f)) {
                message = try {
                    val defs = CommandTableJson.parse(text)
                    val problems = CommandTableJson.validate(defs)
                    if (problems.isEmpty()) "Ошибок нет: ${defs.size} команд" to true
                    else ("Проблемы:\n" + problems.joinToString("\n")) to false
                } catch (e: IllegalArgumentException) {
                    (e.message ?: "Ошибка") to false
                }
            }
            Spacer(Modifier.width(8.dp))
            PrimaryButton("Сохранить", Modifier.weight(1f), enabled = allowed && profile != null) {
                val p = profile ?: return@PrimaryButton
                if (!state.protectedEditAllowed(live)) {
                    message = "Не сохранено: раздел «Критичное» закрыт или машина не на стоянке" to false
                    return@PrimaryButton
                }
                try {
                    val defs = CommandTableJson.parse(text)
                    val problems = CommandTableJson.validate(defs)
                    if (problems.isNotEmpty()) {
                        message = ("Не сохранено:\n" + problems.joinToString("\n")) to false
                    } else {
                        val normalized = CommandTableJson.serialize(defs)
                        g.scope.launch {
                            g.db.profiles().get(p.id)?.let { cur -> g.db.profiles().update(cur.copy(commandsJson = normalized)) }
                        }
                        text = normalized
                        message = "Сохранено: ${defs.size} команд" to true
                    }
                } catch (e: IllegalArgumentException) {
                    message = "Не сохранено: ${e.message}" to false
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        SecondaryButton("Загрузить из файла", enabled = allowed) { importer.launch(arrayOf("application/json", "text/*", "*/*")) }
        Spacer(Modifier.height(8.dp))
        SecondaryButton("Вернуть таблицу по умолчанию", enabled = allowed && profile != null) { askReset = true }
        Spacer(Modifier.height(16.dp))
    }
    if (askReset) {
        ConfirmDialog(
            title = "Вернуть таблицу по умолчанию?",
            text = "Своя таблица профиля будет удалена. Отметки «проверена» и правки пропадут.",
            confirmText = "Вернуть",
            onConfirm = {
                askReset = false
                val p = profile
                if (p != null && !state.protectedEditAllowed(live)) {
                    message = "Не изменено: раздел «Критичное» закрыт или машина не на стоянке" to false
                } else if (p != null) {
                    g.scope.launch {
                        g.db.profiles().get(p.id)?.let { cur -> g.db.profiles().update(cur.copy(commandsJson = "")) }
                    }
                    text = CommandTableJson.serialize(g.commands.defaults)
                    message = "Таблица по умолчанию восстановлена" to true
                }
            },
            onDismiss = { askReset = false }
        )
    }
}
