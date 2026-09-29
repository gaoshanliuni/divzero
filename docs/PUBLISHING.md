# DivZero 发布流程

[安装](BUILD_JAR.md) · [功能](FEATURES.md) · [技术说明](SOURCE_SNAPSHOT.md)

公开仓库使用独立 Git 历史。开发目录先提交，经清单、凭据和资源检查后导出干净源码快照，在公开 main 上提交和推送。已公开文档的更新必须经过审查，不能被旧快照覆盖。

## 每次发布

1. 更新源码版本号和 `docs/releases/<版本>.md`，明确新增功能、升级方式与已测边界。
2. 同步中英文 README、安装指南、功能说明、第三方来源和截图。截图采用实际游戏原图，记录场景与 SHA-256。
3. 审查不包含 API Key、用户配置、存档、数据库、日志、构建缓存或私人历史；截图检查画面与元数据。
4. main 推送运行云端构建与测试，生成可审查工件；不会自动发布 Release。
5. 确认发布范围后手动运行 Build JAR：`build_only=false`，选择所需变体。
6. 发布 job 校验源码/版本/运行身份及全部打包文件，从 draft 上传四个运行 JAR 后核对 hash、大小与附件集合，再公开开发预发布并检查下载。

## 附件契约

| 附件 | 用途 |
|---|---|
| `DivZero-mineagent-<版本>.jar` | 主模组 |
| `ldlib2-neoforge-26.1-26.1.2.41.jar` | 必需的原生 UI/HUD |
| `kubejs-neoforge-26.1.2-8.0.6.jar` | AI 动态界面 |
| `better-advanced-tooltips-2601.1.0-build.9.jar` | KubeJS 依赖 |

新 Release 只发行这四个独立运行 JAR，不打包 MCEF / WebGUI，不上传平台浏览器包或整合 ZIP。全部 staging 文件仍经 SHA256SUMS 校验，BUILD-INFO、DEPENDENCIES 与许可副本保留在 Actions 工件中。Release 正文直接列用途、安装步骤、哈希、固定源码与许可链接，不把临时 Actions 工件作为长期附件替代品。

内置 F2 不要求 KubeJS；完整 AI 动态界面需要四个文件。Rhino 已内嵌。更新依赖时同步编译锁、打包脚本、文档和发布测试，不能仅改下载链接。

## 版本与权限

Tag 和 Release 标题等于已提交 Mod 版本，主附件为 `DivZero-mineagent-<版本>.jar`。已发布 Tag/附件不覆盖；不同源码须提升版本。旧版 Release 保留当时的依赖，不将其标为新原生版安装说明。

构建 job 只读，发布 job 用同次 Actions 的临时 GITHUB_TOKEN。PR 与普通推送不能发布。发布脚本拒绝源码、版本、运行身份和校验值不符，以及浏览器附件混入的情况。失败 draft 可以在同次来源下核对恢复，已发布产物只检查、不静默替换。