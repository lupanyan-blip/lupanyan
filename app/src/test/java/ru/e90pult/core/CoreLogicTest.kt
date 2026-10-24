package ru.e90pult.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class CoreLogicTest {

    @Test fun versions() {
        val i7 = FwVersion.parse("I7")!!
        val m8 = FwVersion.parse("M8")!!
        val p5 = FwVersion.parse("P5")!!
        val q2 = FwVersion.parse("Q2")!!
        assertTrue(i7 < m8 && m8 < p5 && p5 < q2)
        assertTrue(FwVersion.parse("I10")!! > i7)
        assertEquals(FwVersion('I', 4), FwVersion.parse("i4"))
        assertEquals(FwVersion('R', 5), FwVersion.parse("HD03-R5"))
        assertEquals(FwVersion('I', 3), FwVersion.parse("FZ12 - I3"))
        assertEquals("FZ12", FwVersion.hardwareCode("FZ12 - I3"))
        assertNull(FwVersion.parse("FG63"))
        assertNull(FwVersion.parse(""))
        assertTrue(FwVersion.isAtLeast(m8, m8))
        assertFalse(FwVersion.isAtLeast(null, m8))
        assertTrue(FwVersion.isAtLeast(null, null))
    }

    @Test fun phones() {
        assertEquals("+79161234567", Phone.normalize("8 (916) 123-45-67"))
        assertEquals("+79161234567", Phone.normalize("+7 916 123 45 67"))
        assertEquals("+79161234567", Phone.normalize("79161234567"))
        assertEquals("+79161234567", Phone.normalize("9161234567"))
        assertEquals("+380501234567", Phone.normalize("+380501234567"))
        assertEquals("MegaFon", Phone.normalize(" MegaFon "))
        assertTrue(Phone.same("89161234567", "+79161234567"))
        assertFalse(Phone.same("89161234567", "+79161234568"))
        assertFalse(Phone.same("", "+79161234567"))
        assertEquals("+7 916 123-45-67", Phone.pretty("89161234567"))
        assertTrue(Phone.isValid("+79161234567"))
        assertFalse(Phone.isValid("123"))
    }

    private val noPw = SendOptions(passwordMode = false, password = null, appendHash = false)

    @Test fun commandTextBasics() {
        val c09 = CommandDef("09", "Статус", "Информация", template = "09{PW}")
        assertEquals(Built.Ok("09"), CommandText.build(c09, emptyMap(), noPw))
        assertEquals(Built.Ok("09#"), CommandText.build(c09, emptyMap(), noPw.copy(appendHash = true)))
        val pw = noPw.copy(passwordMode = true, password = "1234")
        assertEquals(Built.Ok("09001234"), CommandText.build(c09, emptyMap(), pw))
        assertTrue(CommandText.build(c09, emptyMap(), pw.copy(password = "12")) is Built.Error)
    }

    @Test fun commandTextParams() {
        val fwd = CommandDef("0069X", "Пересылка", "Сервис", template = "0069{X}",
            params = listOf(ParamSpec("X", "Минуты", min = 0, max = 99)), m1Only = true)
        assertEquals(Built.Ok("00695"), CommandText.build(fwd, mapOf("X" to "5"), noPw))
        assertTrue(CommandText.build(fwd, mapOf("X" to "100"), noPw) is Built.Error)
        assertTrue(CommandText.build(fwd, mapOf("X" to "5"), noPw.copy(passwordMode = true, password = "1234")) is Built.Error)
        val hist = CommandDef("01NN", "История", "Информация", template = "01{PW}{NN}",
            params = listOf(ParamSpec("NN", "Записей", min = 1, max = 8, width = 2)))
        assertEquals(Built.Ok("0100123405#"),
            CommandText.build(hist, mapOf("NN" to "5"), SendOptions(true, "1234", appendHash = true)))
        val run = CommandDef("21XX#", "Запуск на XX", "Двигатель", template = "21{XX}#",
            params = listOf(ParamSpec("XX", "Минуты", min = 10, max = 60, width = 2)))
        assertEquals(Built.Ok("2150#"), CommandText.build(run, mapOf("XX" to "50"), noPw.copy(appendHash = true)))
    }

    @Test fun commandTextSpecial() {
        val off = CommandDef("1000PW", "Выкл. антиограбление", "Охрана", template = "1000{PWRAW}",
            kind = CommandKind.ANTIHIJACK_OFF)
        assertEquals(Built.Ok("10004321"), CommandText.build(off, emptyMap(), noPw.copy(password = "4321")))
        assertTrue(CommandText.build(off, emptyMap(), noPw) is Built.Error)
        val bal = CommandDef("*100#", "Баланс", "Баланс", template = "{USSD}", kind = CommandKind.USSD_BALANCE)
        assertEquals(Built.Ok("*100#"), CommandText.build(bal, emptyMap(), noPw.copy(appendHash = true)))
        assertTrue(CommandText.build(bal, emptyMap(), noPw.copy(ussd = "100")) is Built.Error)
        val m2 = CommandDef("0002XY00M2", "Номер M2", "Номера", template = "0002{X}{Y}00{PHONE}",
            params = listOf(ParamSpec("X", "Голос", max = 2), ParamSpec("Y", "SMS", max = 1),
                ParamSpec("PHONE", "Номер", type = ParamType.PHONE)), hashAllowed = false, m1Only = true)
        assertEquals(Built.Ok("00021100+79111211343"),
            CommandText.build(m2, mapOf("X" to "1", "Y" to "1", "PHONE" to "89111211343"), noPw.copy(appendHash = true)))
        val bad = CommandDef("X", "Плохой", "Сервис", template = "12{Z}")
        assertTrue(CommandText.build(bad, emptyMap(), noPw) is Built.Error)
    }

    @Test fun csv() {
        val csv = CsvExcel(ZoneId.of("Europe/Moscow"))
        csv.header("Дата", "Время", "Номер", "Текст", "АКБ", "CI")
        // 24.10.2026 14:28:05 МСК = 11:28:05 UTC
        val t = java.time.ZonedDateTime.of(2026, 10, 24, 14, 28, 5, 0, ZoneId.of("Europe/Moscow")).toInstant().toEpochMilli()
        csv.row(listOf(Cell.Date(t), Cell.Time(t), Cell.Protected("+79161234567"),
            Cell.Text("a;b \"c\"\nd"), Cell.Num(12.775), Cell.Protected("147E3")))
        csv.row(listOf(Cell.Text("12.10"), Cell.Text("=SUM(A1)"), Cell.Text("*100#"), Cell.Text(null), Cell.Num(null), Cell.Integer(7)))
        val text = csv.text()
        val lines = text.split("\r\n")
        assertEquals("Дата;Время;Номер;Текст;АКБ;CI", lines[0])
        assertEquals("24.10.2026;14:28:05;\"=\"\"+79161234567\"\"\";\"a;b \"\"c\"\"\nd\";12,78;\"=\"\"147E3\"\"\"", lines[1])
        assertTrue(text.endsWith("\r\n"))
        assertEquals("\"=\"\"12.10\"\"\";\"=\"\"=SUM(A1)\"\"\";*100#;;;7", lines[2])
        val bytes = csv.bytes()
        assertEquals(0xEF.toByte(), bytes[0]); assertEquals(0xBB.toByte(), bytes[1]); assertEquals(0xBF.toByte(), bytes[2])
    }

    private val defs = listOf(
        CommandDef("09", "Статус", "Информация", template = "09{PW}"),
        CommandDef("0069X", "Пересылка", "Сервис", minFw = "M8", critical = true, template = "0069{X}",
            params = listOf(ParamSpec("X", "Мин", max = 99)), m1Only = true),
        CommandDef("21XX#", "Запуск на XX", "Двигатель", minFw = "P5", notApplicable = "для E90 неприменимо"),
        CommandDef("37", "Багажник", "Сервис", minFw = "Q2"),
        CommandDef("31", "Антиограбление", "Охрана", critical = true, dangerous = true, m1Only = true),
        CommandDef("20", "Стоп", "Двигатель", template = "20{PW}"),
        CommandDef("1000PW", "Выкл. антиограбление", "Охрана", critical = true, template = "1000{PWRAW}",
            kind = CommandKind.ANTIHIJACK_OFF)
    )

    private fun ctx(fw: String? = "I7", moving: Boolean = false, inCar: Boolean = false, pwMode: Boolean = false, hasPw: Boolean = true) =
        CommandContext(FwVersion.parse(fw), pwMode, hasPw, moving, inCar)

    private fun av(code: String, c: CommandContext) = Rules.availability(defs.first { it.code == code }, c)

    @Test fun availability() {
        assertEquals(Availability.Enabled, av("09", ctx()))
        assertEquals(BlockKind.FIRMWARE, (av("0069X", ctx()) as Availability.Disabled).kind)
        assertEquals(Availability.Enabled, av("0069X", ctx(fw = "M8")))
        assertEquals(BlockKind.FIRMWARE_UNKNOWN, (av("0069X", ctx(fw = null)) as Availability.Disabled).kind)
        assertEquals(BlockKind.NOT_APPLICABLE, (av("21XX#", ctx(fw = "Q2")) as Availability.Disabled).kind)
        assertEquals(BlockKind.MOTION, (av("31", ctx(moving = true)) as Availability.Disabled).kind)
        assertEquals(BlockKind.MOTION, (av("31", ctx(inCar = true)) as Availability.Disabled).kind)
        assertEquals(BlockKind.MOTION, (av("20", ctx(moving = true)) as Availability.Disabled).kind)
        // «Стоп» по Bluetooth не блокируется намеренно: при автозапуске магнитола может включиться и телефон
        // подключится к ней — заглушить прогреваемый двигатель нужно и сидя в машине. В движении — блок по GPS.
        assertEquals(Availability.Enabled, av("20", ctx(inCar = true)))
        assertEquals(Availability.Enabled, av("1000PW", ctx(moving = true, inCar = true)))
        assertEquals(BlockKind.PASSWORD, (av("1000PW", ctx(hasPw = false)) as Availability.Disabled).kind)
        assertEquals(BlockKind.M1_ONLY, (av("31", ctx(pwMode = true)) as Availability.Disabled).kind)
        assertEquals(BlockKind.PASSWORD, (av("20", ctx(pwMode = true, hasPw = false)) as Availability.Disabled).kind)
    }

    @Test fun firmwareNews() {
        val news = FirmwareNews.newlyAvailable(defs, FwVersion.parse("I7"), FwVersion.parse("P5")!!)
        assertEquals(listOf("0069X", "21XX#"), news.map { it.code })
        assertTrue(FirmwareNews.newlyAvailable(defs, FwVersion.parse("Q2"), FwVersion.parse("Q2")!!).isEmpty())
    }

    @Test fun matching() {
        val a = PendingRun(1, 1_000_000, 1_300_000)
        val b = PendingRun(2, 1_100_000, 1_400_000)
        assertEquals(1L, Matching.pick(listOf(b, a), 1_150_000)?.id)
        assertEquals(2L, Matching.pick(listOf(a, b), 1_350_000)?.id)
        assertNull(Matching.pick(listOf(a, b), 1_500_000))
        // Допуск на часы — 1 с: SMS, принятое за 5 с до отправки, ответом быть не может.
        assertEquals(1L, Matching.pick(listOf(a), 999_500)?.id)
        assertNull(Matching.pick(listOf(a), 995_000))
        assertEquals(2L, Matching.lateCandidate(listOf(a, b), 1_450_000, 600_000)?.id)
    }

    @Test fun balanceEstimate() {
        val day = BalanceMath.DAY_MS
        val e = BalanceMath.estimate(BalanceInputs(
            lastAmount = 200.0, lastAt = 0, moduleSmsParts = 10, smsPrice = 2.5,
            monthlyFee = 365.25 / 12 * 10, trafficPerDay = 1.0, adjustments = 50.0, now = 3 * day))
        assertEquals(3.0, e.days, 1e-9)
        assertEquals(25.0, e.smsCost, 1e-9)
        assertEquals(30.0, e.feeCost, 1e-9)
        assertEquals(3.0, e.trafficCost, 1e-9)
        assertEquals(192.0, e.value, 1e-9)
    }

    @Test fun signalColors() {
        assertEquals(SignalQuality.BAD, Signal.quality(14))
        assertEquals(SignalQuality.MEDIUM, Signal.quality(15))
        assertEquals(SignalQuality.MEDIUM, Signal.quality(20))
        assertEquals(SignalQuality.GOOD, Signal.quality(21))
    }

    @Test fun observations() {
        val p = ResponseParser.parse("VER:I7 GSM:11(G) BAT:12.78V MCC:250 MNC:02 LAC:147C CI:CE91")
        val obs = Observations.from(p, isBalanceReply = false).associateBy { it.key }
        assertEquals("12.78", obs[ObsKey.BATTERY]?.text)
        assertEquals("02", obs[ObsKey.MNC]?.text)
        assertEquals("G", obs[ObsKey.GSM_LINK]?.text)
        assertNull(obs[ObsKey.BALANCE])
        val bal = Observations.from(ResponseParser.parse("Остаток 77,10р"), isBalanceReply = true)
        assertEquals(77.1, bal.first { it.key == ObsKey.BALANCE }.num!!, 1e-9)
        assertEquals("-0.50", Observations.fmt2(-0.5))
    }

    @Test fun emulatorUsesSamples() {
        val emu = ReplyEmulator(kotlin.random.Random(1))
        val st = CommandDef("09", "Статус", "Информация", kind = CommandKind.STATUS)
        val reply = emu.reply(st, EmuMode.GLUED, "I7")!!
        val p = ResponseParser.parse(reply)
        assertEquals(FwVersion('I', 7), p.fw)
        assertEquals("CE91", p.ci)
        assertNull(emu.reply(st, EmuMode.SILENT, "I7"))
        assertEquals(CmdResult.FAILED, ResponseParser.parse(emu.reply(null, EmuMode.FAILURE, "I7")!!).result)
        assertTrue(ResponseParser.parse(emu.greeting("M8")).greeting)
    }
}
