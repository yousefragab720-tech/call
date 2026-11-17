package com.callguard.app

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object Lang {
    /** يطبّق لغة التطبيق المختارة (ar / en)، وإلا يتبع لغة النظام. */
    fun wrap(base: Context): Context {
        val code = Prefs.lang(base)
        if (code != "ar" && code != "en") return base
        val locale = Locale(code)
        Locale.setDefault(locale)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(locale)
        cfg.setLayoutDirection(locale)
        return base.createConfigurationContext(cfg)
    }
}
