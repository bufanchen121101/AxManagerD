# AxManagerD

> 🌐 官网 | Official Site: **https://axmd.cc.cd**
> 📦 最新版下载：[Releases](https://github.com/bufanchen121101/AxManagerD/releases)

**AxManagerD** 是一款**免 Root** 的 Android 设备所有者（Device Owner）管理器。它通过 [Dhizuku](https://github.com/iamr0s/Dhizuku) 获得系统最高级别的设备管理权限，让你无需 Root、无需解锁 Bootloader，就能对手机里的每一个应用进行普通工具做不到的深度管理。

---

## 它是干什么的

简单说：它把 Android 里只有「企业管控 / 设备管理员」才能用的 **Device Owner 特权**，带到了普通用户的手机里。

传统上，想要隐藏系统预装应用、冻结应用、禁止卸载，你只能 Root 或刷入 Magisk。而 Root 会解锁 Bootloader、破坏保修、影响 OTA 更新，还有被银行 App、游戏检测到闪退的风险。

**AxManagerD 走的是另一条路**——借助 Dhizuku 把自己注册成「设备所有者」，从而直接调用系统原生的 DevicePolicyManager（DPM）权限。整个过程**不碰系统分区、不修改系统、不影响保修**，卸载 AxManagerD 后一切恢复原状。

---

## 它能帮你做什么

### 🔒 应用隐藏
把不想让别人看到、或者想彻底「雪藏」的应用从启动器和最近任务里隐藏掉。应用并没有被卸载，数据原封不动，需要时一键恢复。

### ⏸️ 应用挂起 / 解挂
把某个应用「挂起」——进程被停止、图标变灰、无法启动，效果等同于卸载，但**不删除数据**。适合处理那种删不掉、又天天在后台偷跑耗电的应用。

### 🛑 强制停止 / 停用 / 禁用
彻底停用系统或第三方应用，防止它们自启动、互相唤醒、偷跑流量和电量。比系统自带的「强行停止」权限更高、更彻底。

### 🚫 禁止卸载
把重要应用锁定，防止被误删，也防止被别的应用恶意卸载（比如家长防止孩子卸载学习软件）。

### 🎛️ 权限管控
授予或拒绝应用的各种权限（包括危险权限），精确掌控每个应用能访问什么、不能访问什么，保护隐私。

### 🔄 一键重启
通过设备所有者特权，直接一键重启设备，无需 Android 11+ 的电源菜单限制。

### 🧩 模块（插件）运行框架
内置 Magisk 式的模块系统，可以加载自定义模块脚本，实现系统属性注入（resetprop）、开机自启脚本、自定义命令执行等高级玩法，把 AxManagerD 当一个「免 Root 的 Magisk」来用。

### 🛠️ Xposed 注入引擎
集成 LSPatch，无需 Root 即可对目标应用「打补丁」，实现运行时 Hook 和进程注入——也就是免 Root 用 Xposed 模块的体验。

---

## 适合谁用

| 场景 | 说明 |
|------|------|
| **精简系统** | 隐藏/冻结厂商预装、无法卸载却又占用资源的应用 |
| **省电控流量** | 停用后台偷跑、互相唤醒的应用 |
| **家长管控** | 锁定重要应用防误删、限制危险权限 |
| **不想 Root 的用户** | 需要系统级权限管理，但不想破坏保修、影响 OTA |
| **折腾党 / 开发者** | 模块化扩展、Xposed Hook、运行时注入、AI 分析 |

---

## 使用方法

1. **安装 AxManagerD**（从 [Releases](https://github.com/bufanchen121101/AxManagerD/releases) 下载最新 APK 安装）。
2. **安装并激活 Dhizuku**：下载 [Dhizuku](https://github.com/iamr0s/Dhizuku)，通过 ADB 将其激活为「设备所有者」（Dhizuku 会引导你执行一条 ADB 命令）。
3. **授权**：打开 AxManagerD，在应用内完成 Dhizuku 授权。
4. **开始使用**：授权成功后，即可在仪表盘里管理应用、加载模块、打补丁。

> 💡 全程无需 Root。激活 Dhizuku 只需电脑连一次 ADB，之后手机端独立使用。

---

## 核心原理

AxManagerD 被 Dhizuku 注册为 Device Owner 后，拥有完整的 DPM 特权。它在自身进程内通过 **binder 桥接**直接调用系统 DevicePolicyManager API 执行操作，能力等同于企业级设备管控，但由你自己掌控。

相比 Root 方案：
- ✅ 不解锁 Bootloader、不刷机、不破坏保修
- ✅ 不影响 OTA 系统更新
- ✅ 不被银行/游戏等应用的 Root 检测拦截
- ✅ 卸载即完全还原，不留后患

---

## 本版更新（v1.1.0 Beta）

- 修复 `axeron-dpm` 脚本更新不生效的问题（改用 sha256 内容比对）
- 修复「Device Owner not active」报错（新增自我 DO 快速通道，绕过 shell 授权校验）
- 新增 AI 分析引擎、模块追踪等能力
- 完善 Dhizuku 服务桥接

---

## 下载

| 文件 | 说明 |
|------|------|
| `AxManager_v1.1.0_3-release.apk` | 已签名、R8 混淆 + 资源裁剪的发布版 |
| `frb-project.keystore` | 发布签名密钥（debug 类型，别名 `androiddebugkey`） |

> ⚠️ 本版为 Beta 测试版，仅供学习与研究使用，请遵守当地法律法规。
> 🔑 仓库附带的签名密钥为 debug 类型密钥，正式发布请自行生成独立 release 密钥并妥善保管。

---

## 致谢

- [Dhizuku](https://github.com/iamr0s/Dhizuku) — 免 Root 的 Device Owner 激活方案
- [Shizuku](https://github.com/RikkaApps/Shizuku) — 系统级权限桥接
- [LSPatch](https://github.com/LSPosed/LSPatch) — 免 Root 的 Xposed 实现

## License

本项目仅供学习与研究使用，请遵守当地法律法规。