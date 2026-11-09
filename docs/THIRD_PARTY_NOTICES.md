# 第三方依赖与资源

项目源码许可见根目录 Apache-2.0 [LICENSE](../LICENSE)。第三方组件分别遵循自身许可，运行 JAR 保留相应声明与源码入口。

| 组件 | 版本与许可 | 用途／来源 |
|---|---|---|
| Jsoup | 1.18.3，MIT | HTML 解析；许可副本位于 Worker resources，构建核对 Maven 版本与打包内容 |
| Acorn | 8.15.0，MIT | JavaScript 语法解析；解析器与许可位于 scripting resources |
| LDLib2 | 26.1.2.41，LGPL-3.0-only | 原生 UI / HUD；[上游源码](https://github.com/Low-Drag-MC/LDLib2) · [对应版本源码 JAR](https://cdn.modrinth.com/data/B1CBVXHX/versions/15aCZh6V/ldlib2-neoforge-26.1-26.1.2.41-sources.jar) |
| KubeJS | 26.1.2-8.0.6，LGPL-3.0-only | AI 动态界面；[上游源码](https://github.com/KubeJS-Mods/KubeJS) · [对应版本源码 JAR](https://maven.latvian.dev/releases/dev/latvian/mods/kubejs-neoforge/26.1.2-8.0.6/kubejs-neoforge-26.1.2-8.0.6-sources.jar) |
| Better Advanced Tooltips | 2601.1.0-build.9，MIT | KubeJS 依赖；[源码与许可](https://github.com/latvian-dev/better-advanced-tooltips) |
| Gradle Wrapper | 构建文件所列版本 | 保留上游脚本与许可声明 |

NeoForge 与 Minecraft 开发依赖由构建工具获取。游戏本体由用户按其许可安装；YSM 与模型使用对应项目的安装和许可流程。其他 Maven 依赖见模块构建文件。

## 原生界面依赖分发

Release 以独立附件提供原始依赖 JAR，保留上游许可，不修改依赖二进制。固定 Modrinth 版本、文件大小、来源与对应源码在 [准备脚本](../scripts/stage-native-ui-dependencies.ps1) 中列明；Actions 的 DEPENDENCIES.json 记录同一信息。Rhino 已嵌入 DivZero，来源和许可随主 JAR 保留。

1.0.20 起不再分发 MCEF、JCEF 或 WebGUI。历史版本的许可与来源见对应历史 Tag；[旧方案迁移提示](MCEF_OFFLINE.md) 不再提供当前浏览器安装指引。

## Windows x64 媒体依赖

1.0.35 构建将固定版本的媒体工具捆绑在两种 Python 版本中。Java 本地提取并使用固定解码参数调用，检查版本、大小和目录结构，不重新计算文件摘要。公开 Git 管理源码和准备脚本。

- **yt-dlp 2026.08.19**：[源码](https://github.com/yt-dlp/yt-dlp/tree/2026.08.19)，The Unlicense。供已有媒体下载功能使用；新资源读取与 FFmpeg 解码不要求 AI 编写 Python。
- **FFmpeg n8.1.3-6-gff48edd8b2**：[BtbN 工件、对应源代码和构建脚本](https://github.com/BtbN/FFmpeg-Builds/releases/tag/autobuild-2026-09-29-13-10)，LGPL 2.1 或更新版本，共享库构建。归档内 `LICENSE.txt` 与所需 DLL 保留，使用上游原始二进制。

## Java 资源解析器

| 组件 | 固定版本 | 许可与来源 |
| --- | --- | --- |
| Apache PDFBox | 3.0.4 | Apache-2.0；[源码](https://github.com/apache/pdfbox/tree/3.0.4) |
| Apache POI OOXML | 5.4.1 | Apache-2.0；[项目](https://poi.apache.org/) |
| JCodec javase | 0.2.5 | BSD-2-Clause；[源码](https://github.com/jcodec/jcodec) |
| HtmlUnit | 4.13.0 | Apache-2.0；[项目](https://www.htmlunit.org/) |
| TwelveMonkeys ImageIO WebP | 3.12.0 | BSD-3-Clause；[源码](https://github.com/haraldk/TwelveMonkeys) |

这些库及其依赖按固定 Maven 坐标构建；内嵌 JAR 保留上游 LICENSE/NOTICE。动态网页使用有界的只读 GET 请求；解析不提供本机脚本执行授权。

## Java 管理的 Python

内置 Python 版捆绑完整 CPython **3.13.15 / 20260901 / Windows x86_64** `install_only_stripped` 解释器、标准库、DLL，以及 requests、Pillow、NumPy、colorama 和固定传递依赖。首次本机执行经批准后由 Java 离线解压，不依赖系统 Python 或 PowerShell。无 Python 版不包含执行器和上述运行时。

[python-build-standalone Release](https://github.com/astral-sh/python-build-standalone/releases/tag/20260901) · [分发与许可说明](https://github.com/astral-sh/python-build-standalone/blob/main/docs/distributions.rst)

解压保留上游 CPython 及依赖的许可与 notice 文件。随后安装的 Python 包各自遵循其许可证。固定库版本、上游资源 ID 与文件大小见 `META-INF/divzero/python/bundle-lock.json`；许可证随运行时与 wheel 一起捆绑。

项目界面与基础资源随源码提供；用户选择的建筑、皮肤和运行数据由用户管理。


## Microsoft Edge 免费云端朗读协议适配

`EdgeReadAloudAuthentication.java` 的 Sec-MS-GEC v2 算法由 [rany2/edge-tts 的 drm.py](https://github.com/rany2/edge-tts/blob/master/src/edge_tts/drm.py) 翻译为 Java，原项目及该派生文件使用 LGPL-3.0-only；著作权归 rany 和 edge-tts 贡献者。修改为固定微软朗读认证输入的 Java 接口，不提供通用文件/内容摘要能力。

Worker 中 `META-INF/licenses/edge-tts/` 包含原始协议源文件、上游版本说明、LGPL 与 GPL 文本。对应修改后的 Java 源码随同版本 GitHub 源码标签提供；可修改该文件后用项目公开 Gradle 构建重新链接 Worker 或 Mod，无需应用签名私钥。应用其它部分仍遵循各自声明的许可证。朗读通过 Java HTTP/WebSocket 直接访问微软云端，不运行上游 Python 文件。
