# 📱 LogCatcher

**Android 日志捕获应用** — Console 极简控制台风格，基于 Shizuku 实时抓取指定应用的日志输出。

[![API](https://img.shields.io/badge/API-28%2B-brightgreen)](https://developer.android.com/studio/releases/platforms)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.20-blue)](https://kotlinlang.org/)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

---

## ✨ 功能

- **🎯 应用选择器** — 列出所有已安装应用，支持搜索和系统应用切换
- **📋 实时日志捕获** — 通过 Shizuku 获取 `logcat` 输出，按 PID 过滤指定应用日志
- **🔴 崩溃检测** — 自动检测进程退出时的 FATAL EXCEPTION，双通道捕获崩溃堆栈
- **📁 日志保存** — 保存日志为 `.txt` 文件到 `Download/LogCatcher/` 目录
- **📤 一键分享** — 通过 FileProvider 分享日志文件
- **🔄 两种模式** — 普通模式（全部日志）和错误模式（仅 E/W/F 级别 + 异常关键词）
- **🎨 Console 极简风格** — Material 3 双主题（亮/暗），简洁终端式界面

---

## 📋 前置条件

| 条件 | 说明 |
|------|------|
| **Android 版本** | Android 9.0 (API 28) 或更高 |
| **Shizuku** | 必须安装并授权 [Shizuku](https://shizuku.rikka.app/) v13+ |
| **存储权限** | Android 9 以下需要手动授权存储权限（目标版本已声明 maxSdk=28） |

---

## 🚀 快速开始

### 克隆并构建

```bash
git clone https://github.com/a2006-dev/LogCatcher.git
cd LogCatcher
./gradlew assembleDebug
```

### 直接安装 APK

从 [Releases](../../releases) 页面下载最新 APK，或自行构建安装：

```bash
./gradlew installDebug
```

> **注意**：Shizuku 依赖以本地 AAR 文件形式提供（`app/libs/`），无需额外配置远程仓库。

---

## 📖 使用说明

1. **授权 Shizuku** — 首次启动需通过 Shizuku 授权（点击「去授权」按钮）
2. **选择应用** — 点击顶部「选择应用」按钮，从列表中选择目标应用
3. **启动捕获** — 点击 ▶️ 按钮，自动拉起目标应用并开始捕获日志
4. **查看日志** — 实时日志显示在控制台区域，可手动滚动查看历史
5. **保存/分享** — 通过底部操作栏保存日志文件或直接分享

### 捕获流程 (4 步)

1. **基线采集** — 捕获启动前的 100 行日志作为背景
2. **启动应用** — 使用 `monkey` 命令拉起目标 Activity
3. **缓冲等待** — 等待 3 秒确保应用完全启动
4. **实时捕获** — 按 PID 持续过滤日志，进程退出时自动检查崩溃

---

## 🏗️ 技术架构

```
app/
├── src/main/
│   ├── java/com/logcatcher/
│   │   ├── MainActivity.kt      # 主界面 + 核心捕获逻辑 (~671行)
│   │   └── ShizukuManager.kt    # Shizuku 封装 + 进程管理 (~212行)
│   ├── res/
│   │   ├── layout/              # 3 个布局文件（控制台 + 对话框 + 应用列表项）
│   │   ├── drawable/            # 13 个矢量图标 + 形状资源
│   │   ├── values/              # strings / colors(27语义化token) / themes(M3双主题)
│   │   └── xml/file_paths.xml   # FileProvider 路径配置
│   └── AndroidManifest.xml
├── libs/
│   ├── shizuku-api-13.1.5.aar
│   ├── shizuku-aidl-13.1.5.aar
│   └── shizuku-provider-13.1.5.aar
├── build.gradle                 # AGP 8.2.0 + Kotlin 1.9.20
└── ...
```

### 关键依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| AndroidX AppCompat | 1.6.1 | 兼容性框架 |
| Material | 1.11.0 | Material 3 组件库 |
| ConstraintLayout | 2.1.4 | 布局引擎 |
| RecyclerView | 1.3.2 | 应用列表展示 |
| Shizuku | 13.1.5 | 特权进程 API 调用 |

### ShizukuManager 核心设计

- **三层进程创建降级策略**：`newProcess` → `newProcess`(带系统类加载器) → `ProcessBuilder` 回退
- **双通道崩溃检测**：同时监控进程退出码和 `logcat` FATAL EXCEPTION 输出

---

## ⚠️ 已知问题

1. **`setStatusText` 视觉问题** — 方法中使用 `setBackgroundColor` 覆盖圆形 Drawable，API 21+ 应改用 `background.setTint` 保持圆角
2. **`showOverflowMenu` 信息未显示** — AlertDialog 构建了 Shizuku 状态和日志行数数组但未渲染显示
3. **部分图标硬编码黑色** — `ic_stop`、`ic_more_vert`、`ic_chevron_down` 使用 `#FF000000` 填充，暗色主题下可见性差
4. **`LogDetailActivity` 冗余声明** — AndroidManifest 声明了 `.LogDetailActivity` 但无对应类文件

---

## 📄 许可证

本项目基于 MIT 许可证开源 — 详见 [LICENSE](LICENSE) 文件。

---

*Made with ❤️ by a2006-dev*
