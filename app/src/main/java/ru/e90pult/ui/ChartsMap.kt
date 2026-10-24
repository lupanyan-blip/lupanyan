package ru.e90pult.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.e90pult.core.BalanceMath
import ru.e90pult.core.CellLookup
import ru.e90pult.core.CellResult
import ru.e90pult.core.Describe
import ru.e90pult.core.ObsKey
import ru.e90pult.core.Signal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min
import java.util.Locale

@Composable
fun ChartsScreen(state: AppState, live: Live) {
    val g = state.graph
    val pid = live.settings.activeProfileId
    var kind by remember { mutableStateOf(0) }
    var range by remember { mutableStateOf(1) }
    val from = remember(range) {
        val now = System.currentTimeMillis()
        when (range) {
            0 -> now - BalanceMath.DAY_MS
            1 -> now - 7 * BalanceMath.DAY_MS
            2 -> now - 30 * BalanceMath.DAY_MS
            else -> 0L
        }
    }
    val key = if (kind == 0) ObsKey.BATTERY else ObsKey.GSM_LEVEL
    val flow = remember(pid, key, from, live.emu) { g.db.obs().series(pid, key.name, from, live.emu) }
    val series by flow.collectAsState(initial = emptyList())
    val points = series.mapNotNull { o -> o.num?.let { o.timestamp to it } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        ScreenHeader("Графики")
        Segmented(listOf("Напряжение АКБ", "Уровень GSM"), kind) { kind = it }
        Spacer(Modifier.height(8.dp))
        Segmented(listOf("24 ч", "7 дней", "30 дней", "Всё"), range) { range = it }
        Spacer(Modifier.height(12.dp))
        CardBox {
            val last = points.lastOrNull()?.second
            Row {
                Text(
                    if (kind == 0) "Напряжение АКБ (В)" else "Уровень GSM",
                    color = Pal.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
                )
                if (last != null) {
                    Text(
                        if (kind == 0) Describe.volts(last) + " В" else "${last.toInt()} / 31",
                        color = if (kind == 0) Pal.GreenText else Pal.signal(Signal.quality(last.toInt())),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (kind == 0) {
                LineChart(points, fixedMin = null, fixedMax = null, color = Pal.GreenText,
                    thresholds = listOf(11.9 to Pal.RedText), decimals = 1)
            } else {
                LineChart(points, fixedMin = 0.0, fixedMax = 31.0, color = Pal.Yellow,
                    thresholds = listOf(15.0 to Pal.RedText, 20.0 to Pal.GreenText), decimals = 0)
            }
            if (points.size >= 2) {
                val values = points.map { it.second }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Точек: ${values.size} · мин ${fmtV(values.min(), kind)} · макс ${fmtV(values.max(), kind)} · среднее ${fmtV(values.average(), kind)}",
                    color = Pal.TextDim, fontSize = 12.sp
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (kind == 0) "Значения из ответов модуля (BAT). Без двигателя полностью заряженный АКБ — около 12,6–12,8 В."
            else "GSM: ниже 15 — красная линия (интернет фактически не работает), выше 20 — хороший сигнал.",
            color = Pal.TextDim, fontSize = 12.sp
        )
        Spacer(Modifier.height(16.dp))
    }
}

private fun fmtV(v: Double, kind: Int) = if (kind == 0) Describe.volts(v) else String.format(Locale.ROOT, "%.1f", v).replace('.', ',')

/** Простой линейный график по времени. */
@Composable
fun LineChart(
    points: List<Pair<Long, Double>>,
    fixedMin: Double?,
    fixedMax: Double?,
    color: Color,
    thresholds: List<Pair<Double, Color>>,
    decimals: Int
) {
    val dim = Pal.TextDim.toArgb()
    val grid = Pal.Border
    Canvas(Modifier.fillMaxWidth().height(220.dp)) {
        val paint = android.graphics.Paint().apply {
            this.color = dim
            textSize = 10.sp.toPx()
            isAntiAlias = true
        }
        val left = 40.dp.toPx()
        val bottom = 22.dp.toPx()
        val w = size.width - left - 6.dp.toPx()
        val h = size.height - bottom - 6.dp.toPx()
        if (points.size < 2) {
            drawIntoCanvas { it.nativeCanvas.drawText("Мало данных для графика", left, h / 2, paint) }
            return@Canvas
        }
        val values = points.map { it.second }
        var yMin = fixedMin ?: (values.min() - 0.3)
        var yMax = fixedMax ?: (values.max() + 0.3)
        thresholds.forEach { (t, _) ->
            if (fixedMin == null) yMin = min(yMin, t - 0.2)
            if (fixedMax == null) yMax = max(yMax, t + 0.2)
        }
        if (yMax - yMin < 0.5) yMax = yMin + 0.5
        val tMin = points.first().first
        val tMax = max(points.last().first, tMin + 60_000)
        fun x(t: Long) = left + (t - tMin).toFloat() / (tMax - tMin).toFloat() * w
        fun y(v: Double) = (6.dp.toPx() + (1 - (v - yMin) / (yMax - yMin)) * h).toFloat()

        for (i in 0..4) {
            val v = yMin + (yMax - yMin) * i / 4
            val yy = y(v)
            drawLine(grid, Offset(left, yy), Offset(left + w, yy), strokeWidth = 1f)
            val label = if (decimals == 0) v.toInt().toString() else String.format(Locale.ROOT, "%.1f", v).replace('.', ',')
            drawIntoCanvas { it.nativeCanvas.drawText(label, 2.dp.toPx(), yy + 4.dp.toPx(), paint) }
        }
        thresholds.forEach { (t, c) ->
            if (t in yMin..yMax) {
                drawLine(
                    c.copy(alpha = 0.6f), Offset(left, y(t)), Offset(left + w, y(t)), strokeWidth = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))
                )
            }
        }
        val fmt = DateTimeFormatter.ofPattern(if (tMax - tMin > 2 * BalanceMath.DAY_MS) "dd.MM" else "dd.MM HH:mm")
        for (i in 0..3) {
            val t = tMin + (tMax - tMin) * i / 3
            val label = fmt.format(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()))
            val xx = (x(t) - paint.measureText(label) / 2).coerceIn(left - 10.dp.toPx(), size.width - paint.measureText(label))
            drawIntoCanvas { it.nativeCanvas.drawText(label, xx, size.height - 4.dp.toPx(), paint) }
        }
        val path = Path()
        points.forEachIndexed { i, (t, v) ->
            if (i == 0) path.moveTo(x(t), y(v)) else path.lineTo(x(t), y(v))
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
        val (lt, lv) = points.last()
        drawCircle(color, radius = 4.dp.toPx(), center = Offset(x(lt), y(lv)))
    }
}

/** Местоположение по соте: LAC/CI и (необязательно) координаты из OpenCellID. */
@Composable
fun MapScreen(state: AppState, live: Live) {
    val g = state.graph
    val context = LocalContext.current
    val lac = live.obs(ObsKey.LAC)
    val ci = live.obs(ObsKey.CI)
    val mcc = live.obs(ObsKey.MCC)?.text?.toIntOrNull()
    val mnc = live.obs(ObsKey.MNC)?.text?.toIntOrNull()
    var result by remember(lac?.text, ci?.text) { mutableStateOf<CellResult?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun openUri(uri: String, fallback: String?) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            if (fallback != null) openUri(fallback, null) else state.toast("Нет приложения для открытия ссылки")
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        ScreenHeader("Местоположение", subtitle = "по соте, приблизительно")
        CardBox {
            if (lac == null || ci == null) {
                Text("Данных о соте пока нет. Отправьте «Статус (09)».", color = Pal.TextDim)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Text("LAC: ${lac.text}", color = Pal.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text("CI: ${ci.text}", color = Pal.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "Сота: ${mcc ?: "?"}-${mnc?.toString()?.padStart(2, '0') ?: "?"} · " +
                        "десятичные LAC ${lac.text.toLongOrNull(16) ?: "?"}, CI ${ci.text.toLongOrNull(16) ?: "?"}",
                    color = Pal.TextDim, fontSize = 13.sp
                )
                Text("Получено: ${Fmt.dateTime(ci.timestamp)}", color = Pal.TextDim, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        CardBox {
            Text("OpenCellID (необязательно)", color = Pal.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            val token = live.settings.openCellIdToken
            when {
                token.isBlank() -> {
                    Text("Нужен ваш API-токен OpenCellID — задаётся в настройках.", color = Pal.TextDim, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("Открыть настройки") { state.open(Screen.Settings) }
                }
                lac == null || ci == null || mcc == null || mnc == null ->
                    Text("Нужны MCC, MNC, LAC и CI из ответа модуля.", color = Pal.TextDim, fontSize = 13.sp)
                else -> {
                    PrimaryButton(if (loading) "Запрос…" else "Запросить координаты", enabled = !loading) {
                        loading = true
                        g.scope.launch {
                            val r = g.lookupCell(mcc, mnc, lac.text, ci.text)
                            withContext(Dispatchers.Main) {
                                result = r
                                loading = false
                            }
                        }
                    }
                    if (loading) {
                        Spacer(Modifier.height(8.dp))
                        CircularProgressIndicator(color = Pal.Blue)
                    }
                }
            }
            when (val r = result) {
                is CellResult.Ok -> {
                    val loc = r.location
                    Spacer(Modifier.height(10.dp))
                    Text("≈ ${"%.5f".format(loc.lat)}, ${"%.5f".format(loc.lon)}", color = Pal.Text, fontSize = 16.sp)
                    Text(
                        "Приблизительно: координаты соты, а не машины" + (loc.range?.let { ", радиус ≈ $it м" } ?: ""),
                        color = Pal.Yellow, fontSize = 12.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    PrimaryButton("Открыть в картах") {
                        openUri(CellLookup.geoUri(loc.lat, loc.lon), CellLookup.osmUrl(loc.lat, loc.lon))
                    }
                    Spacer(Modifier.height(6.dp))
                    SecondaryButton("OpenStreetMap в браузере") { openUri(CellLookup.osmUrl(loc.lat, loc.lon), null) }
                }
                is CellResult.Error -> {
                    Spacer(Modifier.height(8.dp))
                    Text(r.message, color = Pal.RedText, fontSize = 13.sp)
                }
                null -> Unit
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Точность по соте — от сотен метров до километров. Интернет нужен только для запроса в OpenCellID.",
            color = Pal.TextDim, fontSize = 12.sp
        )
        Spacer(Modifier.height(16.dp))
    }
}
