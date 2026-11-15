val apiVersionMajor = 1
val apiVersionMinor = 4
val apiVersionPatch = 1
// 语义化版本号：放弃 major*10000 + minor*1000 + patch 的数字拼接法，
// 版本号统一用 1.1.0。versionCode 改为简单递增整数。
// 当前对外版本：1.4.1（versionCode 14100）。
val apiVersionName = "1.4.1"
// v1.4.0（versionCode 12，当前开发版本）：
//  - 新增「模块开发 / AI 辅助」（入口在模块页顶部），可新建工程、编辑文件、导出可安装 zip、
//    让 AI 直接产出模块文件；
//  - 拦截抓取：Android 的 sh 是 mksh，它把重定向单独打成一行 trace（`+ >/path`），
//    旧逻辑会把 `>/path` 当垃圾指令、同时按「无重定向」把 echo 写操作丢掉，现改为把重定向片段拼回命令；
//    另外 strace 调用补 `-s 4096`，避免 argv 被默认 32 字节截断后解析出「现实中不存在的指令」。
//  - 运行界面卡在「运行中」：分析页未回调时决策永久挂起，现加返回键/销毁兜底、超时与主动取消。
// 【v1.4.1】版本号收口：功能已累积到 1.9.x，对外统一发布为 1.4.1。
// versionCode 采用「主*10000 + 次*1000 + 修」的数字形式（14100），
// 与旧版 12 相比单调递增，可直接覆盖安装。
val apiVersionCode = 14100

extra["api_version_major"] = apiVersionMajor
extra["api_version_minor"] = apiVersionMinor
extra["api_version_patch"] = apiVersionPatch
extra["api_version_name"] = apiVersionName
extra["api_version_code"] = apiVersionCode
