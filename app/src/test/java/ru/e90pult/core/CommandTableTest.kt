package ru.e90pult.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CommandTableTest {

    private fun defaultJson(): String {
        val candidates = listOf("src/main/assets/commands_default.json", "app/src/main/assets/commands_default.json")
        val f = candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: error("commands_default.json не найден")
        return f.readText(Charsets.UTF_8)
    }

    @Test fun defaultTableIsValid() {
        val defs = CommandTableJson.parse(defaultJson())
        assertTrue(defs.size > 40)
        assertEquals(emptyList<String>(), CommandTableJson.validate(defs))
        val byCode = defs.associateBy { it.code }
        // Проверенные на машине — только 09 и 00551.
        assertEquals(setOf("09", "00551"), defs.filter { it.verified }.map { it.code }.toSet())
        // Плитки главного экрана существуют.
        listOf("11", "10", "21", "20", "41", "09", "00551", "*100#").forEach { assertTrue(it, byCode.containsKey(it)) }
        assertTrue(byCode.getValue("31").dangerous && byCode.getValue("31").critical)
        assertEquals(CommandKind.ANTIHIJACK_OFF, byCode.getValue("1000PW").kind)
        assertEquals("M8", byCode.getValue("0069X").minFw)
        assertEquals("P5", byCode.getValue("21XX#").minFw)
        assertTrue(byCode.getValue("21XX#").notApplicable != null)
        assertTrue(byCode.getValue("37").notApplicable != null)
        // Никакой 0018 (программирование таблиц ЦБ) в таблице нет.
        assertTrue(defs.none { it.code.startsWith("0018") })
    }

    @Test fun roundTrip() {
        val defs = CommandTableJson.parse(defaultJson())
        val again = CommandTableJson.parse(CommandTableJson.serialize(defs))
        assertEquals(defs, again)
    }

    @Test fun builtTexts() {
        val byCode = CommandTableJson.parse(defaultJson()).associateBy { it.code }
        val o = SendOptions(passwordMode = false, password = "1234", appendHash = false)
        fun b(code: String, p: Map<String, String> = emptyMap(), opt: SendOptions = o) = CommandText.build(byCode.getValue(code), p, opt)
        assertEquals(Built.Ok("09"), b("09"))
        assertEquals(Built.Ok("00551"), b("00551"))
        assertEquals(Built.Ok("*100#"), b("*100#"))
        assertEquals(Built.Ok("10001234"), b("1000PW"))
        assertEquals(Built.Ok("00695"), b("0069X", mapOf("X" to "5")))
        assertEquals(Built.Ok("0103"), b("01NN", mapOf("NN" to "3")))
        assertEquals(Built.Ok("82"), b("8N", mapOf("N" to "2")))
        assertEquals(Built.Ok("00031100+79011231111"), b("000NXY00", mapOf("N" to "3", "X" to "1", "Y" to "1", "PHONE" to "+79011231111")))
        assertEquals(Built.Ok("00021100#"), b("000N1100", mapOf("N" to "2"), o.copy(appendHash = true)))
        assertEquals(Built.Ok("99001234004321001100+79111234455"),
            b("9900", mapOf("PS" to "4321", "X" to "1", "Y" to "1", "PHONE" to "+79111234455"), o.copy(appendHash = true)))
        assertEquals(Built.Ok("00961234000"), b("0096PW000"))
        assertEquals(Built.Ok("11001234#"), b("11", opt = o.copy(passwordMode = true, appendHash = true)))
    }

    @Test fun badJson() {
        val errors = listOf(
            "не json",
            "{\"commands\": 5}",
            "[{\"name\": \"без кода\"}]",
            "[{\"code\": \"1\", \"name\": \"a\"}, {\"code\": \"1\", \"name\": \"b\"}]",
            "[{\"code\": \"1\", \"name\": \"a\", \"minFw\": \"8M\"}]",
            "[{\"code\": \"1\", \"name\": \"a\", \"kind\": \"XYZ\"}]"
        )
        for (e in errors) {
            try {
                CommandTableJson.parse(e)
                throw AssertionError("Должна быть ошибка: $e")
            } catch (ex: IllegalArgumentException) {
                assertTrue(ex.message!!.isNotBlank())
            }
        }
    }
}
