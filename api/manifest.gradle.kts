val apiVersionMajor = 1
val apiVersionMinor = 3
val apiVersionPatch = 1

// 语义化版本号：放弃 major*10000 + minor*1000 + patch 的数字拼接法，
// 版本号统一用 1.1.0。versionCode 改为简单递增整数。
val apiVersionName = "1.3.1"
// 本轮（任务：黑边修复 + 悬浮底栏遮挡 + 授权页模块名/学习入口 + 核心文件修改全量）
// 起 versionCode 由 10 提升为 11，用于与之前的 beta 版本区分（覆盖安装时也能识别为升级）。
val apiVersionCode = 11

extra["api_version_major"] = apiVersionMajor
extra["api_version_minor"] = apiVersionMinor
extra["api_version_patch"] = apiVersionPatch
extra["api_version_name"] = apiVersionName
extra["api_version_code"] = apiVersionCode
