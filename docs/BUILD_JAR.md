# 1.0.20：安装、升级与构建

[返回首页](../README.md) · [Release 附件](https://github.com/gaoshanliuni/divzero/releases/tag/1.0.20) · [功能说明](FEATURES.md)

支持 **Minecraft 26.1.2 / NeoForge 26.1.2.106 / Java 25**，网络协议 **10**。客户端和服务端使用同版主模组。

## 下载哪些附件

| Release Assets 文件 | 安装范围 |
|---|---|
| `DivZero-mineagent-1.0.20.jar` | 必需，DivZero 主模组 |
| `ldlib2-neoforge-26.1-26.1.2.41.jar` | 必需，F2、AI 专属面板、原生预览与 HUD |
| `kubejs-neoforge-26.1.2-8.0.6.jar` | AI 创建、修改动态原生界面及交互 |
| `better-advanced-tooltips-2601.1.0-build.9.jar` | 上述 KubeJS 版本的依赖 |

**内置 F2 只需前两个 JAR；完整 AI 动态界面安装四个。** Rhino 已嵌入主模组。附件保留原文件名，直接下载各个 JAR；GitHub 自动生成的 Source code 压缩包是源码。

## 从旧版升级

1. 关闭游戏，备份现有实例和存档。
2. 移除该实例旧版 DivZero，以及为旧 DivZero 安装的 WebGUI、MCEF / MCEF-Offline 平台包。若其它模组仍依赖它们，使用独立实例进行迁移。
3. 将需要的上表 JAR 放入 `mods`，同一 Mod 只保留一份版本；服务端与客户端同步更新。
4. 进入世界，在聊天中点击 **启用 / 禁用**。启用立即生效，无需输入 AI ACCEPT 或退出重进。
5. F2 → **设置 → Provider** 配置 API URL、保密 Key 和模型；F2 → **AI 玩家** 创建 AI，或使用 `/ai create "星河"`。原生聊天 `@星河 你好` 或 F2 对话即可使用。

新版本不打包或发行 MCEF、JCEF、WebGUI JAR，不包含浏览器核心下载、离线包或修复流程。不要删除存档或数据库来完成升级。旧 HTML/CSS/DOM 内容需要显式重写为 LDLib2 原生定义；无关资源和玩法代码应保留。

## 当前入口与操作

F2 / Ctrl+M 共用 MC 主题工作区；右键 AI 打开专属状态、人设、会话、背包与内容面板。确认、填入、复制选项在原生聊天和 F2 均可点击。

托管开始释放鼠标；T、F2、切换窗口和最小化继续执行。面板提供暂停/继续、退出和追加命令，ESC 结束托管；退出恢复原失焦暂停设置。具体技能和恢复边界见 [持续玩家技能](PERSISTENT_PLAYER_SKILLS.md)。

## 校验、许可与构建

Release 正文列出四个独立运行附件的 SHA-256、用途、源码提交和 Actions 记录。依赖的固定下载、哈希、许可及对应源码见 [第三方声明](THIRD_PARTY_NOTICES.md) 和 [固定依赖清单](../scripts/stage-native-ui-dependencies.ps1)。Actions 工件还含 BUILD-INFO、DEPENDENCIES 和 SHA256SUMS，保留 7 天；Release 的 JAR 附件不依赖临时 Actions 下载链接。

- main 推送与 Pull Request：只构建、测试并保存工件。
- 手动 Actions → **Build JAR → Run workflow**：`build_only=true` 仅构建；`false` 按所选 main 提交发布开发预发布。
- `standard` 为标准构建；`include_media_tools` 可加入固定版本的可选 Windows 媒体工具，来源与许可另行列明。
- 发布先创建 draft，上传并核对四个运行 JAR，公开后再次验证下载。
- Tag、Release 标题和主 JAR 版本均来自 `gradle.properties`。不移动旧 Tag，不用新源码覆盖已发布的旧版本。

本项目维护流程由公开 GitHub Actions 编译。CI 不代替游戏或真实模型验收；[升级状态](NATIVE_UI_MIGRATION_STATUS.md)、[十项场景](NATIVE_TEN_SCENARIOS.md)、[持续技能](PERSISTENT_PLAYER_SKILLS.md) 分别记录已测范围。旧版本变更见对应历史 Release。

## English quick install

Download the main JAR and LDLib2 from **Release Assets**. Add KubeJS and Better Advanced Tooltips for AI-created interfaces; Rhino is embedded. Close the game, back up the instance, remove the old DivZero browser dependencies and replace the main JAR. Keep clients and servers on the same version.

Click **Enable** in chat on first entry. Open F2 → Settings → Provider for API settings. F2 and Ctrl+M share the MC-themed workspace; right-click an AI for its panel. During player takeover, the cursor stays free and chat, F2 and window switching do not pause work. Use Pause/Continue, Exit or Add command on the panel; ESC ends takeover.

HTML/CSS/DOM packages need an explicit native rewrite. This release provides four individual runtime JAR attachments and no MCEF/WebGUI package.