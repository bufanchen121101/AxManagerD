# AxManagerD

> 🌐 官网 | Official Site: **https://axmd.cc.cd**

**AxManagerD** 通过 [Dhizuku](https://github.com/iamr0s/Dhizuku) 在**免 Root** 的 Android 设备上解锁 **Device Owner** 特权，提供隐藏 / 挂起 / 强制停止应用、授予 / 拒绝权限、禁止卸载、重启等一系列系统级操作——全部经由 binder 桥接的 DPM（DevicePolicyManager）服务完成。

采用现代 **Kotlin + Jetpack Compose** 仪表盘，无需 Root，内置模块化插件（Magisk 式）运行框架与基于 LSPatch 的 Xposed 注入引擎。

---

## ✨ 功能特性

- 🔒 **Device Owner 特权**：基于 Dhizuku 免 Root 激活，支持应用隐藏 (hide)、挂起/恢复 (suspend)、强制停止、停用/卸载、禁止卸载、重启等 DPM 操作。
- 🧩 **模块（插件）运行框架**：Magisk 式模块管理，扫描 / 加载 / 生命周期管理 / 系统属性注入 / 卸载回滚。
- 🛠️ **Xposed 注入引擎**：内置 LSPatch，无需 Root 即可对目标 APK 打补丁、运行时 Hook 与进程注入。
- 📱 **现代化 UI**：Jetpack Compose 仪表盘，中英双语，深色模式。
- 🔌 **Shizuku 桥接**：通过 Shizuku 授权链路获取系统级 binder 权限。

---

## 📦 下载与安装

前往 [Releases](https://github.com/bufanchen121101/AxManagerD/releases) 下载最新 **`AxManager_v1.1.0_3-release.apk`**（已签名、已混淆并裁剪资源）。

安装后需先安装并激活 [Dhizuku](https://github.com/iamr0s/Dhizuku) 作为 Device Owner，再在 AxManagerD 内完成授权，即可使用全部功能。

---

## 🚀 从源码构建

### 环境要求
- JDK 21
- Android SDK（compileSdk 36，build-tools 36.0.0，NDK 29.0.14206865）

### 构建步骤

```bash
# 1. 配置 local.properties（填入 SDK 路径与签名信息）
cat > local.properties << 'EOF'
sdk.dir=/path/to/android-sdk
signing.storeFile=/path/to/frb-project.keystore
signing.storePassword=android
signing.keyAlias=androiddebugkey
signing.keyPassword=android
EOF

# 2. 编译 debug
./gradlew :manager:assembleDebug --console=plain --no-daemon

# 3. 编译 release（含 R8 混淆 + 资源裁剪）
./gradlew :manager:assembleRelease --console=plain --no-daemon
```

产物输出至 `manager/build/outputs/apk/{debug,release}/`，R8 混淆映射文件输出至 `out/mapping/`。

> ⚠️ **提示**：若编译服务器为 x86_64 且 `assembleRelease` 在 `minifyReleaseWithR8` 阶段 JVM 崩溃（SIGSEGV），请在 `gradle.properties` 中添加以下配置，强制 Gradle 使用系统自带的更新 JDK 而非 toolchain 自动下载的老版本：
> ```properties
> org.gradle.java.installations.paths=/usr/lib/jvm/java-17-openjdk-amd64,/usr/lib/jvm/java-21-openjdk-amd64
> org.gradle.java.installations.auto-download=false
> ```

---

## 🔑 签名密钥说明

仓库根目录提供 `frb-project.keystore`（标准 debug 密钥）用于发布签名：

| 项 | 值 |
|----|-----|
| 别名 (alias) | `androiddebugkey` |
| 密码 (store/key password) | `android` |

> ⚠️ **安全警告**：该密钥为 debug 类型密钥，公开展出仅便于复现构建。若用于正式发布，请自行生成独立的 release 密钥并妥善保管，切勿将私钥提交到公开仓库。

---

## 📁 目录结构

```
AxManagerD/
├── manager/        # 主应用（Compose UI + Device Owner 特权 + DPM 服务）
├── api/            # Axeron API / 插件运行时（AxeronPluginService、axerish 等）
├── server/         # Shizuku 桥接服务
├── lspatch-core/   # LSPatch 注入引擎核心
├── reignite/       # 插件管理器 / Igniter
├── vector-ui/      # UI 组件库
├── adb/            # 内置 adb 桥接
├── build.gradle.kts
└── settings.gradle.kts
```

---

## 🙏 致谢

- [Dhizuku](https://github.com/iamr0s/Dhizuku) — 免 Root Device Owner 激活
- [Shizuku](https://github.com/RikkaApps/Shizuku) — 系统级权限桥接
- [LSPatch](https://github.com/LSPosed/LSPatch) — 免 Root Xposed 实现

## 📄 License

本项目仅供学习与研究使用，请遵守当地法律法规。
