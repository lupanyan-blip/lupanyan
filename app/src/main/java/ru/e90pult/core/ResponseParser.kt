package ru.e90pult.core

/** Статус связи с сервером — буква в скобках после уровня GSM. */
enum class LinkStatus(val code: String, val title: String, val hint: String) {
    T("T", "Связь с сервером есть", "Модуль подключён к серверу StarLine."),
    G("G", "Интернет есть, сервер — нет", "GPRS работает, но модуль не зарегистрирован на сервере."),
    A("A", "Идёт подключение", "Модуль подключается к GPRS и серверу."),
    NONE("-", "Нет GPRS", "Мобильного интернета у модуля нет.");

    companion object {
        fun of(c: Char?): LinkStatus? = when (c?.uppercaseChar()) {
            'T' -> T
            'G' -> G
            'A' -> A
            '-', '–', '—' -> NONE
            else -> null
        }

        fun ofCode(code: String?): LinkStatus? = of(code?.firstOrNull())
    }
}

enum class CmdResult(val title: String) {
    OK("Команда выполнена"),
    FAILED("Команда не выполнена"),
    ERROR("Ошибка команды")
}

/** Результат разбора SMS от сигнализации. Исходный текст всегда сохраняется целиком в [raw]. */
data class ParsedResponse(
    val raw: String,
    val fwRaw: String? = null,
    val fw: FwVersion? = null,
    val hwCode: String? = null,
    val gsmLevel: Int? = null,
    val gsmNet: String? = null,
    val linkRaw: Char? = null,
    val link: LinkStatus? = null,
    val battery: Double? = null,
    val mcc: Int? = null,
    val mnc: Int? = null,
    val lac: String? = null,
    val ci: String? = null,
    val imei: String? = null,
    val result: CmdResult? = null,
    val errorText: String? = null,
    val armed: Boolean? = null,
    val locksClosed: Boolean? = null,
    /** Двигатель — только по явным словам «двигатель запущен/заглушен…»; формат не проверен. */
    val engineRunning: Boolean? = null,
    /** Сумма после слова «баланс». */
    val balance: Double? = null,
    /** Сумма с «р/руб/₽» без слова «баланс» — используется только для ответа на запрос баланса. */
    val moneyAmount: Double? = null,
    val greeting: Boolean = false,
    val updateMention: Boolean = false,
    val pinCode: String? = null,
    val gprsUnavailable: Boolean = false,
    /** В тексте есть слова «тревога»/«сработал» — такое SMS не должно теряться среди ответов. */
    val alarm: Boolean = false,
    /** Все поля «ключ:значение» в порядке появления. */
    val fields: List<Pair<String, String>> = emptyList(),
    /** Нераспознанные фрагменты как есть. */
    val unknown: List<String> = emptyList()
) {
    val recognizedAnything: Boolean
        get() = fields.isNotEmpty() || result != null || armed != null || locksClosed != null ||
            engineRunning != null || balance != null || greeting || pinCode != null || gprsUnavailable

    /** Сумма для ответа на запрос баланса: сначала «баланс …», затем любая сумма в рублях. */
    val balanceForBalanceReply: Double? get() = balance ?: moneyAmount
}

/**
 * Разбор ответов модуля. Выдерживает текст, склеенный без пробелов и переводов строк:
 * "VER:I7GSM:11(G)BAT:12.78VMCC:250MNC:02LAC:147CCI:CE91".
 */
object ResponseParser {

    private val IC = setOf(RegexOption.IGNORE_CASE)

    // Ключи, которые в склеенном тексте могут идти сразу за латинской буквой (12.78VMCC, 147CCI, 13.75V4G),
    // ищутся без проверки предыдущего символа; остальные — только если перед ними не латинская буква
    // (чтобы не находить VER в «SERVER:», PS в «GPS:»).
    private val KEY = Regex(
        "(?:(MCC|MNC|LAC|CI|IMEI|4G|3G|2G)|(?<![A-Za-z])(VER|GSM|BAT|PW|PS|M[1-4]\\d{0,2}))\\s*:",
        IC
    )

    private val V_VER = Regex("^\\s*((?:[A-Za-z]{1,3}\\d{1,3}\\s*[-–—]\\s*)?[A-Za-z]\\d{1,2})(?![0-9])")
    private val V_GSM = Regex("^\\s*(\\d{1,2})(?:\\s*\\(\\s*([A-Za-z\\-–—])\\s*\\))?")
    private val V_BAT = Regex("^\\s*(\\d{1,2}(?:[.,]\\d{1,3})?)\\s*[VvВв]?")
    private val V_DEC = Regex("^\\s*(\\d{1,3})")
    private val V_HEX = Regex("^\\s*([0-9A-Fa-f]{1,8})")
    private val V_IMEI = Regex("^\\s*(\\d{14,17})")
    private val V_PIN4 = Regex("^\\s*(\\d{4})")
    private val V_PHONE = Regex("^\\s*(\\+?\\d{6,15})")

