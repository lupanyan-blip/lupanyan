package ru.e90pult.core

/** Нормализация и сравнение телефонных номеров (+7 / 8 / без кода). */
object Phone {

    /**
     * 8XXXXXXXXXX, 7XXXXXXXXXX, +7XXXXXXXXXX, 9XXXXXXXXX → +7XXXXXXXXXX.
     * Другие номера: «+» и цифры, если «+» был, иначе только цифры.
     * Буквенный отправитель (MegaFon и т. п.) возвращается как есть, без пробелов по краям.
     */
    fun normalize(raw: String?): String {
        if (raw == null) return ""
        val trimmed = raw.trim()
        val digits = trimmed.filter { it in '0'..'9' }
        if (digits.isEmpty()) return trimmed
        val hasPlus = trimmed.startsWith("+")
        return when {
            digits.length == 11 && digits[0] == '7' -> "+$digits"
            digits.length == 11 && digits[0] == '8' && !hasPlus -> "+7" + digits.substring(1)
            digits.length == 10 && digits[0] == '9' && !hasPlus -> "+7$digits"
            hasPlus -> "+$digits"
            else -> digits
        }
    }

    /** Совпадают ли номера после нормализации. */
    fun same(a: String?, b: String?): Boolean {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return false
        if (na == nb) return true
        val da = na.filter { it in '0'..'9' }
        val db = nb.filter { it in '0'..'9' }
        return da.isNotEmpty() && da == db
    }

    /** Похоже ли на номер, пригодный для отправки SMS. */
    fun isValid(raw: String?): Boolean {
        val n = normalize(raw)
        val digits = n.filter { it in '0'..'9' }
        return digits.length in 10..15 && n.all { it == '+' || it in '0'..'9' }
    }

    /** +7 912 345-67-89 для показа; прочие номера без изменений. */
    fun pretty(raw: String?): String {
        val n = normalize(raw)
        if (n.length == 12 && n.startsWith("+7")) {
            val d = n.substring(2)
            return "+7 ${d.substring(0, 3)} ${d.substring(3, 6)}-${d.substring(6, 8)}-${d.substring(8, 10)}"
        }
        return n
    }
}
