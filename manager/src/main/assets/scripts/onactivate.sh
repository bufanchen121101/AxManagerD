#!/system/bin/sh
# ============================================================
# AxManager 激活回调钩子（可选）
# ============================================================
# 触发时机：
#   受非 root 权限限制，本软件无法开机自启动。开机后需要用户通过
#   shell 指令等方式重新激活（触发 Igniter 成功之后），
#   AxeronPluginService.igniteSuspendService() 会调用本脚本。
#
# 路径：
#   释放到 AXERONBIN 目录（assets/scripts/ 会被解压到该目录）。
#   用户也可以直接编辑 AXERONBIN/onactivate.sh 挂载自己的逻辑，
#   只要文件名是 onactivate.sh 就会被执行。
#
# 环境：
#   由 busybox sh 执行；stdout/stderr 会回显到激活流程的日志。
#
# 说明：
#   默认实现为空操作（只打印一行），避免影响激活主流程。
#   需要的自定义动作（例如拉起运行时模块、写入日志、恢复网络等）
#   请取消下方注释或自行追加。
# ============================================================

echo "[onactivate] AxManager 激活回调：$(date 2>/dev/null)"

# ---- 示例：把激活时间写进日志（按需启用）----
# LOG="/sdcard/axeron/logs/onactivate.log"
# mkdir -p "$(dirname "$LOG")" 2>/dev/null
# echo "$(date) activated" >> "$LOG" 2>/dev/null

# ---- 示例：拉起某个运行时模块的动作脚本（按需启用）----
# AX_ROOT="/data/user_de/0/com.android.shell/axeron"
# MOD="$AX_ROOT/runtime_plugins/com.demo.alive10"
# [ -x "$MOD/action.sh" ] && (cd "$MOD" && sh action.sh) &

exit 0