    private const val L = "[а-яё]"

    private val P_GREETING = Regex("(?:star\\s*line(?:\\s*gps)?\\s*)?приветствует\\s*вас\\s*!?", IC)
    private val P_PIN = Regex("код\\s*регистрации[^:\\d]{0,60}:\\s*(\\d{4,8})", IC)
    private val P_GPRS_NA = Regex("gprs\\s*недоступен[^.!]{0,40}[.!]?", IC)
    private val P_ERROR = Regex(
        "ошибка(?:\\s*набора)?\\s*команды\\s*(?:sms|смс)(?:\\s*,\\s*(?:мин|макс)\\.?\\s*время\\s*\\d+\\s*минут$L*)?",
        IC
    )
    private val P_ERROR_WORD = Regex("ошибка$L*", IC)
    private val P_FAILED = Regex("(?:команда\\s*)?не\\s*выполнена", IC)
    private val P_OK = Regex("(?:команда\\s*)?выполнена", IC)
    private val P_ARMED = Regex("охрана\\s*(вкл|выкл)$L*\\.?", IC)
    private val P_LOCKS = Regex("замки\\s*(откр|закр)$L*\\.?", IC)
    private val P_ENGINE_OFF = Regex(
        "двигатель\\s*(?:не\\s*запущен|заглушен|остановлен|выключен|выкл)$L*\\.?", IC
    )
    private val P_ENGINE_ON = Regex(
        "двигатель\\s*(?:запущен|заведен|заведён|работает|включен|вкл)$L*\\.?", IC
    )
    private val P_BALANCE = Regex(
        "баланс$L*[^0-9\\-−]{0,25}?([\\-−]?\\s?\\d{1,6}(?:[.,]\\d{1,2})?)\\s*(?:руб$L*\\.?|р(?![а-яё])\\.?|₽)?",
        IC
    )
    private val P_MONEY = Regex(
        "([\\-−]?\\d{1,6}(?:[.,]\\d{1,2})?)\\s*(?:руб$L*\\.?|р(?![а-яё])\\.?|₽)",
        IC
    )
    private val P_UPDATE = Regex("обновлени$L*", IC)
    private val P_ALARM = Regex("тревог$L*|сработ$L*", IC)
    private val JUNK = Regex("^[\\s.,;:!?\\-–—]*$")

    private class Acc {
        var fwRaw: String? = null
        var gsmLevel: Int? = null
        var gsmNet: String? = null
        var linkRaw: Char? = null
        var battery: Double? = null
        var mcc: Int? = null
        var mnc: Int? = null
        var lac: String? = null
        var ci: String? = null
        var imei: String? = null
        var result: CmdResult? = null
        var errorText: String? = null
        var armed: Boolean? = null
        var locks: Boolean? = null
        var engine: Boolean? = null
        var balance: Double? = null
        var money: Double? = null
        var greeting = false
        var update = false
        var pin: String? = null
        var gprsNa = false
        var alarm = false
        val fields = mutableListOf<Pair<String, String>>()
        val unknown = mutableListOf<String>()
    }

    fun parse(raw: String): ParsedResponse {
        // Неразрывные пробелы → обычные, длина строки не меняется.
        val text = raw.replace(' ', ' ')
        val acc = Acc()
        val free = mutableListOf<String>()

        val keys = KEY.findAll(text).toList()
        if (keys.isEmpty()) {
            free += text
        } else {
            free += text.substring(0, keys[0].range.first)
            keys.forEachIndexed { i, m ->
                val keyName = m.groupValues[1].ifEmpty { m.groupValues[2] }.uppercase()
                val regionStart = m.range.last + 1
                val regionEnd = if (i + 1 < keys.size) keys[i + 1].range.first else text.length
                val region = text.substring(regionStart, regionEnd)
                val used = parseValue(keyName, region, acc)
                free += region.substring(used)
            }
        }
        free.forEach { analyzeFree(it, acc) }

        val fw = FwVersion.parse(acc.fwRaw)
        return ParsedResponse(
            raw = raw,
            fwRaw = acc.fwRaw,
            fw = fw,
            hwCode = FwVersion.hardwareCode(acc.fwRaw),
            gsmLevel = acc.gsmLevel,
            gsmNet = acc.gsmNet,
            linkRaw = acc.linkRaw,
            link = LinkStatus.of(acc.linkRaw),
            battery = acc.battery,
            mcc = acc.mcc,
            mnc = acc.mnc,
            lac = acc.lac,
            ci = acc.ci,
            imei = acc.imei,
            result = acc.result,
            errorText = acc.errorText,
            armed = acc.armed,
            locksClosed = acc.locks,
            engineRunning = acc.engine,
            balance = acc.balance,
            moneyAmount = acc.money,
            greeting = acc.greeting,
            updateMention = acc.update,
            pinCode = acc.pin,
            gprsUnavailable = acc.gprsNa,
            alarm = acc.alarm,
            fields = acc.fields.toList(),
            unknown = acc.unknown.toList()
        )
    }

