package ru.e90pult.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Таблица команд в JSON: чтение и запись. */
object CommandTableJson {

    const val FORMAT_VERSION = 1

    private val pretty = Json { prettyPrint = true }

    /** Разбор JSON: объект {"version":1,"commands":[…]} или просто массив команд. */
    fun parse(text: String): List<CommandDef> {
        val root = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("Не JSON: ${e.message}")
        }
        val arr: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> root["commands"] as? JsonArray
                ?: throw IllegalArgumentException("Нет массива \"commands\"")
            else -> throw IllegalArgumentException("Ожидался объект или массив")
        }
        val out = ArrayList<CommandDef>(arr.size)
        arr.forEachIndexed { i, el ->
            val o = el as? JsonObject ?: throw IllegalArgumentException("Команда №${i + 1}: не объект")
            out += parseCommand(o, i + 1)
        }
        val dup = out.groupBy { it.code }.filter { it.value.size > 1 }.keys
        if (dup.isNotEmpty()) throw IllegalArgumentException("Повторяются коды: ${dup.joinToString()}")
        return out
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.bool(k: String, def: Boolean): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull ?: def
    private fun JsonObject.int(k: String, def: Int): Int = (this[k] as? JsonPrimitive)?.intOrNull ?: def

    private fun parseCommand(o: JsonObject, n: Int): CommandDef {
        val code = o.str("code") ?: throw IllegalArgumentException("Команда №$n: нет поля \"code\"")
        val name = o.str("name") ?: throw IllegalArgumentException("Команда $code: нет поля \"name\"")
        val params = (o["params"] as? JsonArray)?.mapIndexed { i, p ->
            val po = p as? JsonObject ?: throw IllegalArgumentException("Команда $code: параметр №${i + 1} не объект")
            val key = po.str("key") ?: throw IllegalArgumentException("Команда $code: у параметра нет \"key\"")
            val type = when (po.str("type")?.uppercase()) {
                null, "INT" -> ParamType.INT
                "PHONE" -> ParamType.PHONE
                else -> throw IllegalArgumentException("Команда $code: неизвестный тип параметра ${po.str("type")}")
            }
            ParamSpec(
                key = key,
                label = po.str("label") ?: key,
                type = type,
                min = po.int("min", 0),
                max = po.int("max", 99),
                width = po.int("width", 0),
                default = po.str("default")
            )
        } ?: emptyList()
        val kind = o.str("kind")?.let { k ->
            CommandKind.entries.firstOrNull { it.name == k.uppercase() }
                ?: throw IllegalArgumentException("Команда $code: неизвестный kind $k")
        } ?: CommandKind.NORMAL
        val minFw = o.str("minFw")
        if (minFw != null && FwVersion.parse(minFw) == null) {
            throw IllegalArgumentException("Команда $code: minFw \"$minFw\" — ожидается буква и цифра, например M8")
        }
        return CommandDef(
            code = code,
            name = name,
            group = o.str("group") ?: "Прочее",
            dangerous = o.bool("dangerous", false),
            critical = o.bool("critical", false),
            minFw = minFw,
            cbRequirement = o.str("cbRequirement"),
            notApplicable = o.str("notApplicable"),
            verified = o.bool("verified", false),
            template = o.str("template") ?: code,
            params = params,
            m1Only = o.bool("m1Only", false),
            control = o.bool("control", false),
            hashAllowed = o.bool("hashAllowed", true),
            note = o.str("note"),
            source = o.str("source"),
            kind = kind
        )
    }

    fun serialize(defs: List<CommandDef>): String {
        val arr = JsonArray(defs.map { toJson(it) })
        val root = JsonObject(linkedMapOf("version" to JsonPrimitive(FORMAT_VERSION), "commands" to arr))
        return pretty.encodeToString(JsonElement.serializer(), root)
    }

    private fun s(v: String?): JsonElement = if (v == null) JsonNull else JsonPrimitive(v)

    private fun toJson(d: CommandDef): JsonObject {
        val m = linkedMapOf<String, JsonElement>(
            "code" to JsonPrimitive(d.code),
            "name" to JsonPrimitive(d.name),
            "group" to JsonPrimitive(d.group),
            "dangerous" to JsonPrimitive(d.dangerous),
            "critical" to JsonPrimitive(d.critical),
            "minFw" to s(d.minFw),
            "cbRequirement" to s(d.cbRequirement),
            "notApplicable" to s(d.notApplicable),
            "verified" to JsonPrimitive(d.verified),
            "template" to JsonPrimitive(d.template),
            "params" to JsonArray(d.params.map { p ->
                JsonObject(linkedMapOf(
                    "key" to JsonPrimitive(p.key),
                    "label" to JsonPrimitive(p.label),
                    "type" to JsonPrimitive(p.type.name),
                    "min" to JsonPrimitive(p.min),
                    "max" to JsonPrimitive(p.max),
                    "width" to JsonPrimitive(p.width),
                    "default" to s(p.default)
                ))
            }),
            "m1Only" to JsonPrimitive(d.m1Only),
            "control" to JsonPrimitive(d.control),
            "hashAllowed" to JsonPrimitive(d.hashAllowed),
            "note" to s(d.note),
            "source" to s(d.source),
            "kind" to JsonPrimitive(d.kind.name)
        )
        return JsonObject(m)
    }

    /** Проверка таблицы: каждая команда собирается с параметрами по умолчанию или минимальными значениями. */
    fun validate(defs: List<CommandDef>): List<String> {
        val problems = mutableListOf<String>()
        for (d in defs) {
            val params = d.params.associate { p ->
                p.key to (p.default ?: if (p.type == ParamType.PHONE) "+79000000000" else p.min.toString())
            }
            val pwMode = !d.m1Only
            val r = CommandText.build(d, params, SendOptions(passwordMode = false, password = "1234", appendHash = false))
            if (r is Built.Error) problems += "${d.code}: ${r.message}"
            if (pwMode) {
                val r2 = CommandText.build(d, params, SendOptions(passwordMode = true, password = "1234", appendHash = false))
                if (r2 is Built.Error) problems += "${d.code} (с паролем): ${r2.message}"
            }
        }
        return problems
    }
}
