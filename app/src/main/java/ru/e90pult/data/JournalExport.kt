package ru.e90pult.data

import ru.e90pult.core.Cell
import ru.e90pult.core.CsvExcel
import ru.e90pult.core.ResponseParser
import java.time.ZoneId

/** Экспорт журнала SMS в CSV под русский Excel. */
object JournalExport {

    fun statusTitle(s: SmsEntity): String = when (s.status) {
        SmsStatus.SENDING -> "Отправляется"
        SmsStatus.SENT -> "Отправлено"
        SmsStatus.SEND_FAILED -> "Ошибка отправки"
        SmsStatus.DELIVERED -> "Доставлено"
        SmsStatus.DELIVERY_FAILED -> "Не доставлено"
        SmsStatus.RECEIVED -> "Получено"
        else -> s.status
    }

    fun kindTitle(kind: String): String = when (kind) {
        SmsKind.COMMAND -> "Команда"
        SmsKind.REPLY -> "Ответ"
        SmsKind.EVENT -> "Событие/тревога"
        SmsKind.UPDATE -> "Обновление ПО"
        else -> kind
    }

    fun build(sms: List<SmsEntity>, runs: List<CommandRunEntity>, profileName: String, zone: ZoneId = ZoneId.systemDefault()): ByteArray {
        val codes = runs.associate { it.id to it.code }
        val csv = CsvExcel(zone)
        csv.header(
            "Дата", "Время", "Профиль", "Направление", "Номер", "Тип", "Команда", "Статус", "Частей",
            "Текст", "Уровень GSM", "Связь", "АКБ, В", "Версия ПО", "MCC", "MNC", "LAC", "CI", "Эмуляция", "Примечание"
        )
        for (s in sms) {
            val p = if (!s.outgoing) ResponseParser.parse(s.text) else null
            csv.row(
                listOf(
                    Cell.Date(s.timestamp),
                    Cell.Time(s.timestamp),
                    Cell.Text(profileName),
                    Cell.Text(if (s.outgoing) "Отправлено" else "Получено"),
                    Cell.Protected(s.phone),
                    Cell.Text(kindTitle(s.kind)),
                    Cell.Protected(s.runId?.let { codes[it] }),
                    Cell.Text(statusTitle(s)),
                    Cell.Integer(s.parts.toLong()),
                    Cell.Text(s.text),
                    Cell.Integer(p?.gsmLevel?.toLong()),
                    Cell.Text(p?.linkRaw?.toString()),
                    Cell.Num(p?.battery),
                    Cell.Text(p?.fwRaw),
                    Cell.Protected(p?.mcc?.toString()),
                    Cell.Protected(p?.mnc?.toString()?.padStart(2, '0')),
                    Cell.Protected(p?.lac),
                    Cell.Protected(p?.ci),
                    Cell.Text(if (s.emulated) "да" else ""),
                    Cell.Text(s.note)
                )
            )
        }
        return csv.bytes()
    }
}
