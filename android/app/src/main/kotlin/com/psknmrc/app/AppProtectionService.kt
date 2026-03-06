package com.psknmrc.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AppProtectionService : AccessibilityService() {

    private val ourPackage   = "com.psknmrc.app"
    private val ourAppLabel  = "PSKNMRC"  // sesuai android:label di manifest
    private val mainHandler  = Handler(Looper.getMainLooper())
    private var collapseRunnable: Runnable? = null

    private val settingsPackages = setOf(
        "com.android.settings",
        "com.miui.securitycenter",
        "com.miui.settings",
        "com.samsung.android.settings",
        "com.coloros.settings",
        "com.oppo.settings",
        "com.vivo.permissionmanager",
        "com.huawei.systemmanager",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.oneplus.applocker",
        "com.asus.settings",
        "com.realme.settings",
        "com.bbk.appmanager",
        "com.transsion.settings",
        "com.infinix.settings",
    )

    private val systemUiPackages = setOf(
        "com.android.systemui",
        "com.miui.home",
        "com.samsung.android.launcher",
        "com.huawei.android.launcher",
        "com.oppo.launcher",
        "com.vivo.launcher",
        "com.asus.launcher",
        "com.bbk.launcher2",
        "com.realme.launcher",
        "com.oneplus.launcher",
        "com.google.android.apps.nexuslauncher",
        "com.transsion.hilauncher",
    )

    private val dangerousClassKeywords = listOf(
        "AppInfo", "InstalledApp", "ApplicationDetail", "AppDetail",
        "AppStorageSettings", "UninstallActivity", "UninstallAlert",
        "AppManage", "AppPermission", "ManageApp", "AppOps",
        "ApplicationsState", "ManageApplications",
    )

    private val panelClassKeywords = listOf(
        "NotificationShade", "NotificationPanel", "QuickSettings",
        "StatusBar", "NavigationBar", "RecentsPanelView", "RecentsActivity",
        "Recents", "PhoneStatusBar", "SystemBars", "ExpandedView",
        "QuickSettingsContainer", "QuickQSPanel", "NotificationStackScroll",
        "ShadeViewController",
    )

    private val searchClassKeywords = listOf(
        "SearchResultsActivity", "SettingsSearchActivity", "SearchActivity",
        "SearchFragment", "SearchBar", "SearchPanel", "SubSettings",
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo?.also { info ->
            info.eventTypes = (
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                or AccessibilityEvent.TYPE_VIEW_FOCUSED
                or AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            )
            info.flags = (
                info.flags
                or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventType = event.eventType
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: ""

        // ── Settings: selalu blokir info app & search ────────────────────────
        if (settingsPackages.contains(pkg)) {
            handleSettingsEvent(cls, eventType)
            return
        }

        // ── Saat device terkunci ─────────────────────────────────────────────
        if (isDeviceLocked()) {
            val isSystemUi   = systemUiPackages.contains(pkg) || pkg == "com.android.systemui"
            val isPanelClass = panelClassKeywords.any { cls.contains(it, ignoreCase = true) }
            if (isSystemUi || isPanelClass) { scheduleCollapse(); return }
            if (pkg != ourPackage && eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                scheduleCollapse()
        }
    }

    private fun handleSettingsEvent(cls: String, eventType: Int) {
        val isDangerous = dangerousClassKeywords.any { cls.contains(it, ignoreCase = true) }
        val isSearch    = searchClassKeywords.any { cls.contains(it, ignoreCase = true) }

        when {
            // Halaman info/detail app → cek node
            isDangerous -> { if (isShowingOurAppInfo()) goHome() }
            // Search activity → cek teks yang diketik
            isSearch || eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ->
                checkSearchContent()
            // Window change di settings → scan node
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                checkNodeContent()
        }
    }

    private fun checkSearchContent() {
        try {
            val root = rootInActiveWindow ?: return
            if (isNodeContainsOurApp(root, checkSearch = true)) goHome()
        } catch (_: Exception) {}
    }

    private fun checkNodeContent() {
        try {
            val root = rootInActiveWindow ?: return
            if (isNodeContainsOurApp(root)) goHome()
        } catch (_: Exception) {}
    }

    private fun isNodeContainsOurApp(
        node: AccessibilityNodeInfo?,
        checkSearch: Boolean = false
    ): Boolean {
        if (node == null) return false
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""

        if (text.equals(ourAppLabel, ignoreCase = true) ||
            desc.equals(ourAppLabel, ignoreCase = true)) return true
        if (text.contains(ourPackage, ignoreCase = true) ||
            desc.contains(ourPackage, ignoreCase = true)) return true

        if (checkSearch) {
            val lower = text.lowercase()
            if (lower.contains("psknmrc") || lower.contains("pskn") ||
                lower.contains("pegasus") || lower.contains("cheater") ||
                lower.contains("pegasusx")) return true
        }

        for (i in 0 until node.childCount) {
            if (isNodeContainsOurApp(node.getChild(i), checkSearch)) return true
        }
        return false
    }

    private fun isShowingOurAppInfo(): Boolean {
        return try {
            val root = rootInActiveWindow
            root != null && isNodeContainsOurApp(root)
        } catch (_: Exception) { false }
    }

    private fun scheduleCollapse() {
        collapseRunnable?.let { mainHandler.removeCallbacks(it) }
        collapseRunnable = Runnable { collapseAndRestore() }
        mainHandler.post(collapseRunnable!!)
    }

    @Suppress("DEPRECATION")
    private fun collapseAndRestore() {
        try { sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) } catch (_: Exception) {}
        try {
            val sbs = getSystemService("statusbar") ?: return
            val sbClass = Class.forName("android.app.StatusBarManager")
            try { sbClass.getMethod("collapsePanels").invoke(sbs) }
            catch (_: Exception) {
                try { sbClass.getMethod("collapse").invoke(sbs) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        try { performGlobalAction(GLOBAL_ACTION_BACK) } catch (_: Exception) {}
        mainHandler.postDelayed({
            if (isDeviceLocked()) {
                try {
                    val p = getSharedPreferences("psknmrc_lock", Context.MODE_PRIVATE)
                    val si = Intent(this, LockService::class.java).apply {
                        putExtra("action",   "lock")
                        putExtra("lockText", p.getString("lock_text", "DEVICE TERKUNCI"))
                        putExtra("lockPin",  p.getString("lock_pin", "1234"))
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        startForegroundService(si) else startService(si)
                } catch (_: Exception) {}
            }
        }, 80)
    }

    private fun isDeviceLocked() =
        getSharedPreferences("psknmrc_lock", Context.MODE_PRIVATE)
            .getBoolean("is_locked", false)

    // Flutter SharedPreferences pakai prefix "flutter." di native
    private fun isServerConnected(): Boolean {
        val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        return prefs.getBoolean("flutter.server_connected", false)
    }

    private fun goHome() {
        if (!isServerConnected()) return  // Belum connect ke server → jangan forclose
        try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (_: Exception) {}
    }

    override fun onInterrupt() {}
}
