package ru.e90pult.data

import android.content.Context
import ru.e90pult.core.CommandDef
import ru.e90pult.core.CommandTableJson

/** Таблица команд: по умолчанию из assets/commands_default.json, у профиля может быть своя. */
class CommandRepo(private val context: Context) {

    val defaultJson: String by lazy {
        context.assets.open("commands_default.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    val defaults: List<CommandDef> by lazy { CommandTableJson.parse(defaultJson) }

    @Volatile
    private var cache: Pair<String, List<CommandDef>>? = null

    fun forProfile(p: ProfileEntity?): List<CommandDef> {
        val json = p?.commandsJson.orEmpty()
        if (json.isBlank()) return defaults
        cache?.let { if (it.first == json) return it.second }
        val parsed = runCatching { CommandTableJson.parse(json) }.getOrNull() ?: return defaults
        cache = json to parsed
        return parsed
    }

    fun find(p: ProfileEntity?, code: String): CommandDef? = forProfile(p).firstOrNull { it.code == code }

    /** Текст таблицы профиля для редактора/экспорта. */
    fun jsonFor(p: ProfileEntity?): String {
        val json = p?.commandsJson.orEmpty()
        return if (json.isBlank()) CommandTableJson.serialize(defaults) else json
    }
}
