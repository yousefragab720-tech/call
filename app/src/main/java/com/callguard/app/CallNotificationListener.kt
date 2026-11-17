package com.callguard.app

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * يراقب إشعارات "مكالمة فائتة" من واتساب، يعدّ المكالمات لكل رقم،
 * ويجدول إما تحذير أو حظر عند الوصول للحد.
 */
class CallNotificationListener : NotificationListenerService() {

    private val seen = LinkedHashSet<String>()
    private val lastParsed = HashMap<String, Int>()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null) lastParsed.remove(sbn.key)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            handle(sbn)
        } catch (e: Exception) {
            Prefs.log(applicationContext, "خطأ: " + e.javaClass.simpleName + " " + e.message)
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        val ctx = applicationContext
        val pkg = sbn.packageName
        val allowed = (pkg == Prefs.BUSINESS_PKG && Prefs.useBusiness(ctx)) ||
            (pkg == Prefs.REGULAR_PKG && Prefs.useRegular(ctx))
        if (!allowed || !Prefs.enabled(ctx)) return

        val n = sbn.notification ?: return
        if (n.category == Notification.CATEGORY_MESSAGE) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (!isMissedCall(text)) return

        // منع العد المزدوج لنفس الإشعار
        val id = sbn.key + "|" + n.`when` + "|" + text
        if (!seen.add(id)) return
        if (seen.size > 300) {
            val iter = seen.iterator()
            if (iter.hasNext()) {
                iter.next()
                iter.remove()
            }
        }

        if (!Util.looksLikePhone(title)) {
            Prefs.log(ctx, "تجاهل: الرقم محفوظ كجهة اتصال ($title)")
            return
        }
        val number = Util.digits(title)

        if (isWhitelisted(ctx, number)) {
            Prefs.log(ctx, "تجاهل: +$number ضمن الأرقام المستثناة")
            return
        }

        // واتساب قد يدمج المكالمات في إشعار واحد مثل "2 missed calls"
        val parsed = Util.leadingNumber(text)
        val prev = lastParsed[sbn.key]
        val delta = if (parsed != null) (parsed - (prev ?: 0)).coerceIn(1, 10) else 1
        lastParsed[sbn.key] = parsed ?: ((prev ?: 0) + 1)

        val max = Prefs.maxCalls(ctx).coerceAtLeast(1)
        val windowMs = Prefs.windowHours(ctx).coerceAtLeast(1) * 3_600_000L
        val rec = Prefs.addCalls(ctx, number, delta, windowMs)
        Prefs.log(ctx, "مكالمة فائتة من +$number — العدد ${rec.count} من $max")
        if (rec.blocked) return

        if (rec.count >= max) {
            if (JobQueue.add(GuardJob(JobType.BLOCK, number, pkg))) {
                Prefs.log(ctx, "تمت جدولة حظر +$number")
            }
        } else if (Prefs.warnEnabled(ctx) && !rec.warned &&
            rec.count >= Prefs.warnAt(ctx).coerceAtLeast(1)
        ) {
            val msg = Prefs.message(ctx)
                .replace("{count}", rec.count.toString())
                .replace("{max}", max.toString())
            if (JobQueue.add(GuardJob(JobType.WARN, number, pkg, msg))) {
                Prefs.log(ctx, "تمت جدولة تحذير إلى +$number")
            }
        }

        val svc = GuardAccessibilityService.instance
        if (svc != null) {
            svc.kick()
        } else {
            Prefs.log(ctx, "تنبيه: خدمة الأتمتة (إمكانية الوصول) غير مفعلة")
        }
    }

    private fun isMissedCall(text: String): Boolean {
        val t = text.lowercase()
        return (t.contains("missed") && t.contains("call")) ||
            (t.contains("فائت") && t.contains("مكالم"))
    }

    private fun isWhitelisted(ctx: Context, number: String): Boolean {
        for (line in Prefs.whitelist(ctx).split("\n", ",", " ")) {
            val d = Util.digits(line)
            if (d.length >= 6 && (number.endsWith(d) || d.endsWith(number))) return true
        }
        return false
    }
}
