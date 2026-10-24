package ru.e90pult.core

/**
 * Версия ПО GSM-модуля StarLine: буква + число (I7, M8, P5, Q2, R9).
 * Сравнение: сначала буква, затем число — I7 < M8 < P5 < Q2.
 */
data class FwVersion(val letter: Char, val number: Int) : Comparable<FwVersion> {

    override fun compareTo(other: FwVersion): Int {
        val byLetter = letter.compareTo(other.letter)
        return if (byLetter != 0) byLetter else number.compareTo(other.number)
    }

    override fun toString(): String = "$letter$number"

    companion object {
        // Необязательный аппаратный код ("HD03-", "FZ12 - "), затем буква и 1–2 цифры.
        private val PATTERN = Regex(
            "^\\s*(?:([A-Za-z]{1,3}\\d{1,3})\\s*[-–—]\\s*)?([A-Za-z])(\\d{1,2})(?![0-9])"
        )

        /** "I7", "i7", "HD03-R5", "FZ12 - I3" → версия; что-то другое → null. */
        fun parse(text: String?): FwVersion? {
            if (text.isNullOrBlank()) return null
            val m = PATTERN.find(text) ?: return null
            val letter = m.groupValues[2].uppercase()[0]
            val number = m.groupValues[3].toIntOrNull() ?: return null
            return FwVersion(letter, number)
        }

        /** Аппаратный код модуля из "HD03-R5" → "HD03"; для "I7" → null. */
        fun hardwareCode(text: String?): String? {
            if (text.isNullOrBlank()) return null
            val m = PATTERN.find(text) ?: return null
            return m.groupValues[1].ifEmpty { null }?.uppercase()
        }

        /** a < b с учётом неизвестной версии: неизвестная считается «меньше любой». */
        fun isAtLeast(actual: FwVersion?, required: FwVersion?): Boolean {
            if (required == null) return true
            if (actual == null) return false
            return actual >= required
        }
    }
}
