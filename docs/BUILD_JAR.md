# 版本、安装与构建

[返回首页](../README.md) · [Releases](https://github.com/gaoshanliuni/divzero/releases) · [全部功能](FEATURES.md) · [Build JAR](https://github.com/gaoshanliuni/divzero/actions/workflows/build-jar.yml)

## 安装

支持 **Minecraft 26.1.2 / NeoForge 26.1.2.106 / Java 25**，网络协议 **7**。客户端与服务端请使用同版主模组。

从 Release 的 Assets 选择以下三份文件：

1. `DivZero-mineagent-<版本>.jar`：主模组。
2. `webgui-neoforge-1.6.2+mc26.1.2.jar`：WebGUI。
3. 与系统及游戏所用 Java 架构匹配的一个 MCEF。

| 系统／Java 架构 | MCEF 文件 |
|---|---|
| Windows x64 | `mcef-offline-neoforge-windows_amd64.jar` |
| Linux x64 | `mcef-offline-neoforge-linux_amd64.jar` |
| macOS Intel | `mcef-offline-neoforge-macos_amd64.jar` |
| macOS Apple Silicon | `mcef-offline-neoforge-macos_arm64.jar` |

完整 Release 提供6个运行 JAR，每位玩家安装其中3个。每个实例保留一份主模组、一份 WebGUI 和一个 MCEF 平台包。

关闭游戏，备份存档，将选定 JAR 放入 `mods`。MCEF 平台包内置 Chromium/JCEF 运行库；AI API 和在线网页使用网络连接。本机 Python 支持 Windows x64。

## 初始化与创建 AI

进入已启用作弊／具备管理权限的世界：

```mcfunction
/ai accept
/ai create "星河"
```

API 设置任选以下入口：

| 设置 | 原生面板 | F2 界面 |
|---|---|---|
| 打开设置 | Ctrl+M → 模型 | F2 → 更多 → API 设置 |
| API URL | 选择 DeepSeek／GLM／OpenAI／Ollama 预设，或编辑 URL | 选择地址预设，或编辑 URL |
| API Key | 同页 Key 输入框 → 保存 | 设置 / 替换 API Key → 本机保密输入页 |
| 模型 | 选择模型，或编辑模型名称 | 获取模型列表 → 选择名称；也可选择使用自定义模型 |
| 创建 AI | AI 玩家 → 创建 | AI 管理 → 创建 AI |

DeepSeek 默认 `deepseek-flash`。保存 URL／Key 后可获取 `/v1/models` 列表。Key 仅在游戏设置填写。

原生聊天输入 `@星河 你好`，Tab 可补全 AI 名字；F2 → 对话提供相同聊天能力。`/ai default` 可选择只对本人响应的默认 AI。

## Release 内容与校验

Release Assets 提供运行 JAR，SHA-256 校验值列在正文。GitHub 的 Source code ZIP/tar.gz 用于查看和构建源码。

主 JAR 内含项目许可和第三方说明。WebGUI/MCEF 保留各自许可；MCEF 对应源码链接到固定上游版本。完整构建身份、依赖清单和打包审计保留在 Actions 工件中，保存7天；固定来源入口见仓库文档与上游 Release。

## 版本规则

当前 Mod 版本为 **1.0.2**，以已提交 `gradle.properties` 的 `mod_version` 为准，不再自动覆盖成日期版本。历史 `-SNAPSHOT` 提交仍使用 UTC 日期＋公开提交序号；文件名中的提交号、运行 ID 和 attempt 区分构建身份。

`get-build-version.ps1` 使用完整 Git 提交记录，Actions 采用 `fetch-depth: 0`。构建通过 `-Pmod_version=...` 将版本写入 JAR 文件名、NeoForge 元数据和发布信息。

Windows 本地构建需要 Java 25 和 PowerShell 7：

```powershell
$version = ./scripts/get-build-version.ps1
./gradlew.bat -I scripts/release-build.init.gradle "-Pmod_version=$version" :neoforge:jar --no-configuration-cache
```

输出目录为 `neoforge/build/libs/`。

## Actions 构建与发布

- main 推送：构建、测试和打包成功后生成开发预发布。
- Pull Request：构建与测试。
- Actions → Build JAR → Run workflow：手动构建，main 可发布。
- `standard`：标准版。选择 `include_media_tools` 后构建 `with-media`，包含固定版本的可选媒体工具。
- 发布流程：创建 draft → 上传运行 JAR → 检查文件集合／大小／SHA-256 → 公开 Release。

构建 job 使用只读权限；发布 job 使用临时 GitHub Token。CI 覆盖公开源码构建和选定测试；游戏场景与模型联验范围见 [功能清单](FEATURES.md)。

### 发布命名

当前版本 **1.0.2**，Tag与Release标题均为 `1.0.2`，主模组附件为 `DivZero-mineagent-1.0.2.jar`。依赖JAR保留各自名称。源码提交、变体和运行号保存在构建信息/正文，不再拼入主JAR名或版本Tag。已发布版本Tag不强制移动；同版本不同源码会明确拒绝，后续源码发布需提升版本号。

本轮起不再本地编译，由main推送触发GitHub Actions编译与检查。

## 1.0.1 修复说明

修复进入含长名称原生计分板的世界时崩溃（`invalid scoreboard objective snapshot`），保留计分板名称和分数；读取异常不再逃逸展示 Tick。退出游戏并备份存档后替换旧的主模组JAR，不需要删除存档、数据库或计分板。1.0.0的Tag和附件保持不变。新增长名称及故障隔离单测；用户原世界入图复验尚待完成。

## 1.0.2 增量

增加刚性骨骼/局部动画，修正高低差误潜行；独立AI/规划/文件/包任务无固定并发数限制，取消和结果按请求隔离；F2历史批量读取、缓存和变更通知，流式写库移至后台。同会话及同一本机Python环境仍保持有序。协议7须双端更新；新增场景真实DeepSeek与Native画面联验尚待完成，CI单测不替代实际游戏验收。
