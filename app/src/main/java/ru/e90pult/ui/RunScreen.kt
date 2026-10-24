package ru.e90pult.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.e90pult.R
import ru.e90pult.core.Availability
import ru.e90pult.core.CommandDef
import ru.e90pult.core.CommandKind
import ru.e90pult.core.Describe
import ru.e90pult.core.ResponseParser
import ru.e90pult.core.Rules
import ru.e90pult.data.CommandRunEntity
import ru.e90pult.data.RunState
import ru.e90pult.data.SmsKind

private enum class StepStatus { DONE, ACTIVE, FAIL, IDLE }

fun iconFor(code: String): Int = when (code) {
    "11", "12", "13", "14", "15" -> R.drawable.ic_shield
    "10" -> R.drawable.ic_lock_open
    "21", "21XX#" -> R.drawable.ic_engine
    "20" -> R.drawable.ic_power
    "09" -> R.drawable.ic_info
    "00551", "00550" -> R.drawable.ic_signal
    "41", "43" -> R.drawable.ic_car
    "*100#", "0093" -> R.drawable.ic_wallet
    "00591" -> R.drawable.ic_chip
    "31", "1000PW" -> R.drawable.ic_warning
    else -> R.drawable.ic_sms
}

@Composable
fun RunScreen(state: AppState, live: Live, runId: Long) {
    val g = state.graph
    val runFlow = remember(runId) { g.db.runs().observe(runId) }
    val run by runFlow.collectAsState(initial = null)
    val smsFlow = remember(runId) { g.db.sms().byRun(runId) }
    val smsList by smsFlow.collectAsState(initial = emptyList())

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        ScreenHeader("Отправка команды", onBack = { state.back() })
        val r = run
        if (r == null) {
            Text("Загрузка…", color = Pal.TextDim, modifier = Modifier.padding(16.dp))
        } else {
            RunBody(state, live, r, smsList)
        }
    }
}

@Composable
private fun RunBody(state: AppState, live: Live, r: CommandRunEntity, smsList: List<ru.e90pult.data.SmsEntity>) {
    val g = state.graph
    var repeatAsk by remember { mutableStateOf(false) }
    Column {
        val def = live.def(r.code)
        CardBox {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Ico(iconFor(r.code), if (r.control) Pal.Orange else Pal.Blue, 40.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.name, color = Pal.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Код: ${r.code}  |  Требует: ${def?.minFw?.let { "$it+" } ?: "любое ПО"}",
                        color = Pal.TextDim, fontSize = 13.sp
                    )
                    Text("SMS: «${r.smsText}»" + if (r.emulated) " · эмуляция" else "", color = Pal.TextDim, fontSize = 13.sp)
                }
            }
            if (r.control) {
                Spacer(Modifier.height(10.dp))
                Banner(
                    "Команда отправляется через SMS. Не повторяйте её, пока не получите ответ: повтор может иметь " +
                        "побочный эффект (например, продление работы двигателя).",
                    Pal.Blue, R.drawable.ic_info
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        val sentStatus = when {
            r.sentAt != null -> StepStatus.DONE
            r.state == RunState.FAILED -> StepStatus.FAIL
            r.state == RunState.SENDING -> StepStatus.ACTIVE
            else -> StepStatus.IDLE
        }
        Step("SMS отправлено", Fmt.dateTimeSec(r.sentAt).takeIf { r.sentAt != null }
            ?: if (sentStatus == StepStatus.FAIL) r.error else "отправляется…", sentStatus, false)
        val delivered = when {
            r.deliveredAt != null -> StepStatus.DONE
            r.state == RunState.FAILED && r.sentAt != null -> StepStatus.FAIL
            else -> StepStatus.IDLE
        }
        Step(
            "Доставлено оператором",
            when (delivered) {
                StepStatus.DONE -> Fmt.dateTimeSec(r.deliveredAt)
                StepStatus.FAIL -> r.error
                else -> if (r.state == RunState.REPLIED) "отчёт о доставке не пришёл" else "ждём отчёт о доставке"
            },
            delivered, false
        )
        val minutes = live.settings.replyTimeoutMin
        val (replyTitle, replySub, replyStatus) = when (r.state) {
            RunState.REPLIED -> Triple("Ответ получен", Fmt.dateTimeSec(r.replyAt), StepStatus.DONE)
            RunState.NO_REPLY -> Triple("Нет ответа", "за $minutes мин ответ не пришёл; он может прийти позже", StepStatus.FAIL)
            RunState.CANCELLED -> Triple("Ожидание отменено", "ответ, если придёт, попадёт в журнал как событие", StepStatus.IDLE)
            RunState.FAILED -> Triple("Ответа не будет", r.error, StepStatus.FAIL)
            else -> Triple("Ожидание ответа…", "до ${Fmt.time(r.deadline)} (настройка: $minutes мин)", StepStatus.ACTIVE)
        }
        Step(replyTitle, replySub, replyStatus, true)

        if (r.replyText.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            ReplyCard(r.replyText, r.kind == CommandKind.USSD_BALANCE.name)
        }

        if (r.kind == CommandKind.FIRMWARE_UPDATE.name) {
            Spacer(Modifier.height(12.dp))
            UpdateSessionCard(r, smsList.filter { it.kind == SmsKind.UPDATE }.map { it.timestamp to it.text })
        }

        Spacer(Modifier.height(16.dp))
        if (r.state in RunState.PENDING) {
            SecondaryButton("Отменить ожидание") { g.scope.launch { g.engine.cancelWait(r.id) } }
            Spacer(Modifier.height(8.dp))
        }
        TextButton(onClick = {
            when {
                def == null -> state.info = InfoMessage("Повтор недоступен", "Команды ${r.code} нет в таблице команд профиля.")
                r.emulated && !live.emu -> state.info = InfoMessage(
                    "Повтор недоступен",
                    "Это была эмулированная отправка, а эмулятор сейчас выключен: повтор ушёл бы настоящим SMS. " +
                        "Отправьте команду заново с главного экрана."
                )
                !r.emulated && live.emu -> state.info = InfoMessage(
                    "Повтор недоступен",
                    "Сейчас включён эмулятор. Выключите его в «Отладке», чтобы повторить настоящую команду."
                )
                // Опасные, критичные и команды с параметрами — только через окно команды со всеми проверками.
                state.needsSheet(def) -> state.openCommand(def)
                else -> repeatAsk = true
            }
        }, modifier = Modifier.fillMaxWidth()) {
            Text("Отправить ещё раз (вручную)", color = Pal.Blue)
        }
        Spacer(Modifier.height(16.dp))

        if (repeatAsk && def != null) {
            RepeatDialog(r, def, live, onDismiss = { repeatAsk = false }) {
                repeatAsk = false
                state.resend(r.id)
            }
        }
    }
}

@Composable
private fun Step(title: String, subtitle: String?, status: StepStatus, last: Boolean) {
    val color = when (status) {
        StepStatus.DONE -> Pal.GreenText
        StepStatus.ACTIVE -> Pal.Blue
        StepStatus.FAIL -> Pal.RedText
        StepStatus.IDLE -> Pal.TextFaint
    }
    Row {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(26.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)).border(2.dp, color, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                when (status) {
                    StepStatus.DONE -> Ico(R.drawable.ic_check, color, 16.dp)
                    StepStatus.FAIL -> Ico(R.drawable.ic_close, color, 16.dp)
                    StepStatus.ACTIVE -> CircularProgressIndicator(Modifier.size(16.dp), color = color, strokeWidth = 2.dp)
                    StepStatus.IDLE -> Unit
                }
            }
            if (!last) Box(Modifier.width(2.dp).height(34.dp).background(Pal.Border))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.padding(top = 2.dp)) {
            Text(title, color = if (status == StepStatus.IDLE) Pal.TextDim else Pal.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (!subtitle.isNullOrBlank()) Text(subtitle, color = Pal.TextDim, fontSize = 13.sp)
        }
    }
}

