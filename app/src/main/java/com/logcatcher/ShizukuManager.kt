package com.logcatcher

import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shizuku 管理器 - 封装 Shizuku API 调用
 * 通过 Shizuku 权限执行 shell 命令，用于启动应用和抓取日志
 */
object ShizukuManager {

    private const val TAG = "ShizukuManager"

    /**
     * 检查 Shizuku 是否可用且已授权
     */
    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku not available", e)
            false
        }
    }

    /**
     * 获取 Shizuku 版本
     */
    fun getVersion(): Int {
        return try {
            Shizuku.getVersion()
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * 通过 Shizuku 执行 shell 命令
     */
    fun exec(command: String): ShellResult {
        return try {
            val process = newShizukuProcess(arrayOf("sh", "-c", command), null, null)
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            ShellResult(exitCode = exitCode, stdout = stdout, stderr = stderr)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute command: $command", e)
            ShellResult(exitCode = -1, stdout = "", stderr = e.message ?: "Unknown error")
        }
    }

    /**
     * 创建 Shizuku 进程
     *
     * 修复说明：原实现使用反射调用 Shizuku.newProcess 隐藏 API，
     * 在 Shizuku v13 以下版本不存在，且在 ProGuard 混淆环境下签名可能不符。
     *
     * 修复策略（优先级降序）：
     * 1. Shizuku v13+：直接调用公开 API Shizuku.newProcess()
     * 2. Shizuku v11-v12：降级使用反射（API 存在但隐藏）
     * 3. 完全降级：通过 ShizukuBinderWrapper 创建远程进程
     */
    private fun newShizukuProcess(cmd: Array<String>, env: Array<String>?, dir: String?): Process {
        // === 策略一：直接调用公开 API（Shizuku v13+） ===
        try {
            @Suppress("NewApi")
            return Shizuku.newProcess(cmd, env, dir)
        } catch (e: NoSuchMethodError) {
            Log.w(TAG, "Shizuku.newProcess API not found (v13+), trying reflection fallback...")
        } catch (e: Exception) {
            if (e is NoSuchMethodError) {
                Log.w(TAG, "Shizuku.newProcess API not found, trying reflection fallback...")
            } else {
                Log.w(TAG, "Shizuku.newProcess failed, trying fallback...", e)
            }
        }

        // === 策略二：反射调用（Shizuku v11-v12，API 存在但隐藏） ===
        try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            return method.invoke(null, cmd, env, dir) as Process
        } catch (e: Exception) {
            Log.w(TAG, "Reflection fallback failed, trying ShizukuBinderWrapper...", e)
        }

        // === 策略三：ShizukuBinderWrapper 方式（最兼容） ===
        try {
            val binder = Shizuku.getBinder()
            val wrapper = rikka.shizuku.ShizukuBinderWrapper(binder)
            val remoteProcess = rikka.shizuku.ShizukuRemoteProcess(wrapper, cmd, env, dir)
            return remoteProcess
        } catch (e: Exception) {
            Log.e(TAG, "All Shizuku process creation methods failed", e)
            throw RuntimeException("Cannot create Shizuku process. Ensure Shizuku v11+ is installed and authorized.", e)
        }
    }

    /**
     * 启动应用
     */
    fun launchApp(packageName: String): ShellResult {
        return exec("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
    }

    /**
     * 获取应用进程 PID
     */
    fun getAppPids(packageName: String): List<Int> {
        val result = exec("pidof $packageName")
        return result.stdout.trim().split("\\s+".toRegex())
            .filter { it.isNotEmpty() }
            .mapNotNull { it.toIntOrNull() }
    }

    /**
     * 获取应用的友好名称
     */
    fun getAppLabel(packageName: String): String {
        val result = exec("dumpsys package $packageName | grep -i 'ApplicationLabel' | head -1")
        var label = result.stdout.replace("ApplicationLabel:", "").trim()
        if (label.isNotEmpty()) return label

        // 尝试通过 aapt 获取
        val pathResult = exec("pm path $packageName 2>/dev/null | head -1")
        if (pathResult.stdout.contains("package:")) {
            val apkPath = pathResult.stdout.replace("package:", "").trim()
            val labelResult = exec("aapt dump badging \"$apkPath\" 2>/dev/null | grep 'application-label:' | head -1 | cut -d\"'\" -f2")
            if (labelResult.stdout.isNotEmpty()) return labelResult.stdout.trim()
        }

        return packageName
    }

    /**
     * 捕获应用日志
     *
     * 修复说明：
     * - 移除了 logcat -c（清空缓冲区），避免闪退日志被提前清除
     * - 增加了崩溃检测路径，主动查找 FATAL EXCEPTION
     * - 无 PID 时扩宽 grep 匹配规则，捕获崩溃堆栈关键行
     */
    fun captureLog(packageName: String, logcatLines: Int = 500): String {
        val pids = getAppPids(packageName)

        // 【修复】不再清空缓冲区！直接获取日志
        val pidArgs = if (pids.isNotEmpty()) pids.joinToString(" ") { "--pid=$it" } else ""

        // === 崩溃检测：主动查找 FATAL EXCEPTION ===
        val crashCheckCmd = "logcat -d -v time *:E 2>/dev/null | grep -A 30 \"FATAL EXCEPTION\" | grep -i \"$packageName\" | tail -100"
        val crashResult = exec(crashCheckCmd)
        val crashLog = crashResult.stdout.trim()

        // === 获取常规日志 ===
        val logCmd = if (pids.isNotEmpty()) {
            // 有 PID：精确过滤
            "logcat -d -v time $pidArgs *:V 2>/dev/null | tail -$logcatLines"
        } else if (crashLog.isNotEmpty()) {
            // 已崩溃但无 PID：捕获全部崩溃上下文
            "logcat -d -v time *:E 2>/dev/null | grep -A 30 \"FATAL EXCEPTION\" | grep -i \"$packageName\" | tail -$logcatLines"
        } else {
            // 无 PID 也无崩溃：扩宽匹配范围
            "logcat -d -v time *:E 2>/dev/null | grep -iE \"$packageName|FATAL EXCEPTION|beginning of crash\" | tail -$logcatLines"
        }
        val result = exec(logCmd)

        val logContent = when {
            crashLog.isNotEmpty() && result.stdout.trim().isNotEmpty() ->
                "═══ 崩溃信息 ═══\n$crashLog\n\n═══ 常规日志 ═══\n${result.stdout.trim()}"
            crashLog.isNotEmpty() ->
                "═══ 崩溃信息 ═══\n$crashLog"
            result.stdout.trim().isNotEmpty() -> result.stdout.trim()
            else -> "(无错误日志)"
        }

        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault())
            .format(java.util.Date())

        return buildString {
            appendLine("========== 应用启动日志 ==========")
            appendLine("包名: $packageName")
            appendLine("PID: ${pids.joinToString(", ") { it.toString() }}")
            appendLine("捕获时间: $now")
            appendLine("==================================")
            appendLine()
            appendLine(logContent)
            appendLine()
            appendLine("========== 日志结束 ==========")
        }
    }

    /**
     * 检测应用是否正在运行
     */
    fun isAppRunning(packageName: String): Boolean {
        return getAppPids(packageName).isNotEmpty()
    }

    data class ShellResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    )
}