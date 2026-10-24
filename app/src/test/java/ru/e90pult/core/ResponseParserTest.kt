package ru.e90pult.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseParserTest {

    private fun check09(p: ParsedResponse) {
        assertEquals("I7", p.fwRaw)
        assertEquals(FwVersion('I', 7), p.fw)
        assertEquals(11, p.gsmLevel)
        assertEquals(LinkStatus.G, p.link)
        assertEquals(12.78, p.battery!!, 0.0001)
        assertEquals(250, p.mcc)
        assertEquals(2, p.mnc)
        assertEquals("147C", p.lac)
        assertEquals("CE91", p.ci)
        assertTrue(p.unknown.toString(), p.unknown.isEmpty())
    }

    @Test fun status09_spaced() = check09(ResponseParser.parse("VER:I7 GSM:11(G) BAT:12.78V MCC:250 MNC:02 LAC:147C CI:CE91"))

    @Test fun status09_glued() = check09(ResponseParser.parse("VER:I7GSM:11(G)BAT:12.78VMCC:250MNC:02LAC:147CCI:CE91"))

    @Test fun status09_newlines() = check09(ResponseParser.parse("VER:I7\nGSM:11(G)\r\nBAT:12.78V\nMCC:250\nMNC:02\nLAC:147C\nCI:CE91\n"))

    @Test fun commandDone() {
        val p = ResponseParser.parse("Команда выполнена GSM:22(T)")
        assertEquals(CmdResult.OK, p.result)
        assertEquals(22, p.gsmLevel)
        assertEquals(LinkStatus.T, p.link)
        assertTrue(p.unknown.isEmpty())
    }

    @Test fun commandDone_glued() {
        val p = ResponseParser.parse("КомандавыполненаGSM:22(T)")
        assertEquals(CmdResult.OK, p.result)
        assertEquals(22, p.gsmLevel)
    }

    @Test fun commandFailed_glued() {
        val p = ResponseParser.parse("GSM:17(-)Команда не выполненаОхрана выкл.Замки откр.Откр.")
        assertEquals(17, p.gsmLevel)
        assertEquals(LinkStatus.NONE, p.link)
        assertEquals(CmdResult.FAILED, p.result)
        assertEquals(false, p.armed)
        assertEquals(false, p.locksClosed)
        assertEquals(listOf("Откр."), p.unknown)
    }

    @Test fun commandFailed_fullyGlued() {
        val p = ResponseParser.parse("GSM:17(-)КоманданевыполненаОхранавыкл.Замкиоткр.Откр.")
        assertEquals(CmdResult.FAILED, p.result)
        assertEquals(false, p.armed)
        assertEquals(false, p.locksClosed)
        assertEquals(listOf("Откр."), p.unknown)
    }

    @Test fun cyrillicUpperCase() {
        val p = ResponseParser.parse("ОХРАНА ВКЛ. ЗАМКИ ЗАКР.")
        assertEquals(true, p.armed)
        assertEquals(true, p.locksClosed)
    }

    @Test fun unknownFieldsKeptRaw() {
        val p = ResponseParser.parse("GSM:20(T) TEMP:-5C Что-то новое")
        assertEquals(20, p.gsmLevel)
        assertTrue(p.unknown.toString(), p.unknown.any { it.contains("TEMP:-5C") })
        assertTrue(p.unknown.any { it.contains("Что-то новое") })
    }

    @Test fun greetingWithHardwareVersion() {
        val p = ResponseParser.parse("STARLINE GPS ПРИВЕТСТВУЕТ ВАС!\nVER:HD03-R5\nBAT:13.75V\n4G:21\nM111:+79111234455\nPW:1234\nPS:4321")
        assertTrue(p.greeting)
        assertEquals(FwVersion('R', 5), p.fw)
        assertEquals("HD03", p.hwCode)
        assertEquals(21, p.gsmLevel)
        assertEquals("4G", p.gsmNet)
        assertNull(p.link)
        assertEquals(13.75, p.battery!!, 0.001)
        assertTrue(p.unknown.toString(), p.unknown.isEmpty())
    }

    @Test fun greetingGlued() {
        val p = ResponseParser.parse("StarLineприветствуетВас!VER:M8GSM:11(G)BAT:12.78V")
        assertTrue(p.greeting)
        assertEquals(FwVersion('M', 8), p.fw)
        assertEquals(11, p.gsmLevel)
    }

    @Test fun noFalseKeysInsideWords() {
        val p = ResponseParser.parse("GPS: нет данных, SERVER: недоступен")
        assertNull(p.fw)
        assertTrue(p.fields.isEmpty())
    }

    @Test fun balance_docAlert() {
        val p = ResponseParser.parse("Внимание! Баланс ниже порога. Баланс: 33р")
        assertEquals(33.0, p.balance!!, 0.001)
    }

    @Test fun balance_variants() {
        assertEquals(123.45, ResponseParser.parse("Баланс:123,45р").balance!!, 0.001)
        assertEquals(-15.2, ResponseParser.parse("Ваш баланс -15.20 руб.").balance!!, 0.001)
        assertEquals(150.5, ResponseParser.parse("Баланс 150,50 ₽, лимит 300р").balance!!, 0.001)
        val noWord = ResponseParser.parse("Остаток 77,10р. Спасибо")
        assertNull(noWord.balance)
        assertEquals(77.1, noWord.balanceForBalanceReply!!, 0.001)
        assertNull(ResponseParser.parse("Повторите 3 раза").balanceForBalanceReply)
    }

    @Test fun errorText() {
        val p = ResponseParser.parse("Ошибка набора команды SMS, мин. время 10 минут")
        assertEquals(CmdResult.ERROR, p.result)
        assertEquals("Ошибка набора команды SMS, мин. время 10 минут", p.errorText)
        assertTrue(p.unknown.isEmpty())
    }

    @Test fun pinCode() {
        val p = ResponseParser.parse("Код регистрации на сервере starline-online.ru: 123456")
        assertEquals("123456", p.pinCode)
    }

    @Test fun engineOnlyByExplicitWords() {
        assertEquals(true, ResponseParser.parse("Двигатель запущен").engineRunning)
        assertEquals(false, ResponseParser.parse("Двигатель не запущен").engineRunning)
        assertNull(ResponseParser.parse("Команда выполнена GSM:22(T)").engineRunning)
    }

    @Test fun updateMentionKeepsRaw() {
        val p = ResponseParser.parse("Идёт обновление ПО FZ12 - I8")
        assertTrue(p.updateMention)
        assertFalse(p.unknown.isEmpty())
    }

    @Test fun alarmKeywords() {
        // Слова «тревога»/«сработка» в любом SMS поднимают флаг; текст при этом сохраняется целиком.
        assertTrue(ResponseParser.parse("ТРЕВОГА").alarm)
        assertTrue(ResponseParser.parse("сработка").alarm)
        assertFalse(ResponseParser.parse("VER:I7 GSM:11(G) BAT:12.78V MCC:250 MNC:02 LAC:147C CI:CE91").alarm)
        assertFalse(ResponseParser.parse(ReplyEmulator.FAILURE_SAMPLE).alarm)
        assertFalse(ResponseParser.parse("Команда выполнена GSM:22(T)").alarm)
    }

    @Test fun nonBreakingSpaces() {
        val p = ResponseParser.parse("Команда выполнена GSM:22(T)")
        assertEquals(CmdResult.OK, p.result)
        assertEquals(22, p.gsmLevel)
    }
}
