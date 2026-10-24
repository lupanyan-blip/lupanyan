package ru.e90pult.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Ячейка CSV для русского Excel. */
sealed class Cell {
    /** Обычный текст; если похож на число/дату или начинается с = + - @ — защищается. */
    data class Text(val value: String?) : Cell()
    /** Всегда защищённый от автопреобразования текст: номера телефонов, коды с ведущими нулями, CI, IMEI. */
    data class Protected(val value: String?) : Cell()
    /** Число с десятичной запятой. */
    data class Num(val value: Double?, val decimals: Int = 2) : Cell()
    data class Integer(val value: Long?) : Cell()
    /** Дата ДД.ММ.ГГГГ. */
    data class Date(val epochMs: Long?) : Cell()
    /** Время ЧЧ:ММ:СС. */
    data class Time(val epochMs: Long?) : Cell()
}

/**
 * CSV под русский Excel: разделитель «;», десятичная запятая, UTF-8 с BOM, переводы строк CRLF,
 * даты ДД.ММ.ГГГГ, значения с «;», кавычками и переводами строк — в двойных кавычках,
 * длинные числа и коды — в виде ="…", чтобы Excel не превращал их в числа/даты.
 */
class CsvExcel(private val zone: ZoneId = ZoneId.systemDefault()) {

    private val sb = StringBuilder()
    private val dateF = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val timeF = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun header(vararg names: String) = row(names.map { Cell.Text(it) })

    fun row(cells: List<Cell>) {
        sb.append(cells.joinToString(";") { render(it) }).append("\r\n")
    }

    fun text(): String = sb.toString()

    fun bytes(): ByteArray = BOM + sb.toString().toByteArray(Charsets.UTF_8)

    fun render(c: Cell): String = when (c) {
        is Cell.Text -> c.value?.let { quote(protectIfNeeded(it)) } ?: ""
        is Cell.Protected -> c.value?.let { if (it.isEmpty()) "" else quote(protectAlways(it)) } ?: ""
        is Cell.Num -> c.value?.let { decimal(it, c.decimals) } ?: ""
        is Cell.Integer -> c.value?.toString() ?: ""
        is Cell.Date -> c.epochMs?.let { dateF.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: ""
        is Cell.Time -> c.epochMs?.let { timeF.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: ""
    }

    companion object {
        val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        private val NUMBER_LIKE = Regex("^\\s*[-+]?[0-9][0-9\\s.,:/eE+\\-]*$")

        /** Кавычки по правилам CSV: при «;», кавычке, переводе строки или пробеле по краям. */
        fun quote(s: String): String {
            val needs = s.any { it == ';' || it == '"' || it == '\r' || it == '\n' } ||
                s.startsWith(" ") || s.endsWith(" ")
            return if (needs) "\"" + s.replace("\"", "\"\"") + "\"" else s
        }

        /** ="…" — Excel показывает как текст. Строковая константа в формуле — не длиннее 255 символов. */
        fun formula(s: String): String = "=\"" + s.replace("\"", "\"\"") + "\""

        private fun formulaAllowed(s: String) = s.length <= 250 && s.none { it == '\r' || it == '\n' }

        fun protectAlways(s: String): String = if (formulaAllowed(s)) formula(s) else "'$s"

        fun protectIfNeeded(s: String): String {
            if (s.isEmpty()) return s
            val risky = NUMBER_LIKE.matches(s) || s[0] == '=' || s[0] == '+' || s[0] == '-' || s[0] == '@'
            return if (risky) protectAlways(s) else s
        }

        fun decimal(v: Double, decimals: Int): String =
            BigDecimal.valueOf(v).setScale(decimals, RoundingMode.HALF_UP).toPlainString().replace('.', ',')
    }
}
