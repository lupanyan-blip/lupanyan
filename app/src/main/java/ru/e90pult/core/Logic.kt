package ru.e90pult.core

import kotlin.random.Random

/** Ожидающая ответа команда. */
data class PendingRun(val id: Long, val sentAt: Long, val deadline: Long)

object Matching {
    /** Время отправки и получения берутся с часов телефона: допуск только на округление. */
    const val CLOCK_SKEW_MS = 1_000L

    /** Первый ответ в окне ожидания привязывается к самой ранней ожидающей команде, в окно которой попал. */
    fun pick(pending: List<PendingRun>, receivedAt: Long): PendingRun? =
        pending.filter { receivedAt >= it.sentAt - CLOCK_SKEW_MS && receivedAt <= it.deadline }
            .minByOrNull { it.sentAt }

    /** Команда, ответ на которую мог прийти с опозданием (после окна, но в пределах [lateWindowMs]). */
    fun lateCandidate(recent: List<PendingRun>, receivedAt: Long, lateWindowMs: Long): PendingRun? =
        recent.filter { receivedAt > it.deadline && receivedAt <= it.deadline + lateWindowMs }
            .maxByOrNull { it.sentAt }
}

data class CommandContext(
    val moduleFw: FwVersion?,
    val passwordMode: Boolean,
    val hasPassword: Boolean,
    /** Скорость по GPS телефона выше порога. */
    val moving: Boolean,
    /** Телефон подключён по Bluetooth к выбранному устройству машины. */
    val inCar: Boolean
)

enum class BlockKind { NOT_APPLICABLE, FIRMWARE, FIRMWARE_UNKNOWN, M1_ONLY, PASSWORD, MOTION }

sealed class Availability {
    object Enabled : Availability()
    data class Disabled(val kind: BlockKind, val reason: String) : Availability()
}

object Rules {
    const val STOP_CODE = "20"
    const val ANTIHIJACK_CODE = "31"
    const val ENGINE_START_CODE = "21"

    fun availability(def: CommandDef, ctx: CommandContext): Availability {
        def.notApplicable?.let { return Availability.Disabled(BlockKind.NOT_APPLICABLE, it) }
        val req = def.minFwVersion
        if (req != null) {
            val fw = ctx.moduleFw
                ?: return Availability.Disabled(
                    BlockKind.FIRMWARE_UNKNOWN,
                    "Нужно ПО модуля $req или новее. Версия модуля неизвестна — отправьте 09."
                )
            if (fw < req) {
                return Availability.Disabled(BlockKind.FIRMWARE, "Нужно ПО модуля $req или новее, у модуля $fw.")
            }
        }
        if (def.m1Only && ctx.passwordMode) {
            return Availability.Disabled(BlockKind.M1_ONLY, "Только с номера М1 без пароля.")
        }
        val needsPw = def.needsPasswordValue || (ctx.passwordMode && def.template.contains("{PW}"))
        if (needsPw && !ctx.hasPassword) {
            return Availability.Disabled(BlockKind.PASSWORD, "Укажите пароль PW в профиле.")
        }
        if (def.kind != CommandKind.ANTIHIJACK_OFF) {
            if (def.critical && ctx.moving) {
                return Availability.Disabled(BlockKind.MOTION, "Недоступно в движении.")
            }
            if (def.critical && ctx.inCar) {
                return Availability.Disabled(
                    BlockKind.MOTION,
                    "Недоступно, пока телефон подключён к машине по Bluetooth."
                )
            }
            if (def.code == STOP_CODE && ctx.moving) {
                return Availability.Disabled(BlockKind.MOTION, "Недоступно в движении.")
            }
        }
        return Availability.Enabled
    }
}

object FirmwareNews {
    /** Команды, ставшие доступными при переходе с [old] на [new]. */
    fun newlyAvailable(defs: List<CommandDef>, old: FwVersion?, new: FwVersion): List<CommandDef> =
        defs.filter { d ->
            val m = d.minFwVersion ?: return@filter false
            (old == null || m > old) && m <= new
        }
}

enum class EmuMode(val title: String) {
    SUCCESS("Успех"),
    FAILURE("Неудача"),
    SILENT("Нет ответа"),
    GLUED("Склеенный текст")
}

/**
 * Эмулятор ответов для режима отладки без машины. Использует только образцы из ТЗ
 * и документации StarLine (баланс — формат оповещения Q9, приветствие — формат из документации).
 */
class ReplyEmulator(private val random: Random = Random.Default) {

    fun reply(def: CommandDef?, mode: EmuMode, fw: String): String? {
        if (mode == EmuMode.SILENT) return null
        val level = 9 + random.nextInt(14)
        val link = LINKS[random.nextInt(LINKS.size)]
        val bat = Observations.fmt2(12.30 + random.nextInt(60) / 100.0)
        val text = when {
            mode == EmuMode.FAILURE -> FAILURE_SAMPLE
            def?.kind == CommandKind.STATUS ->
                "VER:$fw GSM:$level($link) BAT:${bat}V MCC:250 MNC:02 LAC:147C CI:CE91"
            def?.kind == CommandKind.USSD_BALANCE -> "Баланс: ${80 + random.nextInt(220)}р"
            else -> "Команда выполнена GSM:$level($link)"
        }
        return if (mode == EmuMode.GLUED) text.replace(" ", "") else text
    }

    fun greeting(newFw: String): String = "StarLine приветствует Вас! VER:$newFw GSM:11(G) BAT:12.78V"

    /** Входящее без отправленной команды — для проверки событий/тревог. */
    fun unsolicited(): String = "VER:I7 GSM:11(G) BAT:12.78V MCC:250 MNC:02 LAC:147C CI:CE91"

    companion object {
        const val FAILURE_SAMPLE = "GSM:17(-)Команда не выполненаОхрана выкл.Замки откр.Откр."
        private val LINKS = listOf("G", "T", "-", "A")
    }
}
