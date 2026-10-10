# 模块开发入门

本文档面向第一次编写 AxManagerD 模块的用户，从零讲清「一个模块由哪些文件组成」「怎么写」「怎么装」。

---

## 1. 模块是什么

一个模块就是一个 **zip 压缩包**，里面是一组约定好名字的文件（`module.prop` 必填，外加若干 `.sh` 脚本）。

安装后它会被解压到：

```
axeron/plugins/<id>/            # 普通（Shell）模块
axeron/runtime_plugins/<id>/    # 运行时模块（module.prop 里带 AxmanagerdID=runtime）
```

模块通过 **shell 脚本** 完成事情，例如读写属性、调用系统命令、执行 action 入口。

> 与 Magisk/KSU 模块最大的区别：AxManagerD 模块 **不做 mount**，所有修改都是「真实写盘」。

---

## 2. 模块的文件结构

```
my_module/
├── module.prop        # 必填：模块元信息
├── customize.sh       # 可选：安装解压后执行
├── action.sh          # 可选：用户点「运行」时执行（主要功能入口）
├── uninstall.sh       # 可选：卸载前执行
├── service.sh         # 可选：后台常驻服务
└── post-fs-data.sh    # 可选：开机早期执行
```

打包时注意：**上述文件必须位于 zip 的根目录**，不要多套一层文件夹。

---

## 3. module.prop 字段详解

```properties
id=my_module             # 必填。模块唯一 ID，决定目录名，只能用小写字母/数字/下划线
name=我的模块            # 必填。列表里显示的名字（授权页也显示这个名字）
author=你的名字           # 选填
version=1.0.0            # 选填，版本号
versionCode=100          # 选填，版本代号（整数，用于升级比对）
description=一句话说明    # 选填
axeronPlugin=14800       # 强烈建议。最低管理器版本编号，14800 = v1.4.8
capabilities=overlay     # 选填。能力声明，见第 7 节
```

要点：字段名**区分大小写**；文件必须是 **UTF-8 且无 BOM**。

---

## 4. 生命周期脚本

| 脚本 | 触发时机 | 典型用途 |
|------|----------|----------|
| `customize.sh` | 安装解压后 | 打印说明、前置检查 |
| `action.sh` | 用户点「运行」 | **主要功能入口**，可接收参数 |
| `service.sh` | 后台拉起 | 常驻服务 |
| `post-fs-data.sh` | 开机早期 | 早期初始化 |
| `uninstall.sh` | 卸载删除目录前 | 清理痕迹 |

`action.sh` 的执行方式等价于 `cd 模块目录 && sh ./action.sh <参数>`。

脚本里拿到自己目录的标准写法：

```sh
MODDIR="$(cd "$(dirname "$0")" && pwd)"
```

---

## 5. 执行 Shell 命令

普通命令直接写即可，不需要额外特权：

```sh
sdk=$(getprop ro.build.version.sdk)
pm list packages -3 2>/dev/null | cut -d':' -f2
dumpsys device_policy 2>/dev/null | head -20
if command -v some_cmd >/dev/null 2>&1; then :; fi
```

写法要点：

- 用 `local` 声明局部变量，避免污染全局；
- 用 `"$@"` 透传参数（保留空格），不要用 `$*`；
- 关键命令后立刻 `rc=$?` 保存退出码，便于透传。

---

## 6. 返回码约定

| 返回码 | 含义 |
|:---:|------|
| `0` | 成功 |
| `1` | 参数错误 / 依赖未激活 |
| `2` | 未知命令 |
| `3` | 权限不足 |
| `4` | 其他异常 |
| `6` | 需要 Root |
| `7` | 模块环境异常（找不到依赖命令） |

---

## 7. 核心文件修改（overlay）能力

模块默认**只能改自己的目录**。若确实需要替换软件自身携带的核心文件，必须先在 `module.prop` 里声明能力：

```properties
capabilities=overlay
```

安装/使用过程中，软件会弹出授权窗口，**由用户决定是否放行**；未声明的模块不会获得该能力，也不会弹窗。

### 7.1 覆盖文件放哪里

在模块目录下建一个 `overlay` 目录，内部**按软件 assets 的相对路径原样镜像**：

```
<模块目录>/overlay/assets/<相对路径>
```

例如要替换 `assets/scripts/functions.sh`，就放：

```
<模块目录>/overlay/assets/scripts/functions.sh
```

支持在同一目录放一个 `priority` 文件（内容为一个整数），
当多个模块覆盖同一个文件时，**数字大的胜出**；未声明时按模块目录名升序决定。

### 7.2 用脚本操作覆盖文件

模块脚本可以调用随软件释放的 `axoverlay` 命令：

```sh
axoverlay info                    # 查看当前模块的授权与覆盖状态
axoverlay check                   # 是否已获得授权
axoverlay request "申请理由"       # 主动发起一次授权申请
axoverlay put assets/xxx.sh ./local.sh   # 写入/更新一个覆盖文件
axoverlay get assets/xxx.sh out.sh       # 取出覆盖文件内容
axoverlay ls                      # 列出已覆盖的文件
axoverlay rm assets/xxx.sh        # 删除一个覆盖文件
axoverlay clear                   # 清空本模块的全部覆盖
axoverlay priority 10             # 设置本模块的覆盖优先级
```

### 7.3 安全提醒

核心文件修改能力很强，被滥用的后果包括：植入未知代码、软件崩溃、数据丢失。
**只对自己完全信任的模块授权**，并随时可在「模块核心文件修改权限」页撤销。

---

## 8. 打包与安装

```sh
cd my_module
zip -r ../my_module.zip module.prop action.sh uninstall.sh
```

然后在软件里「导入 zip」→ 选择该 zip → 安装。

- **默认安装**：卸载不还原；
- **备份安装**：安装前对属性做快照，卸载时按快照还原（适合会改属性的模块）。

---

## 9. 常见问题

**Q1：模块装上了，但列表里看不到？**
检查 `module.prop` 是否在 zip 根目录、`id` 是否合法、编码是否为无 BOM 的 UTF-8。

**Q2：声明了 `capabilities=overlay` 但没有弹授权窗？**
确认总开关（模块核心文件修改权限页顶部）已打开；首次使用需要先同意免责声明。

**Q3：`axoverlay check` 一直返回「未授权」？**
在「模块核心文件修改权限」页找到本模块并打开它的开关；或先执行一次 `axoverlay request`。

**Q4：覆盖文件放好了，却没生效？**
优先检查两点：路径是否与 `assets/` 下的相对路径完全一致；覆盖内容的完整性（例如核心脚本被替换成残缺版时，软件会拒绝采用并回退内置版本）。

**Q5：脚本里能直接用 `su` 吗？**
如果设备已 root 且管理器以 root 模式运行，shell 本身可能就带 root；没有 root 时请使用软件提供的特权命令通道。
