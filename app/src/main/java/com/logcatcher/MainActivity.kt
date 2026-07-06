package com.logcatcher

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val SHIZUKU_REQUEST_CODE = 10086
        private const val LOG_DIR_NAME = "LogCatcher"
    }

    // 视图
    private lateinit var chipTargetApp: Chip
    private lateinit var btnMore: com.google.android.material.button.MaterialButton
    private lateinit var btnLaunch: com.google.android.material.button.MaterialButton
    private lateinit var btnStop: com.google.android.material.button.MaterialButton
    private lateinit var tvLogContent: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var layoutEmptyState: LinearLayout
    private lateinit var layoutLogHeader: LinearLayout
    private lateinit var tvStatusIcon: TextView
    private lateinit var tvStatusText: TextView
    private lateinit var tvLogCount: TextView
    private lateinit var btnSave: com.google.android.material.button.MaterialButton
    private lateinit var btnShare: com.google.android.material.button.MaterialButton
    private lateinit var btnClear: com.google.android.material.button.MaterialButton
    private lateinit var switchErrorMode: MaterialSwitch

    private var isCapturing = false
    private var captureThread: Thread? = null
    private var currentPackageName: String = ""
    private var currentAppLabel: String = ""
    private var capturedLog: String = ""
    private var stopRequested = false
    private var errorMode = false

    // Shizuku 授权真实性校验
    private var shizukuActuallyGranted = false
    private var shizukuChecked = false
    private var shizukuVersionInfo = ""

    // 应用列表
    data class AppInfo(val packageName: String, val label: String, val icon: Drawable, val hasLaunchActivity: Boolean = true, val isSystem: Boolean = false)

    private val shizukuRequestCodeReceiver = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQUEST_CODE) {
            shizukuChecked = true
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                shizukuActuallyGranted = true
                updateShizukuStatus(true)
                showSnackbar("Shizuku 授权成功！")
            } else {
                shizukuActuallyGranted = false
                updateShizukuStatus(false)
                showSnackbar("Shizuku 授权被拒绝")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_LogCatcher)
        setContentView(R.layout.activity_main)

        bindViews()
        initShizuku()
        setupListeners()
    }

    private fun bindViews() {
        chipTargetApp = findViewById(R.id.chip_target_app)
        btnMore = findViewById(R.id.btn_more)
        btnLaunch = findViewById(R.id.btn_launch)
        btnStop = findViewById(R.id.btn_stop)
        tvLogContent = findViewById(R.id.tv_log_content)
        scrollLog = findViewById(R.id.scroll_log)
        layoutEmptyState = findViewById(R.id.layout_empty_state)
        layoutLogHeader = findViewById(R.id.layout_log_header)
        tvStatusIcon = findViewById(R.id.tv_status_icon)
        tvStatusText = findViewById(R.id.tv_status_text)
        tvLogCount = findViewById(R.id.tv_log_count)
        btnSave = findViewById(R.id.btn_save)
        btnShare = findViewById(R.id.btn_share)
        btnClear = findViewById(R.id.btn_clear)
        switchErrorMode = findViewById(R.id.switch_error_mode)

        tvLogContent.movementMethod = ScrollingMovementMethod()
    }

    private fun setupListeners() {
        chipTargetApp.setOnClickListener { showAppPickerDialog() }
        btnMore.setOnClickListener { showOverflowMenu() }
        btnLaunch.setOnClickListener { startCapture() }
        btnStop.setOnClickListener { stopCapture() }
        btnShare.setOnClickListener { shareLog() }
        btnSave.setOnClickListener { saveLogToFile() }
        btnClear.setOnClickListener { clearLog() }

        switchErrorMode.setOnCheckedChangeListener { _, isChecked ->
            errorMode = isChecked
            showSnackbar(if (isChecked) "仅显示错误/警告日志" else "显示全部日志")
        }
    }

    // ========== Shizuku ==========

    private fun initShizuku() {
        Shizuku.addRequestPermissionResultListener(shizukuRequestCodeReceiver)
        checkShizukuRealStatus()
    }

    private fun checkShizukuRealStatus() {
        thread {
            try {
                val binderAlive = Shizuku.pingBinder()
                if (!binderAlive) {
                    shizukuVersionInfo = "Shizuku 服务未运行"
                    runOnUiThread { updateShizukuStatus(false) }
                    return@thread
                }

                val selfPerm = Shizuku.checkSelfPermission()
                shizukuChecked = true
                shizukuActuallyGranted = (selfPerm == PackageManager.PERMISSION_GRANTED)

                val version = Shizuku.getVersion()
                val uid = Shizuku.getUid()
                val mode = when {
                    uid == 0 -> "Root"
                    uid == 2000 -> "ADB"
                    else -> "UID=$uid"
                }
                shizukuVersionInfo = "Shizuku v$version · $mode"

                runOnUiThread { updateShizukuStatus(shizukuActuallyGranted) }
            } catch (e: Exception) {
                Log.e(TAG, "Shizuku check failed", e)
                shizukuChecked = true
                shizukuActuallyGranted = false
                shizukuVersionInfo = "检测失败: ${e.message}"
                runOnUiThread { updateShizukuStatus(false) }
            }
        }
    }

    private fun updateShizukuStatus(granted: Boolean) {
        runOnUiThread {
            if (granted && shizukuActuallyGranted) {
                // 状态显示在更多菜单中，UI上不再显示专用状态行
            }
        }
    }

    private fun requestShizukuPermission() {
        if (!Shizuku.pingBinder()) {
            showSnackbar("Shizuku 服务未运行，请先启动 Shizuku")
            return
        }
        if (Shizuku.getVersion() < 11) {
            showSnackbar("Shizuku 版本过低，请更新")
            return
        }
        try {
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "requestPermission failed", e)
            showSnackbar("请求失败: ${e.message}")
        }
    }

    /**
     * 更多菜单（底部分享/保存/清空和 Shizuku 状态）
     */
    private fun showOverflowMenu() {
        val items = mutableListOf(
            "Shizuku 状态：${if (shizukuActuallyGranted) "已授权" else "未授权"}",
            if (shizukuVersionInfo.isNotEmpty()) shizukuVersionInfo else "检测中…",
            "——",
            if (currentPackageName.isNotEmpty()) "当前：$currentAppLabel" else "未选择应用",
            if (capturedLog.isNotEmpty()) "日志：${capturedLog.count { it == '\n' }} 行" else "无日志"
        )

        val builder = AlertDialog.Builder(this, R.style.ThemeOverlay_Material3_Dialog)
        builder.setTitle("LogCatcher")

        if (!shizukuActuallyGranted) {
            builder.setItems(arrayOf("去授权 Shizuku")) { _, _ ->
                requestShizukuPermission()
            }
        } else {
            builder.setItems(arrayOf("重新检测 Shizuku")) { _, _ ->
                checkShizukuRealStatus()
                showSnackbar("Shizuku 状态已刷新")
            }
        }

        builder.setNeutralButton("关闭", null)
        builder.show()
    }

    // ========== 应用选择器 ==========

    private fun showAppPickerDialog() {
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleLarge).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(
                getColor(R.color.primary)
            )
        }
        val container = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 40, 0, 40)
            addView(progressBar, FrameLayout.LayoutParams(200, 200, android.view.Gravity.CENTER))
        }

        val loadingDialog = AlertDialog.Builder(this, R.style.ThemeOverlay_Material3_Dialog)
            .setTitle(getString(R.string.select_app_title))
            .setView(container)
            .setNegativeButton("取消", null)
            .show()

        thread {
            val apps = getInstalledApps()
            runOnUiThread {
                loadingDialog.dismiss()
                showAppPickerDialogUI(apps)
            }
        }
    }

    private fun getInstalledApps(): List<AppInfo> {
        val pm = packageManager

        val launchIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val launchActivities = try {
            pm.queryIntentActivities(launchIntent, 0)
                .map { it.activityInfo.packageName }
                .toSet()
        } catch (e: Exception) { emptySet() }

        val apps = mutableListOf<AppInfo>()
        try {
            val allApps = pm.getInstalledApplications(0)
            for (app in allApps) {
                try {
                    val pkg = app.packageName
                    val label = app.loadLabel(pm).toString()
                    val icon = app.loadIcon(pm)
                    val isSystem = (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    apps.add(AppInfo(pkg, label, icon,
                        hasLaunchActivity = pkg in launchActivities,
                        isSystem = isSystem))
                } catch (_: Exception) { }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getInstalledApplications failed", e)
            val launcherApps = pm.queryIntentActivities(launchIntent, 0)
            for (ri in launcherApps) {
                try {
                    val pkg = ri.activityInfo.packageName
                    val label = ri.loadLabel(pm).toString()
                    val icon = ri.loadIcon(pm)
                    apps.add(AppInfo(pkg, label, icon, hasLaunchActivity = true, isSystem = false))
                } catch (_: Exception) { }
            }
        }

        return apps.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }

    private fun showAppPickerDialogUI(allApps: List<AppInfo>) {
        val builder = AlertDialog.Builder(this, R.style.ThemeOverlay_Material3_Dialog)
        builder.setTitle(getString(R.string.select_app_title))

        val view = layoutInflater.inflate(R.layout.dialog_app_picker, null)
        val searchEdit = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_search)
        val recyclerView = view.findViewById<RecyclerView>(R.id.rv_apps)
        val progressBar = view.findViewById<ProgressBar>(R.id.progress_loading)
        val systemSwitch = view.findViewById<MaterialSwitch>(R.id.switch_system_apps)
        recyclerView.layoutManager = LinearLayoutManager(this)

        var showSystem = false
        var searchQuery = ""
        var filteredApps = allApps

        fun applyFilter() {
            filteredApps = allApps.filter { app ->
                val matchSystem = showSystem || !app.isSystem
                val matchSearch = searchQuery.isEmpty() ||
                        app.label.lowercase().contains(searchQuery) ||
                        app.packageName.lowercase().contains(searchQuery)
                matchSystem && matchSearch
            }
            val adapter = recyclerView.adapter
            if (adapter is AppPickerAdapter) {
                adapter.updateList(filteredApps)
            }
        }

        val adapter = AppPickerAdapter(filteredApps) { app -> selectApp(app) }
        recyclerView.adapter = adapter

        applyFilter()

        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s.toString().lowercase()
                applyFilter()
            }
        })

        systemSwitch.setOnCheckedChangeListener { _, isChecked ->
            showSystem = isChecked
            applyFilter()
        }

        builder.setView(view)
        builder.setNegativeButton("取消", null)
        builder.show()
    }

    private fun selectApp(app: AppInfo) {
        currentPackageName = app.packageName
        currentAppLabel = app.label

        chipTargetApp.text = app.label
        chipTargetApp.setChipIconResource(android.R.color.transparent)
    }

    // ========== 日志捕获 ==========

    private fun setStatusText(text: String, isActive: Boolean = false, isError: Boolean = false) {
        tvStatusText.text = text
        layoutLogHeader.visibility = View.VISIBLE
        tvStatusIcon.visibility = View.VISIBLE
        if (isError) {
            tvStatusIcon.setBackgroundColor(getColor(R.color.status_error))
            tvStatusText.setTextColor(getColor(R.color.status_error))
        } else if (isActive) {
            tvStatusIcon.setBackgroundColor(getColor(R.color.status_ok))
            tvStatusText.setTextColor(getColor(R.color.status_ok))
        } else {
            tvStatusIcon.setBackgroundColor(getColor(R.color.status_info))
            tvStatusText.setTextColor(android.content.res.ColorStateList.valueOf(getColor(R.color.status_info)))
        }
    }

    private fun updateLogContent(log: String) {
        val lineCount = log.count { it == '\n' }
        tvLogContent.text = log
        tvLogCount.text = getString(R.string.lines_count, lineCount)

        if (log.isNotEmpty()) {
            layoutEmptyState.visibility = View.GONE
            layoutLogHeader.visibility = View.VISIBLE
            scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun setCapturingUI(capturing: Boolean) {
        btnLaunch.isEnabled = !capturing
        btnStop.isEnabled = capturing
        btnSave.isEnabled = !capturing && capturedLog.isNotEmpty()
        btnShare.isEnabled = !capturing && capturedLog.isNotEmpty()
        btnClear.isEnabled = !capturing && capturedLog.isNotEmpty()
        isCapturing = capturing
    }

    private fun startCapture() {
        if (currentPackageName.isEmpty()) {
            showSnackbar(getString(R.string.error_empty_package))
            return
        }

        if (!shizukuActuallyGranted) {
            showSnackbar(getString(R.string.error_shizuku_not_ready))
            checkShizukuRealStatus()
            return
        }

        capturedLog = ""
        tvLogContent.text = ""
        tvLogCount.text = ""
        layoutEmptyState.visibility = View.GONE
        layoutLogHeader.visibility = View.VISIBLE

        setCapturingUI(true)
        setStatusText("正在启动…", isActive = true)
        btnLaunch.text = getString(R.string.action_start)

        captureThread = thread {
            val pkgFilter = currentPackageName
            val startTime = System.currentTimeMillis()

            // 步骤1：采集基线日志
            val baselineLog = ShizukuManager.exec("logcat -d -v time *:V 2>/dev/null | tail -100")
            val baselineContent = baselineLog.stdout.trim()

            // 步骤2：启动应用
            ShizukuManager.launchApp(pkgFilter)
            runOnUiThread { setStatusText("应用已启动，等待日志写入…", isActive = true) }

            // 步骤3：等待3秒，给闪退留缓冲
            Thread.sleep(3000)

            // 步骤4：检查进程状态
            var pids = ShizukuManager.getAppPids(pkgFilter)
            val isCrashed = pids.isEmpty()

            if (isCrashed) {
                runOnUiThread { setStatusText("⚠ 检测到进程已退出，正在捕获崩溃日志…", isError = true) }

                val crashCmd = "logcat -d -v time *:E 2>/dev/null | grep -B 2 -A 50 \"FATAL EXCEPTION\" | tail -200"
                val crashResult = ShizukuManager.exec(crashCmd)

                val errorCmd = "logcat -d -v time *:E 2>/dev/null | grep -iE \"$pkgFilter|FATAL EXCEPTION|beginning of crash|AndroidRuntime|Caused by|at |Process:|${pkgFilter.split(".").last()}\" | tail -200"
                val errorResult = ShizukuManager.exec(errorCmd)

                val finalLog = buildString {
                    if (crashResult.stdout.trim().isNotEmpty()) {
                        appendLine("══════════════ 崩溃堆栈 ══════════════")
                        appendLine(crashResult.stdout.trim())
                        appendLine()
                    }
                    if (errorResult.stdout.trim().isNotEmpty()) {
                        appendLine("══════════════ 错误日志 ══════════════")
                        appendLine(errorResult.stdout.trim())
                    }
                }

                capturedLog = if (finalLog.isNotEmpty()) finalLog else "(应用启动即闪退，未捕获到崩溃日志)\n请尝试手动复现并使用普通模式抓取"

                runOnUiThread {
                    updateLogContent(capturedLog)
                    setStatusText("⚠ 已捕获闪退日志", isError = true)
                    setCapturingUI(false)
                    btnLaunch.text = getString(R.string.action_start)
                }
                return@thread
            }

            // === 进程存活：实时捕获循环 ===
            runOnUiThread { setStatusText("正在实时捕获日志…", isActive = true) }

            while (isCapturing && !stopRequested) {
                pids = ShizukuManager.getAppPids(pkgFilter)

                if (pids.isEmpty()) {
                    // 运行中进程挂了
                    runOnUiThread { setStatusText("⚠ 进程已退出，正在汇总日志…", isError = true) }

                    val crashCmd = "logcat -d -v time *:E 2>/dev/null | grep -B 2 -A 50 \"FATAL EXCEPTION\" | tail -200"
                    val crashResult = ShizukuManager.exec(crashCmd)

                    if (crashResult.stdout.trim().isNotEmpty()) {
                        capturedLog += "\n\n═══════════ 运行时崩溃 ═══════════\n"
                        capturedLog += crashResult.stdout.trim()
                    }

                    runOnUiThread {
                        updateLogContent(capturedLog)
                        setStatusText("⚠ 捕获完成（进程中途崩溃）", isError = true)
                        setCapturingUI(false)
                        btnLaunch.text = getString(R.string.action_start)
                    }
                    return@thread
                }

                val pidFilter = pids.joinToString(" ") { "--pid=$it" }

                val grepFilter = if (errorMode) {
                    "grep -iE \"E/|W/|F/|exception|crash|error|fatal|anr|failed|denied|nullpointer|unable|rejected|timeout|FATAL EXCEPTION\""
                } else {
                    "cat"
                }

                val cmd = "logcat -d -v time $pidFilter *:V 2>/dev/null | $grepFilter | tail -50"
                val result = ShizukuManager.exec(cmd)

                if (result.stdout.isNotEmpty()) {
                    capturedLog = result.stdout
                    runOnUiThread {
                        updateLogContent(capturedLog)
                    }
                }

                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                runOnUiThread {
                    setStatusText("正在捕获… ${elapsed}s", isActive = true)
                }

                Thread.sleep(1000)
            }

            // 正常停止
            runOnUiThread {
                updateLogContent(capturedLog)
                setStatusText("已停止捕获", isActive = false)
                setCapturingUI(false)
                btnLaunch.text = getString(R.string.action_start)
            }
        }
    }

    private fun stopCapture() {
        stopRequested = true
        isCapturing = false
        btnLaunch.isEnabled = true
        btnStop.isEnabled = false
        setStatusText("已停止捕获", isActive = false)
        if (capturedLog.isNotEmpty()) {
            btnSave.isEnabled = true
            btnShare.isEnabled = true
            btnClear.isEnabled = true
        }
    }

    // ========== 文件操作 ==========

    private fun getAppDisplayName(): String {
        return currentAppLabel.ifEmpty { currentPackageName }
    }

    private fun getLogFile(): File {
        val appName = getAppDisplayName()
        val timeStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "${appName}报错日志-$timeStr.txt"
        val downloadDir = File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            ),
            LOG_DIR_NAME
        )
        if (!downloadDir.exists()) downloadDir.mkdirs()
        return File(downloadDir, fileName)
    }

    private fun saveLogToFile() {
        if (capturedLog.isEmpty()) { showSnackbar("暂无日志可保存"); return }
        try {
            val file = getLogFile()
            FileWriter(file).use { it.write(capturedLog) }
            showSnackbar("日志已保存: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Save log failed", e)
            showSnackbar("保存失败: ${e.message}")
        }
    }

    private fun shareLog() {
        if (capturedLog.isEmpty()) { showSnackbar("暂无日志可分享"); return }
        try {
            val cacheFile = File(cacheDir, "share_log.txt")
            FileWriter(cacheFile).use { it.write(capturedLog) }
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", cacheFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "${getAppDisplayName()}报错日志")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "分享日志文件"))
        } catch (e: Exception) {
            Log.e(TAG, "Share log failed", e)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, capturedLog)
                putExtra(Intent.EXTRA_SUBJECT, "${getAppDisplayName()}报错日志")
            }
            startActivity(Intent.createChooser(shareIntent, "分享日志"))
        }
    }

    private fun clearLog() {
        capturedLog = ""
        tvLogContent.text = ""
        tvLogCount.text = ""
        layoutLogHeader.visibility = View.GONE
        layoutEmptyState.visibility = View.VISIBLE
        btnSave.isEnabled = false
        btnShare.isEnabled = false
        btnClear.isEnabled = false
        btnLaunch.text = getString(R.string.action_start)
        setStatusText("日志已清空", isActive = false)
    }

    private fun showSnackbar(message: String) {
        runOnUiThread {
            Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isCapturing = false
        Shizuku.removeRequestPermissionResultListener(shizukuRequestCodeReceiver)
    }
}

/**
 * 应用选择列表适配器
 */
class AppPickerAdapter(
    private var apps: List<MainActivity.AppInfo>,
    private val onSelect: (MainActivity.AppInfo) -> Unit
) : RecyclerView.Adapter<AppPickerAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.iv_app_icon)
        val name: TextView = view.findViewById(R.id.tv_app_name)
        val pkg: TextView = view.findViewById(R.id.tv_app_package)
        val noLauncher: TextView = view.findViewById(R.id.tv_no_launcher)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        holder.icon.setImageDrawable(app.icon)
        holder.name.text = app.label
        holder.pkg.text = app.packageName
        holder.noLauncher.visibility = if (app.hasLaunchActivity) View.GONE else View.VISIBLE
        holder.itemView.alpha = if (app.hasLaunchActivity) 1.0f else 0.7f
        holder.itemView.setOnClickListener { onSelect(app) }
    }

    override fun getItemCount() = apps.size

    fun updateList(newList: List<MainActivity.AppInfo>) {
        apps = newList
        notifyDataSetChanged()
    }
}