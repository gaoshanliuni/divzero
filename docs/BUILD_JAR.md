# 自动编译与下载 JAR

[打开 Build JAR 工作流](https://github.com/gaoshanliuni/divzero/actions/workflows/build-jar.yml)

## 自动触发

- 向公开仓库 `main` 推送提交后自动构建。
- 向 `main` 提交 Pull Request 时构建测试候选；这不是已审核 Release。
- 在 Actions → Build JAR → Run workflow 可手动构建。
- 同一分支的新任务会取消仍在执行的旧构建。

流水线使用 Windows 2025、Temurin Java 25、项目 Gradle Wrapper；验证 Wrapper 和已有依赖校验值，编译 NeoForge JAR、检查打包解析器，并运行公开快照包含的 core/worker/client 测试。不会启动 Minecraft，不请求 DeepSeek，不需要 API Key。

## 下载

1. 登录 GitHub，进入上述工作流，选择成功的运行。
2. 点击运行摘要中的下载链接，或页面底部 **Artifacts → divzero-standard-提交号**。
3. 解压得到可安装 `.jar`、`SHA256SUMS`、`BUILD-INFO.json` 和简短安装说明。

产物保留 **7 天**，过期后可手动重新构建。文件名包含构建类型和源码提交号；源码提交与SHA-256也记录在构建信息中。只上传主 Mod JAR，不混入 sources/javadoc JAR。失败时仅提供编译/测试诊断，不发布半成品 JAR。

## 可选多媒体依赖

自动构建默认 `standard`，与普通源码构建一致，不包含可选 yt-dlp/FFmpeg 二进制。
手动运行时勾选 `include_media_tools`，会调用仓库已有脚本下载固定版本、验证 SHA-256，再生成 `with-media` 产物。该选项需要额外网络下载，JAR更大；不修改公开源码或提交下载文件。

## 与源码同步、正式发布的区别

本流程只编译**已提交到公开仓库**的源码，不读取或自动同步私密开发仓库，不包含 API Key/本机配置/存档。不自动创建 Tag、Release，也不向已有 Release 上传文件；正式发布仍遵循 [PUBLISHING.md](PUBLISHING.md)。工作流只有仓库只读权限，第三方 Actions 固定到已核验 commit。

构建通过只证明编译、依赖/包结构和所列测试通过，不等于完整 V1、真实模型或游戏内功能全部验收。安装环境与功能边界见 [SOURCE_SNAPSHOT.md](SOURCE_SNAPSHOT.md)。
