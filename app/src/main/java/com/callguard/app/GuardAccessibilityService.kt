package com.callguard.app

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * ينفذ المهام: فتح محادثة الرقم في واتساب ثم
 *  - WARN : الضغط على زر الإرسال (النص يأتي معبأ من الرابط)
 *  - BLOCK: القائمة ⋮ ← المزيد ← حظر ← تأكيد الحظر
 */
class GuardAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: GuardAccessibilityService? = null

        private const val JOB_TIMEOUT_MS = 45_000L
        private const val STEP_TIMEOUT_MS = 15_000L
        private const val TICK_MS = 900L
    }

    private enum class Step { OPEN, SEND, MENU, MORE, BLOCK, CONFIRM, DONE }

    private val handler = Handler(Looper.getMainLooper())
    private var job: GuardJob? = null
    private var step = Step.OPEN
    private var jobStart = 0L
    private var stepStart = 0L
    private var running = false

    private val kickRunnable = Runnable { kickInternal() }
    private val tickRunnable = Runnable { tick() }

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            kick()
        }
    }

    // ---------------- دورة حياة الخدمة ----------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(unlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(unlockReceiver, filter)
        }
        Prefs.log(this, "خدمة الأتمتة جاهزة")
        kick()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(unlockReceiver)
        } catch (e: Exception) {
            // already unregistered
        }
        running = false
        job = null
        JobQueue.finish()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    // ---------------- تشغيل المهام ----------------

    fun kick() {
        handler.removeCallbacks(kickRunnable)
        handler.post(kickRunnable)
    }

    @Volatile
    private var manualRun = false

    /** تنفيذ المهام المعلّقة الآن حتى لو كان التشغيل التلقائي مقفولاً. */
    fun runNow() {
        manualRun = true
        kick()
    }

    private fun kickInternal() {
        if (running) return
        if (!JobQueue.hasPending()) {
            manualRun = false
            return
        }
        if (!Prefs.autoRun(this) && !manualRun) return
        if (!canAct()) {
            // الشاشة مقفولة: نحاول تاني، وبعد فتح القفل يشتغل فوراً
            handler.postDelayed(kickRunnable, 15_000L)
            return
        }
        val j = JobQueue.take() ?: return
        startJob(j)
    }

    private fun canAct(): Boolean {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isInteractive && !km.isKeyguardLocked
    }

    private fun startJob(j: GuardJob) {
        running = true
        job = j
        step = Step.OPEN
        val what = if (j.type == JobType.WARN) "إرسال تحذير إلى" else "حظر"
        Prefs.log(this, "بدء $what +${j.number}")
        jobStart = SystemClock.elapsedRealtime()
        stepStart = jobStart
        schedule(2500L)
    }

    private fun advance(s: Step) {
        step = s
        stepStart = SystemClock.elapsedRealtime()
    }

    private fun schedule(delay: Long) {
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, delay)
    }

    private fun tick() {
        val j = job ?: return
        val now = SystemClock.elapsedRealtime()
        if (step != Step.DONE) {
            val tooLong = now - jobStart > JOB_TIMEOUT_MS
            val stuck = step != Step.OPEN && now - stepStart > STEP_TIMEOUT_MS
            if (tooLong || stuck) {
                finish(
                    false,
                    "فشل عند الخطوة $step — إذا لم يفتح واتساب فعّل (العرض فوق التطبيقات)، " +
                        "وإذا فتح فربما واجهة واتساب مختلفة"
                )
                return
            }
        }
        try {
            when (step) {
                Step.OPEN -> {
                    if (!openChat(j)) return
                    advance(if (j.type == JobType.WARN) Step.SEND else Step.MENU)
                    schedule(3000L)
                    return
                }
                Step.SEND -> doSend(j)
                Step.MENU -> doMenu(j)
                Step.MORE -> doMore(j)
                Step.BLOCK -> doBlock(j)
                Step.CONFIRM -> doConfirm(j)
                Step.DONE -> {}
            }
        } catch (e: Exception) {
            finish(false, "خطأ: " + e.javaClass.simpleName + " " + e.message)
            return
        }
        if (job != null) schedule(TICK_MS)
    }

    // ---------------- الخطوات ----------------

    private fun openChat(j: GuardJob): Boolean {
        val textPart = if (j.type == JobType.WARN) "?text=" + Uri.encode(j.text) else ""
        val first = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + j.number + textPart))
        first.setPackage(j.pkg)
        first.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(first)
            return true
        } catch (e: ActivityNotFoundException) {
            // نجرب الرابط البديل
        } catch (e: Exception) {
            finish(false, "تعذر فتح واتساب: " + e.message)
            return false
        }
        val q = if (j.type == JobType.WARN) "&text=" + Uri.encode(j.text) else ""
        val second = Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send?phone=" + j.number + q))
        second.setPackage(j.pkg)
        second.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(second)
            true
        } catch (e: Exception) {
            finish(false, "تعذر فتح واتساب: " + e.message)
            false
        }
    }

    private fun doSend(j: GuardJob) {
        val rs = roots(j.pkg)
        if (rs.isEmpty()) return
        var btns: List<AccessibilityNodeInfo> = ArrayList()
        for (r in rs) {
            val byId = r.findAccessibilityNodeInfosByViewId(j.pkg + ":id/send")
            if (byId != null && byId.isNotEmpty()) {
                btns = byId
                break
            }
        }
        if (btns.isEmpty()) {
            btns = findAll(rs) { n ->
                val d = desc(n)
                d == "send" || d == "إرسال" || d == "ارسال"
            }
        }
        if (btns.isNotEmpty() && clickAny(btns)) {
            finishLater(true, "تم إرسال التحذير إلى +${j.number}")
        }
    }

    private fun doMenu(j: GuardJob) {
        val rs = roots(j.pkg)
        if (rs.isEmpty()) return
        val btns = findAll(rs) { n ->
            val d = desc(n)
            d == "more options" || d == "المزيد من الخيارات" ||
                d == "خيارات أخرى" || d == "مزيد من الخيارات"
        }
        if (btns.isNotEmpty() && clickAny(btns)) advance(Step.MORE)
    }

    private fun doMore(j: GuardJob) {
        val rs = roots(j.pkg)
        if (rs.isEmpty()) return
        val mores = findAll(rs) { n ->
            val t = txt(n)
            t == "more" || t == "المزيد" || t == "مزيد"
        }
        if (mores.isNotEmpty() && clickAny(mores)) {
            advance(Step.BLOCK)
            return
        }
        // أحياناً "حظر" يظهر مباشرة
        val blocks = findAll(rs) { n -> isBlockText(txt(n)) }
        if (blocks.isNotEmpty() && clickAny(blocks)) advance(Step.CONFIRM)
    }

    private fun doBlock(j: GuardJob) {
        val rs = roots(j.pkg)
        if (rs.isEmpty()) return
        val blocks = findAll(rs) { n -> isBlockText(txt(n)) }
        if (blocks.isNotEmpty() && clickAny(blocks)) advance(Step.CONFIRM)
    }

    private fun doConfirm(j: GuardJob) {
        val rs = roots(j.pkg)
        if (rs.isEmpty()) return
        val btns = findAll(rs) { n -> isBlockText(txt(n)) && n.isClickable }
        if (btns.isNotEmpty() && clickAny(btns)) {
            finishLater(true, "تم حظر +${j.number}")
        }
    }

    // ---------------- إنهاء المهمة ----------------

    private fun finishLater(ok: Boolean, msg: String) {
        advance(Step.DONE)
        handler.postDelayed({ finish(ok, msg) }, 1500L)
    }

    private fun finish(ok: Boolean, msg: String) {
        val j = job ?: return
        handler.removeCallbacks(tickRunnable)
        Prefs.log(this, (if (ok) "✔ " else "✖ ") + msg)
        if (ok) {
            if (j.type == JobType.BLOCK) Prefs.markBlocked(this, j.number)
            else Prefs.markWarned(this, j.number)
        }
        job = null
        JobQueue.finish()
        val pkg = j.pkg
        handler.postDelayed({ leave(pkg) }, 800L)
        handler.postDelayed({
            running = false
            kick()
        }, 3500L)
    }

    private fun leave(pkg: String) {
        if (inApp(pkg)) performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({
            if (inApp(pkg)) performGlobalAction(GLOBAL_ACTION_BACK)
        }, 700L)
        handler.postDelayed({
            if (inApp(pkg)) performGlobalAction(GLOBAL_ACTION_HOME)
        }, 1500L)
    }

    private fun inApp(pkg: String): Boolean {
        val r = rootInActiveWindow
        return r != null && r.packageName?.toString() == pkg
    }

    // ---------------- أدوات الواجهة ----------------

    private fun roots(pkg: String): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windows) {
                val r = w.root
                if (r != null && r.packageName?.toString() == pkg) out.add(r)
            }
        } catch (e: Exception) {
            // ignore
        }
        if (out.isEmpty()) {
            val r = rootInActiveWindow
            if (r != null && r.packageName?.toString() == pkg) out.add(r)
        }
        return out
    }

    private fun findAll(
        roots: List<AccessibilityNodeInfo>,
        pred: (AccessibilityNodeInfo) -> Boolean
    ): List<AccessibilityNodeInfo> {
        val res = ArrayList<AccessibilityNodeInfo>()
        for (root in roots) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.addLast(root)
            var guard = 0
            while (queue.isNotEmpty() && guard < 4000) {
                val n = queue.removeFirst()
                guard++
                if (pred(n)) res.add(n)
                for (i in 0 until n.childCount) {
                    val c = n.getChild(i)
                    if (c != null) queue.addLast(c)
                }
            }
        }
        return res
    }

    private fun txt(n: AccessibilityNodeInfo): String =
        n.text?.toString()?.trim()?.lowercase() ?: ""

    private fun desc(n: AccessibilityNodeInfo): String =
        n.contentDescription?.toString()?.trim()?.lowercase() ?: ""

    private fun isBlockText(t: String): Boolean {
        if (t.isEmpty() || t.length > 30) return false
        if (t.contains("?") || t.contains("؟")) return false
        if (t.any { Character.isDigit(it) }) return false
        if (t.contains("report") || t.contains("إبلاغ") || t.contains("ابلاغ")) return false
        if (t.contains("unblock") || t.contains("إلغاء") || t.contains("الغاء")) return false
        return t == "block" || t == "حظر" || t.startsWith("block ") || t.startsWith("حظر ")
    }

    private fun clickAny(nodes: List<AccessibilityNodeInfo>): Boolean {
        for (n in nodes) {
            if (clickNode(n)) return true
        }
        return false
    }

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable && cur.isEnabled) {
                return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            cur = cur.parent
            depth++
        }
        return false
    }
}
