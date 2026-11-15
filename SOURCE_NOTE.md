# 源码说明（AxManagerD v1.4.1）

本包是 AxManagerD v1.4.1 的发布用源码，与同 Release 下的 APK 同源（版本号 1.4.1 / versionCode 14100）。

## 一、已脱敏的内容

| 位置 | 原内容 | 本包内容 |
| --- | --- | --- |
| `manager/build.gradle.kts` 注释 | 中转站域名 | `Your link` |
| `AxeronApplication.kt` 证书固定(SPKI) | 中转站域名 + 3 枚 pin | 域名改为 `Your link` 语义说明，pin 已移除 |
| `AIConfigStore.OFFICIAL_AI_ENDPOINT` | 中转站对话接口 | `Your link` |
| `AIConfigStore.OFFICIAL_AI_DEVICE_URL` | 中转站设备注册接口 | `Your link` |
| `AIConfigStore.OFFICIAL_AI_DEVICE_QUOTA_URL` | 中转站额度接口 | `Your link` |
| `AIConfigStore.OFFICIAL_AI_AXKEY_URL` | 中转站取钥接口 | `Your link` |
| `AIConfigStore.BUILTIN_AI_KEY_BLOB_FALLBACK` | 内置密钥密文（XOR+hex） | 空串 |
| `.ci/axkey_pass.b64` | 取钥口令（base64） | 未包含 |
| `frb-project.keystore`、`keystore/debug.keystore` | 签名与调试签名文件 | 未包含 |

## 二、自行构建前要做的事

1. 把 `AIConfigStore.kt` 中四个 `"Your link"` 换成你自己的服务地址。
2. 需要证书固定时，在 `AxeronApplication.kt` 的 `CertificatePinner.Builder()` 里补回你自己域名的 pin。
3. 需要在 `local.properties`（不随包分发）里提供签名与密钥：
   - `signing.storeFile` / `signing.storePassword` / `signing.keyAlias` / `signing.keyPassword`
   - `officialAiKey`（官方默认 AI 密钥）、`axkeyPass`（取钥口令）、`builtinAiKey`（内置直连密钥）
   未提供密钥时 App 仍可安装运行，只是内置 AI 能力不可用。

## 三、许可

见 `LICENSE`（PolyForm Noncommercial 1.0.0）、`LICENSING.md` 与 `NOTICE`。
其中派生自 LSPatch 的部分遵循 GPL-3.0。
