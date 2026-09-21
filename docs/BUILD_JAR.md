# 自动编译与下载 JAR

[Releases 下载](https://github.com/gaoshanliuni/divzero/releases) · [Build JAR 工作流](https://github.com/gaoshanliuni/divzero/actions/workflows/build-jar.yml)

## 自动触发与发布

- 向公开仓库 `main` 推送提交：自动编译、测试，成功后发布独立的 **开发预发布（Pre-release）**。
- 向 `main` 提交 Pull Request：只构建测试，不创建 Release。
- Actions → Build JAR → Run workflow：可手动构建；只有本仓库 `main` 的构建允许发布。
- 同一分支的新任务会取消旧任务；取消或失败的构建不公开半成品附件。

流水线使用 Windows 2025、Temurin Java 25、项目 Gradle Wrapper，验证 Wrapper 和已有依赖校验值，编译 NeoForge JAR、检查打包解析器，运行公开快照包含的 core/worker/client 测试。不会启动 Minecraft，不请求 DeepSeek，不需要 API Key。

## 分文件下载，无需解压整合包

在 Releases 选择对应开发版本，分别下载三个运行时 JAR：

1. `mineagent_runtime-<版本>-<构建类型>-<提交号>.jar`：DivZero 主模组。
2. `webgui-neoforge-1.6.2+mc26.1.2.jar`：WebGUI。
3. `mcef_neoforge_2.2.0_MC_26.1.1.jar`：MCEF，上游原文件名保持不变。

客户端将这三个 JAR 放进 `mods`；先关闭游戏，并移除同 Mod 的旧版，建议使用已备份的测试实例。MCEF 文件名虽然包含 MC26.1.1，其上游元数据允许后续版本，当前项目锁定这个工件用于 Minecraft 26.1.2；不因此宣称本 Mod 支持其它游戏版本。

**浏览器依赖不会由 DivZero 自动安装**：CI 下载并分别发布 WebGUI/MCEF JAR，玩家仍需下载放入 `mods`。Chromium/JCEF 原生库由 MCEF 自身的初始化/下载机制准备，不包含在这些 Release 附件中。

其它附件：
- `SHA256SUMS`：所有附件（清单自身除外）的 SHA-256。
- `BUILD-INFO.json`：Mod/Minecraft/NeoForge/Java 版本、源码提交、构建类型、工作流身份及主 JAR 校验值。
- `DEPENDENCIES.json`、`LICENSE-*.txt`、`THIRD-PARTY-NOTICES.md`、`README.txt`：来源、许可证及安装说明。
- `mcef-2.2.0-neoforge-sources.jar`：MCEF 对应开发源码，**不要放进 mods**。

不上传整合 ZIP。GitHub 自动显示的 **Source code (zip/tar.gz)** 是源码下载，不是 Mod 安装包。Actions artifact 只用于两个 job 间传递文件，保留 7 天；用户下载入口是 Releases，不受这个 7 天暂存期影响。

## 版本标注与可追溯性

Release 标题、正文和构建信息自动读取本次公开源码的 `gradle.properties` 与 Java toolchain，标注当前 Mod 版本、Minecraft 版本与声明范围、NeoForge 构建版本、Java 版本。打包时核对实际 JAR 元数据，版本不一致则拒绝发布，不把开发快照冒称正式 V1。

当前配置为 **Mod 0.1.0-SNAPSHOT / Minecraft 26.1.2 / NeoForge 26.1.2.106 / Java 25**；未来以对应 Release 自动生成的版本信息为准。

每次构建使用独立标签 `ci-v<Mod版本>-mc<游戏版本>-<提交号>-<类型>-r<运行ID>-a<构建次数>`。同一 Mod 版本的开发提交用源码 SHA 区分，不移动既有标签、不覆盖正式 Release。重跑已成功发布的同次发布 job 仅核验，不改附件。

发布顺序为：创建本构建专属 draft → 逐文件上传 → 核对附件集合/大小/服务器提供的 digest → 公开为 Pre-release。中途失败保留 draft，不让用户下载不完整发布；重新运行失败的发布 job 可恢复。发布 job 仅持有本仓库的临时 `GITHUB_TOKEN`，构建 job 为只读权限；第三方 Actions 固定到 commit。

## 可选多媒体版与边界

自动构建默认 `standard`，不包含可选 yt-dlp/FFmpeg。手动勾选 `include_media_tools` 时，使用已有脚本下载固定版本并验证 SHA-256，产出较大的 `with-media` JAR；同样按文件发布。

只编译**已经公开提交**的源码，不读取、自动同步私密开发仓库或发布本机配置/存档。正式稳定版仍由维护者按 [PUBLISHING.md](PUBLISHING.md) 审核发布；自动开发预发布不会抢占稳定版 Latest。

CI 成功不等于完整 V1、真实模型或游戏内全部验收；支持范围见 [SOURCE_SNAPSHOT.md](SOURCE_SNAPSHOT.md)。
