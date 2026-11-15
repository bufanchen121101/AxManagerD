#!/system/bin/sh
# 【自愈】AXERONDIR / AXERONVER 兜底。
#   本脚本固定位于 $AXERONDIR/bin/functions.sh，若环境变量缺失（服务端环境
#   注入失败、或经 busybox standalone 启动导致环境为空），这里按脚本自身
#   位置反推 AXERONDIR，避免 MODROOT 退化成 /plugins；AXERONVER 缺失时置 0，
#   让版本校验退化为「一律放行」而不是触发算术错误（exit 2）。
if [ -z "$AXERONDIR" ]; then
  if [ -n "$AXERONBIN" ]; then
    AXERONDIR="${AXERONBIN%/bin}"
  elif [ -n "$0" ] && [ -f "$0" ]; then
    AXERONDIR=$(cd "$(dirname "$0")/.." 2>/dev/null && pwd)
  else
    AXERONDIR=/data/user_de/0/com.android.shell/axeron
  fi
fi
# AXERONVER 缺失时置为一个极大值，令下方版本校验
#   [ "$MODPLUGIN" -gt "$AXERONVER" ] 恒为假 → 退化为「一律放行」，
# 既不触发算术错误（避免 exit 2），也不会误拦任何新格式模块。
[ -z "$AXERONVER" ] && AXERONVER=999999999
ui_print() {
  echo "$1"
}

abort() {
  ui_print "$1"
  [ -n "$PLUGPATH" ] && rm -rf "$PLUGPATH"
  rm -rf "$TMPDIR"
  exit 1
}

print_title() {
  local len line1len line2len bar
  line1len=$(echo -n "$1" | wc -c)
  line2len=$(echo -n "$2" | wc -c)
  len=$line2len
  [ "$line1len" -gt "$line2len" ] && len=$line1len
  len=$((len + 2))
  bar=$(printf "%${len}s" | tr ' ' '*')
  ui_print "$bar"
  ui_print " $1 "
  [ "$2" ] && ui_print " $2 "
  ui_print "$bar"
}


grep_prop() {
  local REGEX="s/^$1=//p"
  shift
  local FILES=$@
  [ -z "$FILES" ] && FILES='/system/build.prop'
  cat "$FILES" 2>/dev/null | dos2unix | sed -n "$REGEX" | head -n 1
}

grep_get_prop() {
  result=$(grep_prop "$@")
  if [ -z "$result" ]; then
    # Fallback to getprop
    getprop "$1"
  else
    echo "$result"
  fi
}

#PLUGIN_INSTALLER

api_level_arch_detect() {
  API=$(grep_get_prop ro.build.version.sdk)
  ABI=$(grep_get_prop ro.product.cpu.abi)
  if [ "$ABI" = "arm64-v8a" ]; then
    ARCH=arm64
    ABI32=armeabi-v7a
    IS64BIT=true
  elif [ "$ABI" = "x86_64" ]; then
    ARCH=x64
    ABI32=x86
    IS64BIT=true
  elif [ "$ABI" = "armeabi-v7a" ]; then
    ARCH=arm
    ABI32=armeabi-v7a
    IS64BIT=false
  elif [ "$ABI" = "x86" ]; then
    ARCH=x86
    ABI32=x86
    IS64BIT=false
  elif [ "$ABI" = "riscv64" ]; then
    ARCH=riscv64
    ABI32=riscv32
    IS64BIT=true
  fi
}

set_perm() {
  chown "$2":"$3" "$1" || return 1
  chmod "$4" "$1" || return 1
}

# $1 = MODPATH
set_perm_recursive() {
  find "$1" -type d 2>/dev/null | while read -r dir; do
    set_perm "$dir" "$2" "$3" "$4" "$6"
  done
  find "$1" -type f -o -type l 2>/dev/null | while read -r file; do
    set_perm "$file" "$2" "$3" "$5" "$6"
  done
}

