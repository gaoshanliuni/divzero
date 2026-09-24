# 版本、构建与安装

[返回首页](../README.md) · [Releases](https://github.com/gaoshanliuni/divzero/releases) · [全部功能](FEATURES.md) · [Build JAR](https://github.com/gaoshanliuni/divzero/actions/workflows/build-jar.yml)

## 下载哪些文件

Release 的 **Assets** 只上传可安装的运行 JAR：

1. `DivZero-<版本>-mc26.1.2-<类型>-<提交号>.jar`：主模组。
2. `webgui-neoforge-1.6.2+mc26.1.2.jar`：WebGUI。
3. 下表中与你系统及 **Java 架构** 匹配的 **一个** MCEF。

| 系统／Java架构 | MCEF 文件 |
|---|---|
| Windows x64 | `mcef-offline-neoforge-windows_amd64.jar` |
| Linux x64 | `mcef-offline-neoforge-linux_amd64.jar` |
| macOS Intel | `mcef-offline-neoforge-macos_amd64.jar` |
| macOS Apple Silicon | `mcef-offline-neoforge-macos_arm64.jar` |

因此完整跨平台 Release 有6个运行 JAR，**每位玩家只安装3个**。不能同时安装多个 MCEF 平台包，也不能与旧在线 MCEF 一起安装。当前不提供原生 Windows/Linux ARM64 包；有平台附件不等于所有 Mod 功能已在该平台完整验收，例如本机 Python 仍限 Windows x64。

关闭游戏后，在已备份的测试实例将选中的三个 JAR 放进 `mods`，移除同 Mod 的旧版本。需要 **Minecraft 26.1.2 / NeoForge 26.1.2.106 / Java 25**，客户端与服务端同步升级。MCEF 离线包提供匹配平台的浏览器运行库，AI API 和在线网页仍需网络。

首次进入世界，开启作弊／具备真实管理权限后使用 `/ai accept`；F2 → 更多 → API 设置配置模型。不要把 Key 发到聊天或 Issues。

## 哪些内容不再作为 Release 附件

- 不再上传独立的 README、BUILD-INFO、DEPENDENCIES、SHA256SUMS、许可文本和说明 Markdown。
- 不再重复上传开发源码／javadoc／API JAR，或旧在线 MCEF 备选。
- **校验值直接写在 Release 正文**；构建提交与 Actions 记录可以追溯。
- 主 JAR 内含 DivZero 许可证和第三方说明；WebGUI/MCEF 原有许可不移除。MCEF Offline 的固定对应源码和许可入口保留在 Release 正文，链接到其精确上游版本。
- 完整校验、构建身份、来源和许可证仍先在 CI 暂存并验证；只是不用它们占据玩家的附件列表。Actions 审计工件保留7天，永久来源另见仓库文档与上游固定版本。

GitHub 会自动显示 **Source code (zip/tar.gz)**。这是 GitHub 提供的源码下载，不是我们上传的安装附件，不能通过筛选上传文件去掉；不要放进 `mods`。历史已发布版本保留原始标签、二进制和附件，新的附件规则不改写旧版本。

## 版本不再固定为0.1.0

发布版本按**该公开提交的 UTC 日期＋公开历史提交序号**生成，例如 `2026.9.24-dev.42`。

- 每个新的公开提交得到新的开发版本；同一提交重跑保持同一Mod版本，运行ID／attempt仍用于区分不可变发布标签。
- `get-build-version.ps1`需要完整公开Git历史；Actions使用`fetch-depth: 0`，不把浅克隆的“1次提交”当版本序号。
- CI把结果以`-Pmod_version=...`传给所有Gradle模块，因此 **JAR文件名、NeoForge元数据、Release标题和构建记录一致**。打包时读取真实JAR验证，版本不一致拒绝发布。
- `gradle.properties`中的`0.1.0-SNAPSHOT`只保留为未使用发布流程的本地开发默认值，不再作为GitHub发布版本。无需改动私密开发仓库的版本文件，也不会在下次干净快照导出时丢失公开发版规则。
- 本地按相同规则构建（需要Java25、PowerShell7和完整Git checkout）：

```powershell
$version = ./scripts/get-build-version.ps1
./gradlew.bat -I scripts/release-build.init.gradle "-Pmod_version=$version" :neoforge:jar
```

## 自动构建和发布

- 公开 `main` 推送：编译、测试、打包成功后生成开发预发布。
- Pull Request：只构建测试，不发布。
- Actions → Build JAR → Run workflow：可手动构建；只有本仓库 `main` 能发布。
- 同分支新构建取消旧构建。先创建draft、上传选定JAR、核验集合／大小／SHA、再公开，避免发布半成品。
- 已发布标签与二进制不强制覆盖；重跑已成功发布的同次job只核验。

构建job只读，发布job使用临时GITHUB_TOKEN；固定第三方Actions提交。不会读取本机私密开发目录、存档、Provider Key，也不会调用DeepSeek或启动Minecraft。

标准版为`standard`。手动选择`include_media_tools`时构建`with-media`，包含已有固定版本的可选媒体工具；不要将打包成功理解为所有媒体源／平台都已验证。

CI只证明公开源码的构建与所列测试通过，不代表完整V1。截图与场景边界见[功能清单](FEATURES.md)，源码发布原则见[PUBLISHING.md](PUBLISHING.md)。
