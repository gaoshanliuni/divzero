# MCEF 离线运行库

DivZero 使用独立的 [MCEF-Offline](https://github.com/gaoshanliuni/MCEF-Offline) 分发，Release 标签、源码提交和 SHA-256 固定在 `gradle/mcef-offline.lock.json`。编译 API 为 MCEF 2.2.0，匹配本项目 Minecraft 26.1.2 / Java 25 集成。

## 玩家安装

从 DivZero Release Assets 下载主模组、WebGUI，以及与你的系统和 Java 架构匹配的一个 `mcef-offline-neoforge-<platform>.jar`。

| 平台 | 标识 |
|---|---|
| Windows x64 | windows_amd64 |
| Linux x64 | linux_amd64 |
| macOS Intel | macos_amd64 |
| macOS Apple Silicon | macos_arm64 |

每个游戏实例选择一个 MCEF 平台包。API 和 sources 工件供编译、开发与许可核对使用。

平台 JAR 内置固定版本 JCEF/CEF，启动时在本地解压、校验并复用缓存；损坏缓存由包内资源修复。网页和 AI API 使用正常网络连接。

Windows／Linux 原生 ARM64 列入后续平台适配；分发流程检查 PE／ELF／Mach-O 架构头。

## 构建与发布

`gradle/mcef-offline.gradle` 配置固定 compile-only API 和当前平台的 runtime JAR，编译前核对依赖集合与 SHA-256。`-PmcefOfflinePlatform=macos_arm64` 可选择目标平台；专用服务端配置仅保留所需依赖。

Build JAR 工作流构建与测试 DivZero，再由 `stage-offline-mcef.ps1` 准备四个平台运行包、对应源码、第三方声明与上游 manifest。发布 job 核对固定版本、校验值及公开下载 URL；玩家在 Assets 中选择运行 JAR。

## 更新依赖

1. 在 MCEF-Offline 发布已测试的版本。
2. 核对源码提交、原生库、架构验证与 release manifest。
3. 将精确标签、源码提交、文件大小和 SHA-256 更新到 lock；API、源码、运行包与声明来自同一上游版本。
4. 执行 DivZero 构建和发布 dry-run，完成依赖与产物校验。

`mcef-offline-corresponding-sources.jar` 包含 fork 修改源码与固定 JCEF 对应源码，二进制保留许可证及第三方声明。再分发时请同时提供这些许可与源码入口。

验证按编译、打包、原生库解压／修复、游戏渲染分别记录。可在已备份实例中验证断网首启和本地页面渲染；平台附件与具体游戏场景的验证范围见 [功能说明](FEATURES.md)。
