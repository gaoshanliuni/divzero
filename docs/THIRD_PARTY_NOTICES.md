# 第三方依赖与资源

项目源码许可见根目录 Apache-2.0 `LICENSE`。各第三方依赖仍遵循其各自许可，不能用项目许可替代。

- Jsoup **1.18.3**（MIT）：用于纯解析 HTML，不执行网页脚本。许可副本在 Worker resources 中，Maven artifact SHA-256 在构建文件中校验。
- Acorn **8.15.0**（MIT）：仅用于 JavaScript 语法分析；原许可与解析器一同保留在 scripting resources。
- NeoForge、Minecraft 开发依赖通过构建工具获取；不随源码快照分发游戏本体。
- WebGUI **1.6.2+mc26.1.2**（MIT）与 MCEF **2.2.0**（上游 LGPL-2.1-or-later）：独立安装工件；MCEF/Chromium/JCEF 原生运行库需要保留上游第三方声明。此快照不分发其原生库或 YSM 模型。
- Gradle Wrapper 保留上游脚本声明；其它 Maven 依赖版本见模块构建文件。

## 可选 Windows x64 媒体依赖

这些二进制不进入公开 Git；准备脚本只下载并校验固定版本。构建媒体版时会嵌入 Worker，运行时再次校验后才提取。下载与再分发时保留上游许可及对应源代码获取信息。

- **yt-dlp 2026.08.19**：[上游及源码](https://github.com/yt-dlp/yt-dlp/tree/2026.08.19)，The Unlicense。固定 exe SHA-256：`66674953fe251b89f4d08c5f0e35e0728679bd67ab3d7d05c0562af101dd3e7a`。
- **FFmpeg n8.1.2-51-g7ba069f4f1**：[BtbN 构建与源代码/构建脚本](https://github.com/BtbN/FFmpeg-Builds/releases/tag/autobuild-2026-09-07-15-39)，LGPL 2.1 或更新版本，共享库构建。固定归档 SHA-256：`afb1c55ecdef6b80f6243984954dbfe350d44c7da2b64f30575c283b68214825`。归档内 `LICENSE.txt` 保持不变并在安装时保留；程序以独立进程调用，不修改这些二进制。

程序中自带的界面与基础示例资源来自项目源码。旧示例/测试开关不构成当前动态生成能力已经完整交付的证据；不分发用户上传的蓝图、皮肤、存档或聊天数据。

## Optional managed Python runtime

Java may download the separately distributed CPython 3.13.15 Windows x86_64 `install_only_stripped` artifact from [astral-sh/python-build-standalone release 20260901](https://github.com/astral-sh/python-build-standalone/releases/tag/20260901), only after local approval. The archive is not included in this Git repository or Mod JAR. Its upstream license/notice files are retained unchanged during extraction; consult the [distribution documentation](https://github.com/astral-sh/python-build-standalone/blob/main/docs/distributions.rst) and the bundled notices for CPython and its dependencies. Python packages installed later by the user/Agent have their own licenses and are not part of this source snapshot.
