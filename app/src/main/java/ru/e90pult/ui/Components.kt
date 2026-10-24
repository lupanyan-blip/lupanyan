package ru.e90pult.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.e90pult.R
import ru.e90pult.core.Describe
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Fmt {
    private val dt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
    private val dts = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
    private val hm = DateTimeFormatter.ofPattern("HH:mm")
    private val dm = DateTimeFormatter.ofPattern("dd.MM HH:mm")

    private fun z(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())

    fun dateTime(ms: Long?): String = if (ms == null || ms <= 0) "—" else dt.format(z(ms))
    fun dateTimeSec(ms: Long?): String = if (ms == null || ms <= 0) "—" else dts.format(z(ms))
    fun time(ms: Long?): String = if (ms == null || ms <= 0) "—" else hm.format(z(ms))
    fun short(ms: Long?): String = if (ms == null || ms <= 0) "—" else dm.format(z(ms))

    fun ago(ms: Long?, now: Long = System.currentTimeMillis()): String {
        if (ms == null || ms <= 0) return "—"
        val s = (now - ms) / 1000
        return when {
            s < 60 -> "только что"
            s < 3600 -> "${s / 60} мин назад"
            s < 86_400 -> "${s / 3600} ч назад"
            else -> "${s / 86_400} дн назад"
        }
    }

    fun money(v: Double): String = Describe.volts(v) + " ₽"
}

@Composable
fun Ico(res: Int, tint: Color = Pal.Text, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    Icon(painter = painterResource(res), contentDescription = null, tint = tint, modifier = modifier.size(size))
}

/** Заголовок экрана в стиле макета. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Ico(R.drawable.ic_back) }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Pal.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = Pal.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

@Composable
fun CardBox(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    background: Color = Pal.Card,
    border: Color = Pal.Border,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    var m = modifier.fillMaxWidth().clip(shape).background(background).border(1.dp, border, shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(m.padding(14.dp), content = content)
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, color = Pal.TextDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp)
    )
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        color = color,
        fontSize = 11.sp,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

@Composable
fun VerifiedPill(verified: Boolean) {
    if (verified) Pill("проверено", Pal.GreenText) else Pill("не проверено", Pal.Yellow)
}

/** Плашка-предупреждение. */
@Composable
fun Banner(
    text: String,
    color: Color = Pal.Yellow,
    icon: Int = R.drawable.ic_warning,
    title: String? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(12.dp)
    var m = modifier.fillMaxWidth().clip(shape).background(color.copy(alpha = 0.10f)).border(1.dp, color.copy(alpha = 0.5f), shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Row(m.padding(12.dp), verticalAlignment = Alignment.Top) {
        Ico(icon, color, 20.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (title != null) Text(title, color = color, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(text, color = Pal.Text, fontSize = 13.sp)
        }
    }
}

/** Большая плитка команды на главном экране. */
@Composable
fun Tile(
    title: String,
    icon: Int,
    color: Color,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    caption: String? = null,
    onClick: () -> Unit
) {
    val bg = if (dimmed) Pal.Tile else color
    val fg = if (dimmed) Pal.TextFaint else Color.White
    Column(
        modifier
            .height(96.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Ico(icon, fg, 30.dp)
        Spacer(Modifier.height(6.dp))
        Text(title, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, textAlign = TextAlign.Center, maxLines = 1)
        if (caption != null) {
            Text(caption, color = fg.copy(alpha = 0.8f), fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Строка значения состояния: иконка, название, значение, время получения. */
@Composable
fun ValueRow(
    icon: Int,
    iconColor: Color,
    title: String,
    value: String,
    valueColor: Color,
    time: Long?,
    note: String? = null,
    onClick: (() -> Unit)? = null
) {
    CardBox(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ico(icon, iconColor, 30.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(value, color = valueColor, fontSize = 15.sp)
                if (note != null) Text(note, color = Pal.TextDim, fontSize = 12.sp)
            }
            Text(Fmt.dateTime(time), color = Pal.TextDim, fontSize = 11.sp)
        }
    }
}

/** Строка меню с переходом. */
@Composable
fun NavRow(icon: Int, title: String, subtitle: String? = null, tint: Color = Pal.Text, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Ico(icon, tint, 22.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Pal.Text, fontSize = 15.sp)
            if (subtitle != null) Text(subtitle, color = Pal.TextDim, fontSize = 12.sp)
        }
        Ico(R.drawable.ic_chevron, Pal.TextDim, 20.dp)
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) Pal.Text else Pal.TextFaint, fontSize = 15.sp)
            if (subtitle != null) Text(subtitle, color = Pal.TextDim, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = if (enabled) onChange else null, enabled = enabled)
    }
}

@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    supporting: String? = null,
    isError: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = singleLine,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        supportingText = if (supporting != null) ({ Text(supporting) }) else null
    )
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, color: Color = Pal.Blue, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White)
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(12.dp)
    ) { Text(text, color = if (enabled) Pal.Blue else Pal.TextFaint) }
}

/** Переключатель-«сегменты» как в макете. */
@Composable
fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val outer = RoundedCornerShape(10.dp)
    Row(modifier.fillMaxWidth().clip(outer).background(Pal.Surface).border(1.dp, Pal.Border, outer).padding(3.dp)) {
        options.forEachIndexed { i, o ->
            val sel = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (sel) Pal.Blue else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    o, color = if (sel) Color.White else Pal.TextDim, fontSize = 13.sp,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1
                )
            }
        }
    }
}

/**
 * Кнопка подтверждения удержанием: срабатывает, только если держать [holdMillis] мс.
 * Отпустили раньше или кнопка стала недоступна во время удержания (машина тронулась,
 * закрылся доступ) — ничего не происходит.
 */
@Composable
fun HoldButton(
    text: String,
    holdMillis: Int,
    enabled: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    onConfirmed: () -> Unit
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onConfirmed)
    val enabledNow by rememberUpdatedState(enabled)
    val shape = RoundedCornerShape(14.dp)
    LaunchedEffect(enabled) { if (!enabled) progress.snapTo(0f) }
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            .background(if (enabled) color.copy(alpha = 0.28f) else Pal.Tile)
            .border(1.dp, if (enabled) color else Pal.Border, shape)
            .pointerInput(enabled, holdMillis) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    var fired = false
                    try {
                        // Анимация — дочерняя для нажатия: если кнопка стала недоступной, жест
                        // перезапускается и удержание отменяется вместе с ним.
                        coroutineScope {
                            val hold = launch {
                                progress.snapTo(0f)
                                progress.animateTo(1f, tween(durationMillis = holdMillis, easing = LinearEasing))
                                if (enabledNow) {
                                    fired = true
                                    latest()
                                }
                            }
                            tryAwaitRelease()
                            if (!fired) hold.cancel()
                        }
                    } finally {
                        if (fired) {
                            scope.launch {
                                delay(400)
                                progress.snapTo(0f)
                            }
                        } else {
                            withContext(NonCancellable) { progress.snapTo(0f) }
                        }
                    }
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(progress.value)
                .background(color)
        )
        Text(text, color = if (enabled) Color.White else Pal.TextFaint, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String = "Отправить",
    dismissText: String = "Отмена",
    confirmEnabled: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onConfirm, enabled = confirmEnabled) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissText) } },
        title = { Text(title) },
        text = { Text(text) },
        containerColor = Pal.Surface
    )
}
