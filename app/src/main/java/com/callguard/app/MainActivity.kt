package com.callguard.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var swEnabled: Switch
    private lateinit var etMax: EditText
    private lateinit var etWindow: EditText
    private lateinit var cbWarn: CheckBox
    private lateinit var etWarnAt: EditText
    private lateinit var etMessage: EditText
    private lateinit var etWhitelist: EditText
    private lateinit var cbBusiness: CheckBox
    private lateinit var cbRegular: CheckBox
    private lateinit var etTest: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        swEnabled = findViewById(R.id.swEnabled)
        etMax = findViewById(R.id.etMax)
        etWindow = findViewById(R.id.etWindow)
        cbWarn = findViewById(R.id.cbWarn)
        etWarnAt = findViewById(R.id.etWarnAt)
        etMessage = findViewById(R.id.etMessage)
        etWhitelist = findViewById(R.id.etWhitelist)
        cbBusiness = findViewById(R.id.cbBusiness)
        cbRegular = findViewById(R.id.cbRegular)
        etTest = findViewById(R.id.etTest)

        findViewById<Button>(R.id.btnNotif).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btnAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        findViewById<Button>(R.id.btnAppInfo).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            save()
            toast("تم الحفظ")
        }
        findViewById<Button>(R.id.btnReset).setOnClickListener {
            Prefs.resetAll(this)
            toast("تم تصفير العدادات")
            refresh()
        }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            Prefs.clearLog(this)
            refresh()
        }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.btnTestWarn).setOnClickListener { runTest(JobType.WARN) }
        findViewById<Button>(R.id.btnTestBlock).setOnClickListener { runTest(JobType.BLOCK) }

        load()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    private fun load() {
        swEnabled.isChecked = Prefs.enabled(this)
        etMax.setText(Prefs.maxCalls(this).toString())
        etWindow.setText(Prefs.windowHours(this).toString())
        cbWarn.isChecked = Prefs.warnEnabled(this)
        etWarnAt.setText(Prefs.warnAt(this).toString())
        etMessage.setText(Prefs.message(this))
        etWhitelist.setText(Prefs.whitelist(this))
        cbBusiness.isChecked = Prefs.useBusiness(this)
        cbRegular.isChecked = Prefs.useRegular(this)
    }

    private fun save() {
        val max = (etMax.text.toString().toIntOrNull() ?: 3).coerceIn(1, 50)
        val win = (etWindow.text.toString().toIntOrNull() ?: 24).coerceIn(1, 720)
        val warnAt = (etWarnAt.text.toString().toIntOrNull() ?: 1).coerceIn(1, 50)
        Prefs.saveSettings(
            this,
            swEnabled.isChecked,
            max,
            win,
            cbWarn.isChecked,
            warnAt,
            etMessage.text.toString(),
            etWhitelist.text.toString(),
            cbBusiness.isChecked,
            cbRegular.isChecked
        )
    }

    private fun runTest(type: JobType) {
        save()
        val num = Util.digits(etTest.text.toString())
        if (num.length < 7) {
            toast("اكتب رقماً بصيغة دولية، مثال: 201001234567")
            return
        }
        val pkg = if (Prefs.useRegular(this) && !Prefs.useBusiness(this)) {
            Prefs.REGULAR_PKG
        } else {
            Prefs.BUSINESS_PKG
        }
        val text = if (type == JobType.WARN) {
            Prefs.message(this)
                .replace("{count}", "1")
                .replace("{max}", Prefs.maxCalls(this).toString())
        } else {
            ""
        }
        JobQueue.add(GuardJob(type, num, pkg, text))
        val svc = GuardAccessibilityService.instance
        if (svc == null) {
            toast("فعّل خدمة الأتمتة (إمكانية الوصول) أولاً")
        } else {
            svc.kick()
            toast("بدأت التجربة — لا تلمس الشاشة")
        }
    }

    private fun mark(b: Boolean): String = if (b) "✅" else "❌"

    private fun refresh() {
        val notifOn = notifEnabled()
        val accOn = accEnabled()
        val overlayOn = Settings.canDrawOverlays(this)
        tvStatus.text = "قراءة الإشعارات: " + mark(notifOn) + "\n" +
            "خدمة الأتمتة (إمكانية الوصول): " + mark(accOn) + "\n" +
            "العرض فوق التطبيقات: " + mark(overlayOn)
        val log = Prefs.logText(this)
        tvLog.text = if (log.isEmpty()) "لا توجد أحداث بعد" else log
    }

    private fun notifEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        return s.contains(packageName)
    }

    private fun accEnabled(): Boolean {
        val s = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val full = ComponentName(this, GuardAccessibilityService::class.java).flattenToString()
        val short = ComponentName(this, GuardAccessibilityService::class.java).flattenToShortString()
        return s.contains(full) || s.contains(short)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
