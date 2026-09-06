val apiVersionMajor = 1
val apiVersionMinor = 1
val apiVersionPatch = 0

// 语义化版本号：放弃 major*10000 + minor*1000 + patch 的数字拼接法，
// 版本号统一用 1.1.0。versionCode 改为简单递增整数。
val apiVersionName = "1.1.0"
val apiVersionCode = 3

extra["api_version_major"] = apiVersionMajor
extra["api_version_minor"] = apiVersionMinor
extra["api_version_patch"] = apiVersionPatch
extra["api_version_name"] = apiVersionName
extra["api_version_code"] = apiVersionCode
