# 第三方依赖与资源

项目源码许可见根目录 Apache-2.0 [LICENSE](../LICENSE)。第三方组件分别遵循自身许可，运行 JAR 保留相应声明与源码入口。

| 组件 | 版本与许可 | 用途／来源 |
|---|---|---|
| Jsoup | 1.18.3，MIT | HTML 解析；许可副本位于 Worker resources，Maven 工件 SHA-256 在构建时验证 |
| Acorn | 8.15.0，MIT | JavaScript 语法解析；解析器与许可位于 scripting resources |
| WebGUI | 1.6.2+mc26.1.2，MIT | [上游源码](https://github.com/mc-webgui/webgui/tree/v1.6.2) |
| MCEF / MCEF-Offline | 2.2.0，LGPL-2.1-or-later | [MCEF 上游](https://github.com/Keksuccino/mcef/) · [Offline 分发](https://github.com/gaoshanliuni/MCEF-Offline)，精确版本见 `gradle/mcef-offline.lock.json` |
| Gradle Wrapper | 构建文件所列版本 | 保留上游脚本与许可声明 |

NeoForge 与 Minecraft 开发依赖由构建工具获取。游戏本体由用户按其许可安装；YSM 与模型使用对应项目的安装和许可流程。其他 Maven 依赖见模块构建文件。

## MCEF 原生运行库与对应源码

MCEF Offline 平台 JAR 包含固定 JCEF/CEF 运行库及其第三方声明。Release 正文提供该固定版本的 `mcef-offline-corresponding-sources.jar` 链接，源码包包含 fork 修改和对应 JCEF 源码。

分发版本、完整 hash、大小与上游提交见 lock 文件；安装范围见 [MCEF_OFFLINE.md](MCEF_OFFLINE.md)。

## 可选 Windows x64 媒体依赖

准备脚本下载并校验固定工件；媒体版构建将所需工具嵌入 Worker，运行时验证后提取，按独立进程调用。公开 Git 管理源码和准备脚本。

- **yt-dlp 2026.08.19**：[源码](https://github.com/yt-dlp/yt-dlp/tree/2026.08.19)，The Unlicense。EXE SHA-256：`66674953fe251b89f4d08c5f0e35e0728679bd67ab3d7d05c0562af101dd3e7a`。
- **FFmpeg n8.1.2-51-g7ba069f4f1**：[BtbN 工件、源代码和构建脚本](https://github.com/BtbN/FFmpeg-Builds/releases/tag/autobuild-2026-09-07-15-39)，LGPL 2.1 或更新版本，共享库构建。归档 SHA-256：`afb1c55ecdef6b80f6243984954dbfe350d44c7da2b64f30575c283b68214825`。归档内 `LICENSE.txt` 随安装保留，程序使用上游原始二进制。

## Java 管理的 Python

本机确认后，Java 可下载独立分发的 CPython **3.13.15 / 20260901 / Windows x86_64** `install_only_stripped` 工件。

[python-build-standalone Release](https://github.com/astral-sh/python-build-standalone/releases/tag/20260901) · [分发与许可说明](https://github.com/astral-sh/python-build-standalone/blob/main/docs/distributions.rst)

解压保留上游 CPython 及依赖的许可与 notice 文件。随后安装的 Python 包各自遵循其许可证。Mod JAR 提供管理程序，Python 发行包由本机确认流程独立准备。

项目界面与基础资源随源码提供；用户选择的建筑、皮肤和运行数据由用户管理。