    /** Разбирает значение после ключа; возвращает число использованных символов области. */
    private fun parseValue(key: String, region: String, acc: Acc): Int {
        fun take(re: Regex, block: (MatchResult) -> Unit): Int {
            val m = re.find(region) ?: return 0
            block(m)
            acc.fields += key to m.value.trim()
            return m.range.last + 1
        }
        return when {
            key == "VER" -> take(V_VER) { acc.fwRaw = it.groupValues[1].replace(" ", "").uppercase() }
            key == "GSM" || key == "4G" || key == "3G" || key == "2G" -> take(V_GSM) {
                acc.gsmLevel = it.groupValues[1].toIntOrNull()
                acc.gsmNet = key
                acc.linkRaw = it.groupValues[2].firstOrNull()
            }
            key == "BAT" -> take(V_BAT) { acc.battery = it.groupValues[1].replace(',', '.').toDoubleOrNull() }
            key == "MCC" -> take(V_DEC) { acc.mcc = it.groupValues[1].toIntOrNull() }
            key == "MNC" -> take(V_DEC) { acc.mnc = it.groupValues[1].toIntOrNull() }
            key == "LAC" -> take(V_HEX) { acc.lac = it.groupValues[1].uppercase() }
            key == "CI" -> take(V_HEX) { acc.ci = it.groupValues[1].uppercase() }
            key == "IMEI" -> take(V_IMEI) { acc.imei = it.groupValues[1] }
            key == "PW" || key == "PS" -> take(V_PIN4) { }
            key.startsWith("M") -> take(V_PHONE) { }
            else -> 0
        }
    }

    private fun analyzeFree(segment: String, acc: Acc) {
        if (segment.isBlank()) return
        val used = BooleanArray(segment.length)

        fun each(re: Regex, consume: Boolean = true, block: (MatchResult) -> Unit) {
            for (m in re.findAll(segment)) {
                if (m.range.isEmpty()) continue
                if (m.range.any { used[it] }) continue
                block(m)
                if (consume) m.range.forEach { used[it] = true }
            }
        }

        each(P_GREETING) { acc.greeting = true }
        each(P_PIN) { acc.pin = it.groupValues[1] }
        each(P_GPRS_NA) { acc.gprsNa = true }
        each(P_ERROR) {
            acc.result = CmdResult.ERROR
            acc.errorText = it.value.trim()
        }
        each(P_ERROR_WORD) {
            acc.result = CmdResult.ERROR
            if (acc.errorText == null) acc.errorText = it.value.trim()
        }
        each(P_FAILED) { if (acc.result != CmdResult.ERROR) acc.result = CmdResult.FAILED }
        each(P_OK) { if (acc.result == null) acc.result = CmdResult.OK }
        each(P_ARMED) { acc.armed = !it.groupValues[1].lowercase().startsWith("выкл") }
        each(P_LOCKS) { acc.locks = it.groupValues[1].lowercase().startsWith("закр") }
        each(P_ENGINE_OFF) { acc.engine = false }
        each(P_ENGINE_ON) { acc.engine = true }
        // Берём последнюю сумму после «баланс»: в «Баланс ниже порога. Баланс: 33р» нужна вторая.
        each(P_BALANCE) { acc.balance = money(it.groupValues[1]) }
        if (acc.balance == null) each(P_MONEY) { if (acc.money == null) acc.money = money(it.groupValues[1]) }
        each(P_UPDATE, consume = false) { acc.update = true }
        each(P_ALARM, consume = false) { acc.alarm = true }

        // Нераспознанные куски — как есть.
        var start = -1
        for (i in 0..segment.length) {
            val free = i < segment.length && !used[i]
            if (free && start < 0) start = i
            if (!free && start >= 0) {
                val piece = segment.substring(start, i).trim()
                if (piece.isNotEmpty() && !JUNK.matches(piece)) acc.unknown += piece
                start = -1
            }
        }
    }

    private fun money(s: String): Double? =
        s.replace('−', '-').replace(" ", "").replace(',', '.').toDoubleOrNull()
}
