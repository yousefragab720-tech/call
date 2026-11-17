package com.callguard.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvBlockedTitle: TextView
    private lateinit var tvBlocked: TextView
    private lateinit var swEnabled: Switch
    private lateinit var etMax: EditText
    private lateinit var etWindow: EditText
    private lateinit var cbWarn: CheckBox
    private lateinit var etWarnAt: EditText
    private lateinit var etMessage: EditText
    private lateinit var etWhitelist: EditText
    private lateinit var cbBusiness: CheckBox
    private lateinit var cbRegular: CheckBox
    private lateinit var cbAuto: CheckBox
    private lateinit var btnRunNow: Button
    private lateinit var etTest: EditText
    private lateinit var rgLang: RadioGroup
    private lateinit var rgTheme: RadioGroup
    private lateinit var rbLangSystem: RadioButton
    private lateinit var rbLangAr: RadioButton
    private lateinit var rbLangEn: RadioButton
    private lateinit var rbThemeSystem: RadioButton
    private lateinit var rbThemeLight: RadioButton
    private lateinit var rbThemeDark: RadioButton

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Lang.wrap(newBase))
    }

    private fun isNight(): Boolean {
        return when (Prefs.theme(this)) {
            "dark" -> true
            "light" -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (isNight()) R.style.AppThemeDark else R.style.AppThemeLight)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        title = getString(R.string.app_title)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        tvBlockedTitle = findViewById(R.id.tvBlockedTitle)
        tvBlocked = findViewById(R.id.tvBlocked)
        swEnabled = findViewById(R.id.swEnabled)
        etMax = findViewById(R.id.etMax)
        etWindow = findViewById(R.id.etWindow)
        cbWarn = findViewById(R.id.cbWarn)
        etWarnAt = findViewById(R.id.etWarnAt)
        etMessage = findViewById(R.id.etMessage)
        etWhitelist = findViewById(R.id.etWhitelist)
        cbBusiness = findViewById(R.id.cbBusiness)
        cbRegular = findViewById(R.id.cbRegular)
        cbAuto = findViewById(R.id.cbAuto)
        btnRunNow = findViewById(R.id.btnRunNow)
        etTest = findViewById(R.id.etTest)
        rgLang = findViewById(R.id.rgLang)
        rgTheme = findViewById(R.id.rgTheme)
        rbLangSystem = findViewById(R.id.rbLangSystem)
        rbLangAr = findViewById(R.id.rbLangAr)
        rbLangEn = findViewById(R.id.rbLangEn)
        rbThemeSystem = findViewById(R.id.rbThemeSystem)
        rbThemeLight = findViewById(R.id.rbThemeLight)
        rbThemeDark = findViewById(R.id.rbThemeDark)

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
            toast(getString(R.string.toast_saved))
        }
        findViewById<Button>(R.id.btnReset).setOnClickListener {
            Prefs.resetAll(this)
            toast(getString(R.string.toast_reset))
            refresh()
        }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            Prefs.clearLog(this)
            refresh()
        }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.btnTestWarn).setOnClickListener { runTest(JobType.WARN) }
        findViewById<Button>(R.id.btnTestBlock).setOnClickListener { runTest(JobType.BLOCK) }
        btnRunNow.setOnClickListener { runPendingNow() }
        findViewById<Button>(R.id.btnCopyBlocked).setOnClickListener { copyBlocked() }
        findViewById<Button>(R.id.btnClearBlocked).setOnClickListener {
            Prefs.clearBlockedList(this)
            toast(getString(R.string.toast_list_cleared))
            refresh()
        }

        load()

        rgLang.setOnCheckedChangeListener { _, id ->
            val v = when (id) {
                R.id.rbLangAr -> "ar"
                R.id.rbLangEn -> "en"
                else -> "system"
            }
            if (v != Prefs.lang(this)) {
                save()
                Prefs.setLangPref(this, v)
                recreate()
            }
        }
        rgTheme.setOnCheckedChangeListener { _, id ->
            val v = when (id) {
                R.id.rbThemeLight -> "light"
                R.id.rbThemeDark -> "dark"
                else -> "system"
            }
            if (v != Prefs.theme(this)) {
                save()
                Prefs.setThemePref(this, v)
                recreate()
            }
        }
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
        cbAuto.isChecked = Prefs.autoRun(this)

        when (Prefs.lang(this)) {
            "ar" -> {
                rbLangAr.isChecked = true
            }
            "en" -> {
                rbLangEn.isChecked = true
            }
            else -> {
                rbLangSystem.isChecked = true
            }
        }
        when (Prefs.theme(this)) {
            "light" -> {
                rbThemeLight.isChecked = true
            }
            "dark" -> {
                rbThemeDark.isChecked = true
            }
            else -> {
                rbThemeSystem.isChecked = true
            }
        }
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
            cbRegular.isChecked,
            cbAuto.isChecked
        )
    }

    private fun runTest(type: JobType) {
        save()
        val num = Util.digits(etTest.text.toString())
        if (num.length < 7) {
            toast(getString(R.string.toast_bad_number))
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
            toast(getString(R.string.toast_need_service))
        } else {
            svc.runNow()
            toast(getString(R.string.toast_test_started))
        }
    }

    private fun runPendingNow() {
        if (JobQueue.pendingCount() == 0) {
            toast(getString(R.string.toast_nothing_pending))
            return
        }
        val svc = GuardAccessibilityService.instance
        if (svc == null) {
            toast(getString(R.string.toast_need_service))
        } else {
            svc.runNow()
            toast(getString(R.string.toast_run_started))
        }
    }

    private fun copyBlocked() {
        val list = Prefs.blockedList(this)
        if (list.isEmpty()) return
        val text = list.joinToString("\n") { "+" + it.first }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("blocked", text))
        toast(getString(R.string.toast_copied))
    }

    private fun mark(b: Boolean): String = if (b) "✅" else "❌"

    private fun refresh() {
        val notifOn = notifEnabled()
        val accOn = accEnabled()
        val overlayOn = Settings.canDrawOverlays(this)
        val pending = JobQueue.pendingCount()
        tvStatus.text = getString(R.string.status_notif, mark(notifOn)) + "\n" +
            getString(R.string.status_acc, mark(accOn)) + "\n" +
            getString(R.string.status_overlay, mark(overlayOn)) + "\n" +
            getString(R.string.status_pending, pending)
        btnRunNow.text = getString(R.string.btn_runnow_n, pending)

        val log = Prefs.logText(this)
        tvLog.text = if (log.isEmpty()) getString(R.string.log_empty) else log

        val list = Prefs.blockedList(this)
        tvBlockedTitle.text = getString(R.string.sec_blocked_n, list.size)
        if (list.isEmpty()) {
            tvBlocked.text = getString(R.string.blocked_empty)
        } else {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            tvBlocked.text = list.joinToString("\n") {
                "+" + it.first + "    " + fmt.format(Date(it.second))
            }
        }
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
