package ru.e90pult.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ru.e90pult.core.SignalQuality

/** Цвета макета: тёмно-синий фон, цветные плитки команд. */
object Pal {
    val Bg = Color(0xFF0A1322)
    val Surface = Color(0xFF111D33)
    val Card = Color(0xFF15233D)
    val CardHi = Color(0xFF1B2C4B)
    val Border = Color(0xFF233656)
    val Text = Color(0xFFFFFFFF)
    val TextDim = Color(0xFF9AA7BD)
    val TextFaint = Color(0xFF6B7A93)
    val Blue = Color(0xFF2F7BF5)
    val BlueSoft = Color(0xFF1C3A6B)
    val Green = Color(0xFF22B455)
    val GreenText = Color(0xFF33D17A)
    val Orange = Color(0xFFF5960F)
    val Red = Color(0xFFE5393B)
    val RedText = Color(0xFFFF5A5A)
    val Yellow = Color(0xFFF5C542)
    val Tile = Color(0xFF1C2A44)
    val DangerBg = Color(0xFF3A1820)

    fun signal(q: SignalQuality): Color = when (q) {
        SignalQuality.BAD -> RedText
        SignalQuality.MEDIUM -> Yellow
        SignalQuality.GOOD -> GreenText
    }
}

private val scheme = darkColorScheme(
    primary = Pal.Blue,
    onPrimary = Color.White,
    secondary = Pal.Green,
    onSecondary = Color.White,
    tertiary = Pal.Orange,
    background = Pal.Bg,
    onBackground = Pal.Text,
    surface = Pal.Surface,
    onSurface = Pal.Text,
    surfaceVariant = Pal.Card,
    onSurfaceVariant = Pal.TextDim,
    outline = Pal.Border,
    error = Pal.RedText,
    onError = Color.White
)

@Composable
fun E90Theme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
