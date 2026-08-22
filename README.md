# AxManagerD

> 官网 Official Site: **https://axmd.tw.kg**

AxManagerD unlocks device-owner abilities on unrooted Android via Dhizuku. Hide, suspend, force-stop apps, grant/deny permissions, block uninstall, and restart — all through a binder-bridged DPM service. Modern Kotlin + Jetpack Compose dashboard, no root required, modular plugin support.

AxManagerD 是一套面向 Android 的「模块（插件）运行框架 + Xposed 注入引擎」一体化项目，无需 Root，兼容大多数设备与 Android 版本。
AxManagerD is an integrated "module (plugin) runtime framework + Xposed injection engine" project for Android, no root required, compatible with most devices and Android versions.

---

## 🧩 模块部分原理（Module System）

### 模块运行框架（Plugin Runtime）

模块以插件形式运行，由 `reignite`（Plugin Manager / Igniter）统一调度管理：

- **扫描与加载（Scan & Load）**：扫描 `plugins/` 与 `plugins_backup/` 目录，识别每个模块的 `system/bin`、`post-fs-data.sh`、`system.prop`、`service.sh`、`uninstall.sh` 等脚本。
- **生命周期管理（Lifecycle）**：根据 `disable` / `remove` / `update` 标记执行停用、卸载、更新；通过 `service.sh` 以 `setsid` 后台常驻方式启动模块服务。
- **资源注入（Resource Injection）**：`linkBin` 将模块 `system/bin` 下的可执行文件软链接到 `xbin/`；`system.prop` 通过 `resetprop` 注入系统属性；`post-fs-data.sh` 在挂载后执行。
- **卸载还原（Uninstall Restore）**：备份安装的模块在卸载时按 `.backup/props.snapshot` 快照还原被改动的系统属性（Magisk-like rollback）。

The module runtime is managed by `reignite` (Plugin Manager / Igniter):

- **Scan & Load**: scans `plugins/` and `plugins_backup/`, recognizes each module's `system/bin`, `post-fs-data.sh`, `system.prop`, `service.sh`, `uninstall.sh`.
- **Lifecycle**: disables / uninstalls / updates modules via `disable` / `remove` / `update` markers; launches module services via `service.sh` with `setsid` as background daemons.
- **Resource injection**: `linkBin` symlinks executables to `xbin/`; `system.prop` is applied via `resetprop`; `post-fs-data.sh` runs after mount.
- **Uninstall restore**: backup-installed modules restore modified system properties from a `.backup/props.snapshot` snapshot.

### Device Owner 特权执行（Device Owner Privilege）

模块可通过 `axeron-dpm` 脚本调用 Device Owner 特权（需配合 Dhizuku 作为 Device Owner）：

- 通过 `DpmCommandService` 在 manager 进程内执行 `DeviceOwnerPrivilege`，支持应用隐藏/挂起（hide/suspend）、停用/卸载（disable/uninstall）等 DPM API 操作。
- 结果通过结果文件回传给模块的 `action.sh`，返回标准退出码。

Device Owner privileged commands are executed via `axeron-dpm` (requires Dhizuku as Device Owner). `DpmCommandService` executes `DeviceOwnerPrivilege` inside the manager process, supporting app hide/suspend, disable/uninstall and other DPM API operations.

---

## 🛠️ Xposed 部分原理（Xposed / LSPatch Engine）

Xposed 部分基于 **LSPatch**（免 Root 的 Xposed 实现）：

- **APK 打补丁（APK Patching）**：`lspatch-core` 的 `LSPatch` / `ApkPatcher` 将目标 APK 重新打包，注入运行时加载器与 Xposed 框架支持，无需解锁 Bootloader 或刷入 Magisk。
- **Shizuku 服务（Shizuku Service）**：`server` 模块的 `AxeronService` / `ShizukuServiceIntercept` 通过 Shizuku 授权链路获得系统级 binder 权限，执行需要高权限的系统操作。
- **进程注入与 Hook（Process Injection & Hook）**：注入目标应用进程后，可在运行时 Hook 方法、替换实现，实现模块对其它应用的增强（类似 Xposed 模块）。

The Xposed part is based on **LSPatch** (rootless Xposed implementation):

- **APK patching**: `lspatch-core`'s `LSPatch` / `ApkPatcher` repackages the target APK, injecting the runtime loader and Xposed framework support without unlocking the bootloader or flashing Magisk.
- **Shizuku service**: `AxeronService` / `ShizukuServiceIntercept` acquire system-level binder permissions via the Shizuku authorization chain.
- **Process injection & Hook**: after injecting into the target app process, methods can be hooked at runtime to enhance other apps (Xposed-module style).

The Xposed engine (LSPatch) handles runtime hook & process injection, while the module system (reignite) handles plugin management & property/file injection.

---

## 📦 下载与安装（Download & Install）

前往 [Releases](https://github.com/bufanchen121101/AxManagerD/releases) 下载最新 APK 安装即可。
Visit [Releases](https://github.com/bufanchen121101/AxManagerD/releases) to download the latest APK and install it.
