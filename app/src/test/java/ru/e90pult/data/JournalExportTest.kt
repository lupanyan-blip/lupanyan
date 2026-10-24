package ru.e90pult.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class JournalExportTest {

    @Test fun csvForRussianExcel() {
        val zone = ZoneId.of("Europe/Moscow")
        val t = ZonedDateTime.of(2026, 10, 12, 14, 27, 3, 0, zone).toInstant().toEpochMilli()
        val sms = listOf(
            SmsEntity(id = 1, profileId = 1, outgoing = true, phone = "+79001112233", text = "09", timestamp = t,
                kind = SmsKind.COMMAND, runId = 5, status = SmsStatus.DELIVERED),
            SmsEntity(id = 2, profileId = 1, outgoing = false, phone = "+79001112233",
                text = "VER:I7 GSM:11(G) BAT:12.78V MCC:250 MNC:02 LAC:147C CI:CE91", timestamp = t + 60_000,
                kind = SmsKind.REPLY, runId = 5, status = SmsStatus.RECEIVED)
        )
        val runs = listOf(CommandRunEntity(id = 5, profileId = 1, code = "09", name = "Статус", smsText = "09",
            kind = "STATUS", control = false, createdAt = t, deadline = t + 300_000, state = RunState.REPLIED))
        val bytes = JournalExport.build(sms, runs, "Koleos", zone)
        assertEquals(0xEF.toByte(), bytes[0])
        val text = String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        val lines = text.split("\r\n")
        assertEquals(4, lines.size) // заголовок, 2 строки, пустой хвост после CRLF
        assertTrue(lines[0].startsWith("Дата;Время;Профиль;Направление;Номер"))
        assertEquals(
            "12.10.2026;14:27:03;Koleos;Отправлено;\"=\"\"+79001112233\"\"\";Команда;\"=\"\"09\"\"\";Доставлено;1;\"=\"\"09\"\"\";;;;;;;;;;",
            lines[1]
        )
        assertTrue(lines[2], lines[2].contains(";11;G;12,78;I7;\"=\"\"250\"\"\";\"=\"\"02\"\"\";\"=\"\"147C\"\"\";\"=\"\"CE91\"\"\";"))
    }
}
