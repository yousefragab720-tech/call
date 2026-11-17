package com.callguard.app

object Util {

    /** كل الأرقام (بما فيها الهندية) تتحول لأرقام لاتينية، وباقي الرموز تُحذف. */
    fun digits(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            if (Character.isDigit(ch)) {
                val d = Character.digit(ch, 10)
                if (d >= 0) sb.append(d)
            }
        }
        return sb.toString()
    }

    /** هل النص يبدو كرقم هاتف (يعني الرقم غير محفوظ كجهة اتصال)؟ */
    fun looksLikePhone(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return false
        var count = 0
        for (ch in t) {
            if (Character.isDigit(ch)) {
                count++
            } else if (Character.isSpaceChar(ch)) {
                continue
            } else if (ch == '+' || ch == '-' || ch == '(' || ch == ')') {
                continue
            } else if (Character.getType(ch) == Character.FORMAT.toInt()) {
                continue
            } else {
                return false
            }
        }
        return count in 7..15
    }

    /** الرقم في بداية النص مثل "2 missed calls" أو null. */
    fun leadingNumber(s: String): Int? {
        val t = s.trim()
        if (t.isEmpty() || !Character.isDigit(t[0])) return null
        val sb = StringBuilder()
        for (ch in t) {
            if (Character.isDigit(ch)) {
                sb.append(Character.digit(ch, 10))
            } else {
                break
            }
        }
        return sb.toString().toIntOrNull()
    }
}
