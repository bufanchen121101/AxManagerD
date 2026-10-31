# 许可说明 / Licensing

本项目 **源码公开，但不是 OSI 意义上的完全开源项目**。仓库内不同部分适用不同许可，授权范围按**文件路径**划分。

## 1. 本项目原创代码

适用 **PolyForm Noncommercial License 1.0.0**（根目录 `LICENSE`）。

简单说：

- 允许：个人学习、研究、实验、私人娱乐、业余项目、业余爱好，以及慈善组织、教育机构、公共研究机构、公共安全与卫生机构、环保组织、政府机构的**非营利**使用
- 允许：在**非商业**目的下修改源码、二次开发、分发副本（须随附本许可与 Required Notice）
- 禁止：任何**商业用途**（售卖、内置进商业产品与服务、商业分发、用本项目代码提供付费服务）
- 禁止：转授权（sublicense）
- 收到书面违约通知后 **32 天内**完成整改并采取实际补救措施的，许可可继续；否则所有许可立即终止
- 软件按「现状」提供，不附带任何明示或默示担保

商业使用需单独取得书面授权，请联系仓库所有者。

## 2. 第三方组件（不在上述许可范围内）

| 路径 | 来源 | 许可 |
| --- | --- | --- |
| `lspatch-core/`、`manager/src/main/java/org/lsposed/lspatch/`、`manager/src/main/aidl/org/lsposed/lspatch/` | [LSPatch](https://github.com/LSPosed/LSPatch) 及其派生代码 | GNU GPL-3.0 |
| `manager/src/main/assets/lspatch.jar`、`manager/src/main/assets/lspatch/`（`loader.dex`、`metaloader.dex`、`so/*/liblspatch.so`） | LSPatch 预编译产物 | GNU GPL-3.0 |
| `lspatch-core/libs/apkzlib-fork.jar`、`pxb.android.*`、`com.wind.meditor.*` | 各自上游项目 | 保留原有许可（Apache-2.0 等），见文件头部声明 |
| 其余依赖（AndroidX、Kotlin、OkHttp、Shizuku、Dhizuku 等） | 各自上游项目 | 各自原有许可 |

> GPL-3.0 与本项目原创代码所用的 PolyForm Noncommercial 1.0.0 **互不兼容**，因此上述第三方部分不并入本项目的许可，而是按路径独立存在。

## 3. 模块（Modules）

运行在本框架上的模块由各自作者授权，与本仓库许可无关。

## 4. 免责声明

本项目仅供学习与研究使用，请遵守当地法律法规，使用者自行承担一切风险。

---

## English summary

The original code of AxManagerD is licensed under the **PolyForm Noncommercial
License 1.0.0** — source-available, free for noncommercial use, modification and
redistribution, but **commercial use is not permitted**.

Third-party components keep their own licenses: LSPatch-derived code and the
bundled LSPatch binaries are **GPL-3.0**; other dependencies keep theirs.
See the table above.