reset_manager() {
  local debug=$1

  print_title "Resetting AxManager"
  ui_print
  ui_print "$AXERONDIR"
  ui_print "- Removing plugins"

  for plugin in "$AXERONDIR"/plugins/*; do
      [ -d "$plugin" ] || continue

      echo "- Mark to remove $plugin"
      touch "$plugin/remove"
  done

  export CLASSPATH="${AXERONBIN}/ax_reignite.dex";
  app_process / frb.axeron.reignite.Igniter "$debug"

  rm -rf "$AXERONDIR"

  ui_print "Complete"
}

uninstall_axmanager() {
  local debug=$1
  local package=$2

  print_title "Uninstalling AxManager"
  ui_print

  for plugin in "$AXERONDIR"/plugins/*; do
      [ -d "$plugin" ] || continue

      echo "- Mark to remove $plugin"
      touch "$plugin/remove"
  done

  export CLASSPATH="${AXERONBIN}/ax_reignite.dex";
  app_process / frb.axeron.reignite.Igniter "$debug"

  rm -rf "$AXERONDIR"

  echo "GoodBye :)"

  sleep 3

  pm uninstall "$package"
  pkill -f axeron_server
}

# 【v1.4.1】把模块 id 规范成「安全的目录名」。
#
# 只做目录名安全化，**不参与任何识别判断**（模块能不能装由结构决定，见
# module_structure_supported）。之所以必须做：
#   - 旧实现直接把 module.prop 里的 id 拼进路径，id 里带空格/斜杠/中文就会拼出
#     坏路径，mkdir 失败 → 安装失败（这正是「因模块 ID 导致装不上」的来源）；
#   - id 为空时旧实现会得到 MODPATH=$MODROOT/ ，随后的 rm -rf "$MODPATH" 会
#     把整个插件目录删掉，属于危险路径。
# 合法 id（[A-Za-z0-9._-]）经过本函数保持不变，因此不影响已装模块的目录名。
sanitize_module_id() {
  local raw="$1"
  local zip="$2"
  local out
  out=$(printf '%s' "$raw" | tr -c 'a-zA-Z0-9._-' '_')
  out=$(printf '%s' "$out" | tr -s '_')
  out=$(printf '%s' "$out" | sed 's/^[._-]*//; s/[._-]*$//')
  if [ -z "$out" ]; then
    out=$(basename "$zip" 2>/dev/null | sed 's/\.zip$//' | tr -c 'a-zA-Z0-9._-' '_')
    out=$(printf '%s' "$out" | tr -s '_' | sed 's/^[._-]*//; s/[._-]*$//')
  fi
  [ -z "$out" ] && out="module_$(date +%s)"
  printf '%s' "$out"
}
# 【v1.4.1】按 Zip 内的**结构**判断是不是 shell 类模块（与模块 id 无关）。
# 只要存在下列任一结构，就认为它可以按 shell 模块安装：
# service.sh / post-fs-data.sh / action.sh / uninstall.sh / customize.sh / system/
module_structure_supported() {
  local zip="$1"
  local list
  list=$(unzip -l "$zip" 2>/dev/null)
  [ -z "$list" ] && list=$(unzip -Z1 "$zip" 2>/dev/null)
  [ -z "$list" ] && return 1
  printf '%s\n' "$list" | grep -Eq '(service\.sh|post-fs-data\.sh|action\.sh|uninstall\.sh|customize\.sh|system/)$'
}
install_plugin() {
  local AUTO_ENABLE=$1
  local COMPAT=$2
  local BACKUP=$3
  local RUNTIME=$4
  rm -rf "$TMPDIR"
  mkdir -p "$TMPDIR"
  cd "$TMPDIR" || exit
  
  api_level_arch_detect
  
  TMPPROP=$TMPDIR/module.prop
  unzip -o "$ZIPFILE" module.prop -d "$TMPDIR" >&2
  [ ! -f "$TMPPROP" ] && abort "! Error: module.prop not detected!"
  # 备份安装使用独立目录，避免与默认安装（plugins/）冲突
  local MODROOT="$AXERONDIR/plugins"
  [ "$BACKUP" = "true" ] && MODROOT="$AXERONDIR/plugins_backup"
  # 运行时模块装到独立目录，与普通插件完全隔离
  [ "$RUNTIME" = "true" ] && MODROOT="$AXERONDIR/runtime_plugins"
  local MODROOT_UPDATE="$AXERONDIR/plugins_update"
  MODID=$(grep_prop id "$TMPPROP")
  MODNAME=$(grep_prop name "$TMPPROP")
  MODAUTH=$(grep_prop author "$TMPPROP")
  MODPLUGIN=$(grep_prop axeronPlugin "$TMPPROP")

  # 【v1.4.1 修复 · 插件兼容按「结构」识别，不再依赖模块 ID / axeronPlugin】
  #
  # 旧行为：module.prop 里没有 axeronPlugin 时，非兼容模式一律 abort；兼容模式虽然放行，
  # 但后续仍拿原始 id 拼目录名，Magisk/KernelSU 模块的 id 常带大写、点、空格甚至中文，
  # 拼出来的路径非法 → mkdir 失败 → 用户看到「安装失败」，却与模块本身无关。
  #
  # 现在：兼容模式改为**只看 Zip 结构**（service.sh / post-fs-data.sh / action.sh /
  # uninstall.sh / customize.sh / system/ 任一即视为 shell 类模块），完全不看 id；
  # 关闭兼容模式时保持原有的严格校验（未声明 axeronPlugin 即拒绝）。
  if [ -z "$MODPLUGIN" ]; then
    if [ "$COMPAT" = "true" ]; then
      ui_print "- Compatibility mode: detecting module by layout (module id is ignored)"
      if module_structure_supported "$ZIPFILE"; then
        ui_print "- Shell module layout detected, treating as compatible module"
        MODPLUGIN=0
      else
        abort "! This zip is not a shell module (no service.sh / post-fs-data.sh / action.sh / system)"
      fi
    else
      abort "! This module not supporting AxManager Plugin!"
    fi
  fi

  # 版本兼容：旧版 AxManager 用 major*10000+minor*1000+patch 编码（如 1.4.0 -> 14000），
  # 新版改为简单递增整数。两者数值域不重叠，故对 axeronPlugin >= 10000 的旧格式声明
  # 直接放行（无法用新编码的整数比较），仅对新格式（< 10000）做版本校验。
  case "$MODPLUGIN" in
    ''|*[!0-9]*) : ;;                                  # 空或非数字：无法比较，放行
    *)
      if [ "$MODPLUGIN" -lt 10000 ]; then
        [ "$MODPLUGIN" -gt "$AXERONVER" ] && abort "! This module need AxManager Version >= $MODPLUGIN!"
      else
        ui_print "- Legacy axeronPlugin encoding ($MODPLUGIN), skipping version check"
      fi
      ;;
  esac
  # 【v1.4.1】模块 id 在这里只当「目录名」用：非法字符降级为下划线、空值用 zip 名兜底。
  # 无论 id 长成什么样，都不会再拼出坏路径或删错目录（这也是「因 ID 装不上」的根治点）。
  MODID=$(sanitize_module_id "$MODID" "$ZIPFILE")
  ui_print "- Install dir: $MODID"

  MODPATH=$MODROOT/$MODID
  MODPATH_UPDATE=$MODROOT_UPDATE/$MODID
  
  rm -rf "$MODPATH"
  mkdir -p "$MODPATH"
  
  print_title "$MODNAME" "by $MODAUTH"
  print_title "Powered by AxManager"

  unzip -o "$ZIPFILE" customize.sh -d "$MODPATH" >&2

  if ! grep -q '^SKIPUNZIP=1$' "$MODPATH"/customize.sh 2>/dev/null; then
    ui_print "- Extracting module files"
    unzip -o "$ZIPFILE" -x 'META-INF/*' -d "$MODPATH" >&2
    set_perm_recursive "$MODPATH" 2000 2000 0755 0755
  fi

  # Load customization script
  if [ "$BACKUP" = "true" ]; then
    ui_print "- Backup install: snapshotting original state for rollback"
    mkdir -p "$MODPATH/.backup"
    # 1) 系统属性全量快照（卸载时用 diff 还原被改的属性）
    getprop > "$MODPATH/.backup/props.snapshot" 2>/dev/null || true
    # 2) 记录脚本内容，供卸载还原时解析参考
    cp -f "$MODPATH/customize.sh" "$MODPATH/.backup/customize.sh" 2>/dev/null || true
    touch "$MODPATH/.backup/backup_install"
  fi
  [ -f "$MODPATH"/customize.sh ] && . "$MODPATH"/customize.sh
  
  rm -rf \
  "$MODPATH"/system/placeholder "$MODPATH"/customize.sh \
  "$MODPATH"/README.md "$MODPATH"/.git*
  rmdir -p "$MODPATH" 2>/dev/null
  
  cd /
  rm -rf "$TMPDIR"

  rm -rf "$MODPATH_UPDATE"
  mkdir -p "$MODPATH_UPDATE"
  touch "$MODPATH"/update
  touch "$MODPATH_UPDATE"/update_install
  [ -n "$AUTO_ENABLE" ] && [ "$AUTO_ENABLE" != "true" ] && touch "$MODPATH/disable"
  
  ui_print "- Done"
}

BOOTMODE=true