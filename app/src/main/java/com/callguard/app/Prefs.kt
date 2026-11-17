package com.callguard.app

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CallRecord(
    val count: Int,
    val first: Long,
    val warned: Boolean,
    val blocked: Boolean
)

object Prefs {
    const val BUSINESS_PKG = "com.whatsapp.w4b"
    const val REGULAR_PKG = "com.whatsapp"

    const val DEFAULT_MESSAGE =
        "تنبيه آلي: تم رصد اتصالات متكررة من رقمك دون رد ({count} من {max}). " +
            "في حالة تكرار الاتصال سيتم حظر الرقم تلقائياً. برجاء التواصل عبر الرسائل فقط."

    private const val MAX_LOG_LINES = 60

    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences("guard", Context.MODE_PRIVATE)

    fun enabled(c: Context): Boolean = sp(c).getBoolean("enabled", true)
    fun maxCalls(c: Context): Int = sp(c).getInt("max", 3)
    fun windowHours(c: Context): Int = sp(c).getInt("window", 24)
    fun warnEnabled(c: Context): Boolean = sp(c).getBoolean("warn", true)
    fun warnAt(c: Context): Int = sp(c).getInt("warnAt", 1)
    fun message(c: Context): String = sp(c).getString("msg", DEFAULT_MESSAGE) ?: DEFAULT_MESSAGE
    fun whitelist(c: Context): String = sp(c).getString("wl", "") ?: ""
    fun useBusiness(c: Context): Boolean = sp(c).getBoolean("biz", true)
    fun useRegular(c: Context): Boolean = sp(c).getBoolean("reg", false)

    fun saveSettings(
        c: Context,
        enabled: Boolean,
        max: Int,
        window: Int,
        warn: Boolean,
        warnAt: Int,
        msg: String,
        wl: String,
        biz: Boolean,
        reg: Boolean
    ) {
        sp(c).edit()
            .putBoolean("enabled", enabled)
            .putInt("max", max)
            .putInt("window", window)
            .putBoolean("warn", warn)
            .putInt("warnAt", warnAt)
            .putString("msg", if (msg.isBlank()) DEFAULT_MESSAGE else msg)
            .putString("wl", wl)
            .putBoolean("biz", biz)
            .putBoolean("reg", reg)
            .apply()
    }

    // ---------- عدادات الأرقام ----------

    private fun load(c: Context, number: String): CallRecord? {
        val s = sp(c).getString("rec_$number", null) ?: return null
        val p = s.split("|")
        if (p.size < 4) return null
        return CallRecord(
            p[0].toIntOrNull() ?: 0,
            p[1].toLongOrNull() ?: 0L,
            p[2] == "1",
            p[3] == "1"
        )
    }

    private fun save(c: Context, number: String, r: CallRecord) {
        val w = if (r.warned) 1 else 0
        val b = if (r.blocked) 1 else 0
        sp(c).edit().putString("rec_$number", "${r.count}|${r.first}|$w|$b").apply()
    }

    @Synchronized
    fun addCalls(c: Context, number: String, n: Int, windowMs: Long): CallRecord {
        val now = System.currentTimeMillis()
        val old = load(c, number)
        val base = if (old == null || now - old.first > windowMs) {
            CallRecord(0, now, false, old?.blocked ?: false)
        } else {
            old
        }
        val r = base.copy(count = base.count + n)
        save(c, number, r)
        return r
    }

    @Synchronized
    fun markWarned(c: Context, number: String) {
        val r = load(c, number) ?: return
        save(c, number, r.copy(warned = true))
    }

    @Synchronized
    fun markBlocked(c: Context, number: String) {
        val r = load(c, number) ?: CallRecord(0, System.currentTimeMillis(), false, false)
        save(c, number, r.copy(blocked = true))
    }

    @Synchronized
    fun resetAll(c: Context) {
        val e = sp(c).edit()
        for (k in sp(c).all.keys) {
            if (k.startsWith("rec_")) e.remove(k)
        }
        e.apply()
    }

    // ---------- السجل ----------

    @Synchronized
    fun log(c: Context, line: String) {
        val t = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
        val old = sp(c).getString("log", "") ?: ""
        val lines = ArrayList<String>()
        lines.add(t + "  " + line.replace("\n", " "))
        if (old.isNotEmpty()) lines.addAll(old.split("\n"))
        sp(c).edit().putString("log", lines.take(MAX_LOG_LINES).joinToString("\n")).apply()
    }

    fun logText(c: Context): String = sp(c).getString("log", "") ?: ""

    fun clearLog(c: Context) {
        sp(c).edit().remove("log").apply()
    }
}