@Composable
fun ReplyCard(text: String, isBalanceReply: Boolean) {
    val p = remember(text) { ResponseParser.parse(text) }
    CardBox {
        Text("Ответ сигнализации", color = Pal.TextDim, fontSize = 12.sp)
        Text(text, color = Pal.Text, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        Text(Describe.short(p, isBalanceReply), color = Pal.GreenText, fontSize = 13.sp)
        if (p.unknown.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("Нераспознано (как есть): " + p.unknown.joinToString(" | "), color = Pal.Yellow, fontSize = 12.sp)
        }
    }
}

@Composable
private fun UpdateSessionCard(r: CommandRunEntity, messages: List<Pair<Long, String>>) {
    CardBox {
        Text("Сеанс обновления ПО", color = Pal.Text, fontWeight = FontWeight.SemiBold)
        Text(
            "Версия до: ${r.fwBefore.ifEmpty { "?" }}  →  после: ${r.fwAfter.ifEmpty { "ещё нет" }}",
            color = if (r.fwAfter.isNotEmpty()) Pal.GreenText else Pal.TextDim, fontSize = 13.sp
        )
        val now = System.currentTimeMillis()
        if (r.fwAfter.isEmpty()) {
            Text(
                if (r.sessionUntil > now) "Ждём SMS о ходе обновления и приветствие до ${Fmt.time(r.sessionUntil)}"
                else "Сеанс завершён без приветствия. Отправьте 09, чтобы узнать версию.",
                color = Pal.TextDim, fontSize = 12.sp
            )
        }
        messages.forEach { (t, m) ->
            Spacer(Modifier.height(6.dp))
            Text("${Fmt.time(t)}  $m", color = Pal.Text, fontSize = 13.sp)
        }
    }
}

@Composable
private fun RepeatDialog(r: CommandRunEntity, def: CommandDef, live: Live, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var understood by remember { mutableStateOf(false) }
    val isStart = r.code == Rules.ENGINE_START_CODE
    val needsAck = r.control
    // Доступность проверяется заново, пока окно открыто (например, «Стоп» в движении).
    val blocked = Rules.availability(def, live.ctx()) as? Availability.Disabled
    val canSend = blocked == null && (!needsAck || understood)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Pal.Surface,
        title = { Text("Повторить «${r.name}»?") },
        text = {
            Column {
                Text("Будет отправлено SMS «${r.smsText}» ещё раз.", color = Pal.Text)
                if (blocked != null) {
                    Spacer(Modifier.height(8.dp))
                    Banner("Сейчас недоступно: ${blocked.reason}", Pal.RedText)
                }
                if (r.control) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Если первая SMS дошла, команда выполнится повторно. SMS, задержанная у оператора, " +
                            "выполнится, когда дойдёт.",
                        color = Pal.TextDim, fontSize = 13.sp
                    )
                }
                if (isStart) {
                    Spacer(Modifier.height(8.dp))
                    Banner(
                        "Повтор 21 при работающем двигателе продлевает работу на 5 минут (по документации — с ПО L3; " +
                            "на I7 не проверено). Модуль с ПО до K9 не отбрасывает старые SMS: задержанная команда " +
                            "запустит двигатель, когда дойдёт.",
                        Pal.Orange
                    )
                }
                if (needsAck) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = understood, onCheckedChange = { understood = it })
                        Text("Понимаю последствия", color = Pal.Text)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = canSend) {
                Text("Отправить", color = if (canSend) Pal.Blue else Color.Gray)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
