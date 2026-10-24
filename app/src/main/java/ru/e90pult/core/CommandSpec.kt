package ru.e90pult.core

enum class ParamType { INT, PHONE }

/** Параметр команды: {X} в шаблоне. Для INT — диапазон и ширина с ведущими нулями (0 — без дополнения). */
data class ParamSpec(
    val key: String,
    val label: String,
    val type: ParamType = ParamType.INT,
    val min: Int = 0,
    val max: Int = 99,
    val width: Int = 0,
    val default: String? = null
)

/** Особая роль команды в приложении. */
enum class CommandKind {
    NORMAL,
    /** 09 — версия ПО и статус; ответ обновляет версию модуля в профиле. */
    STATUS,
    /** USSD-запрос баланса: текст берётся из профиля ({USSD}). */
    USSD_BALANCE,
    /** 00591 — обновление ПО модуля: ведётся сеанс обновления. */
    FIRMWARE_UPDATE,
    /** Выключение антиограбления (1000PW): никогда не блокируется движением. */
    ANTIHIJACK_OFF
}

/**
 * Описание команды из таблицы (JSON, редактируется в приложении).
 *
 * Шаблон [template]: обычный текст SMS с подстановками
 *  {PW}    — «00» + пароль PW, только если в профиле включён режим пароля, иначе пусто;
 *  {PWRAW} — пароль PW всегда (например, 1000PW — выключение антиограбления);
 *  {USSD}  — USSD-запрос баланса из профиля;
 *  {КЛЮЧ}  — параметр из [params].
 */
data class CommandDef(
    val code: String,
    val name: String,
    val group: String,
    /** Опасная: подтверждение удержанием кнопки. */
    val dangerous: Boolean = false,
    /** Критичная: раздел «Критичное», вход по ПИН, недоступна в движении. */
    val critical: Boolean = false,
    /** Минимальная версия ПО модуля, например "M8". */
    val minFw: String? = null,
    /** Требование к ПО центрального блока — показывается подсказкой. */
    val cbRequirement: String? = null,
    /** Если задано — команда неприменима к этой конфигурации (причина), кнопка неактивна. */
    val notApplicable: String? = null,
    /** Проверена на этой машине. */
    val verified: Boolean = false,
    val template: String = code,
    val params: List<ParamSpec> = emptyList(),
    /** Только с номера М1 без пароля (формат с паролем не описан). */
    val m1Only: Boolean = false,
    /** Управляющая: автоматически не переотправляется, повтор — только вручную с подтверждением. */
    val control: Boolean = false,
    /** Можно ли добавлять «#» в конец (для записи номеров и паролей — нельзя). */
    val hashAllowed: Boolean = true,
    val note: String? = null,
    val source: String? = null,
    val kind: CommandKind = CommandKind.NORMAL
) {
    val minFwVersion: FwVersion? get() = FwVersion.parse(minFw)
    val needsPasswordValue: Boolean get() = template.contains("{PWRAW}")
}

data class SendOptions(
    /** Отправка с пароля: с номеров M2–M4 или при включённом 00961. */
    val passwordMode: Boolean,
    val password: String?,
    val appendHash: Boolean,
    val ussd: String = "*100#"
)

sealed class Built {
    data class Ok(val text: String) : Built()
    data class Error(val message: String) : Built()
}

/** Сборка текста SMS по шаблону команды. */
object CommandText {

    private val PLACEHOLDER = Regex("\\{([A-Za-z0-9_]+)\\}")
    private val USSD = Regex("^[*#][0-9*#]*#$")

    fun build(def: CommandDef, params: Map<String, String>, o: SendOptions): Built {
        if (o.passwordMode && def.m1Only) {
            return Built.Error("Команда отправляется только с номера М1 без пароля: формат с паролем для неё не описан.")
        }
        val pw = o.password?.trim().orEmpty()
        val pwValid = pw.length == 4 && pw.all { it in '0'..'9' }
        val sb = StringBuilder()
        var last = 0
        for (m in PLACEHOLDER.findAll(def.template)) {
            sb.append(def.template, last, m.range.first)
            val key = m.groupValues[1]
            val value: String = when (key) {
                "PW" -> if (o.passwordMode) {
                    if (!pwValid) return Built.Error("Укажите пароль PW (4 цифры) в профиле.")
                    "00$pw"
                } else ""
                "PWRAW" -> {
                    if (!pwValid) return Built.Error("Для этой команды нужен пароль PW (4 цифры) в профиле.")
                    pw
                }
                "USSD" -> {
                    val u = o.ussd.trim()
                    if (!USSD.matches(u)) return Built.Error("USSD-запрос должен начинаться с * или # и заканчиваться #.")
                    u
                }
                else -> {
                    val spec = def.params.firstOrNull { it.key == key }
                        ?: return Built.Error("В шаблоне неизвестный параметр {$key}.")
                    formatParam(spec, params[key] ?: spec.default)
                        ?: return Built.Error(paramError(spec))
                }
            }
            sb.append(value)
            last = m.range.last + 1
        }
        sb.append(def.template, last, def.template.length)
        var text = sb.toString()
        if (text.isBlank()) return Built.Error("Пустой текст команды.")
        if (o.appendHash && def.hashAllowed && def.kind != CommandKind.USSD_BALANCE && !text.endsWith("#")) {
            text += "#"
        }
        return Built.Ok(text)
    }

    fun formatParam(spec: ParamSpec, value: String?): String? {
        val v = value?.trim() ?: return null
        return when (spec.type) {
            ParamType.INT -> {
                val n = v.toIntOrNull() ?: return null
                if (n < spec.min || n > spec.max) return null
                if (spec.width > 0) n.toString().padStart(spec.width, '0') else n.toString()
            }
            ParamType.PHONE -> {
                val p = Phone.normalize(v)
                if (!p.startsWith("+") || !Phone.isValid(p)) null else p
            }
        }
    }

    fun paramError(spec: ParamSpec): String = when (spec.type) {
        ParamType.INT -> "«${spec.label}»: число от ${spec.min} до ${spec.max}."
        ParamType.PHONE -> "«${spec.label}»: номер в формате +7XXXXXXXXXX."
    }
}
