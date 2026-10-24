package ru.e90pult.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtraTest {

    @Test fun cellUrlConvertsHexToDecimal() {
        val url = CellLookup.url("tok en", 250, 2, "147C", "CE91")!!
        assertTrue(url, url.contains("lac=5244"))
        assertTrue(url, url.contains("cellid=52881"))
        assertTrue(url, url.contains("mnc=2"))
        assertTrue(url, url.contains("key=tok+en"))
        assertNull(CellLookup.url("t", 250, 2, "ZZ", "CE91"))
    }

    @Test fun cellParse() {
        val ok = CellLookup.parse("""{"lon":44.0123,"lat":56.3269,"mcc":250,"mnc":2,"lac":5244,"cellid":52881,"range":1200,"samples":7,"radio":"GSM"}""", 200)
        ok as CellResult.Ok
        assertEquals(56.3269, ok.location.lat, 1e-9)
        assertEquals(1200, ok.location.range)
        val err = CellLookup.parse("""{"error":"Cell not found","code":1}""", 200)
        assertTrue(err is CellResult.Error && err.message.contains("Cell not found"))
        assertTrue(CellLookup.parse("<html>", 502) is CellResult.Error)
    }

    @Test fun describeShort() {
        val p = ResponseParser.parse("GSM:17(-)Команда не выполненаОхрана выкл.Замки откр.Откр.")
        assertEquals("Команда не выполнена · охрана выкл. · замки открыты · GSM 17 (-) · Откр.", Describe.short(p))
        val s = ResponseParser.parse("VER:I7 GSM:11(G) BAT:12.78V MCC:250 MNC:02 LAC:147C CI:CE91")
        assertEquals("ПО I7 · GSM 11 (G) · АКБ 12,78 В", Describe.short(s))
        val b = ResponseParser.parse("Остаток 77,10р")
        // Нераспознанное слово «Остаток» показывается как есть.
        assertEquals("баланс 77,10 ₽ · Остаток", Describe.short(b, isBalanceReply = true))
    }
}
