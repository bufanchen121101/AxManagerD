#!/system/bin/sh
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

install_plugin() {
  local AUTO_ENABLE=$1
  local COMPAT=$2
  local BACKUP=$3
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
  local MODROOT_UPDATE="$AXERONDIR/plugins_update"
  MODID=$(grep_prop id "$TMPPROP")
  MODNAME=$(grep_prop name "$TMPPROP")
  MODAUTH=$(grep_prop author "$TMPPROP")
  MODPLUGIN=$(grep_prop axeronPlugin "$TMPPROP")
  
  # 插件兼容选项：开启后识别 Magisk/KernelSU 等结构相似但未声明 axeronPlugin 的模块
  if [ -z "$MODPLUGIN" ]; then
    if [ "$COMPAT" = "true" ]; then
      ui_print "- Compatibility mode: analyzing module structure"
      ui_print "- axeronPlugin not declared, treating as compatible module"
      MODPLUGIN=0
    else
      abort "! This module not supporting AxManager Plugin!"
    fi
  fi

  [ "$MODPLUGIN" -gt "$AXERONVER" ] && abort "! This module need AxManager Version >= $MODPLUGIN!"
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