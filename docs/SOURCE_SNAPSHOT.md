# DivZero 开发版源码快照

版本标识：`0.1.0-SNAPSHOT`。这是开发候选源码，不是完整 V1，也不表示已经创建 GitHub Release。

## 支持环境与构建

- Minecraft **26.1.2**、NeoForge **26.1.2.106**、Java **25**。
- 源码沿用技术包名 `dev.mineagent.runtime` 与 Mod ID `mineagent_runtime`，以保持兼容。
- Gradle Wrapper **9.2.1**；Maven/Modrinth 依赖版本与校验值在构建文件中固定。
- 设置 `JAVA_HOME` 为 Java 25，然后在仓库根目录运行 `./gradlew.bat :neoforge:jar`（Windows）；其它平台使用 `./gradlew`。
- Windows 的可选媒体二进制不进入 Git。完整媒体版先通过 PowerShell 7 执行 `./scripts/prepare-media-tools.ps1`，它从固定上游下载并核对 SHA-256；未准备时不应宣称外部媒体功能可用。
- 输出位于 `neoforge/build/libs/`。源码 ZIP 不是可安装的 Mod；游戏内 WebGUI 还需要对应版本的 WebGUI/MCEF 依赖。
- `./gradlew.bat ci` 只构建该快照并运行随快照提供的离线测试，**不是完整 V1 验收**。历史维护/Native smoke 任务可能依赖未公开的测试存档或单独下载的游戏依赖，不作为公共安装入口。

## 当前可用流程

- 原生 `@AI名字` 与 F2 对话；模型可以通过工具读取玩家、物品、世界和已加载方块，再依据真实回执执行操作。
- 当前权限下直接修改已有物品的名称/附魔/组件，给予已注册物品，执行 Minecraft 指令与 GameRule。
- 普通聊天可以生成独立 RuntimePackage 源码、模型与行为，发布后需在 F2 包目录选择、审查并明确启用。通用 HOT 物品载体支持包内彩色几何和真实 item.use/restyle：DeepSeek 生成的钥匙原样产物已验证给予背包、右键修改同一栈模型/名称、数量保持 1、停用拒绝。源码生成与批准后 Native 验收分阶段进行，没有用固定示例替代；载体不是热注册新的 FML Registry ID。
- 本人真实交互距离内的方块使用、原生菜单读取、整叠 QUICK_MOVE 和关闭。真实 DeepSeek 已完成只搬运箱中钻石、保留铁锭、关闭原生菜单并使用拉杆；实际客户端菜单、物品数量和陈旧请求拒绝均已核对。由模型读取/选择槽位，不要求玩家手输内部 ID；精确搬运 N 个及任意 Mod 特殊菜单尚未验收。
- 生成包可提供蓄力物品：服务端计时的 item.release/throwItem 将手中原件转为真实飞行实体，固体 AABB 碰撞反弹后可由原投掷者碰撞拾回；满背包保留，停用取消有效蓄力。真实 DeepSeek 近似球经历几何修正后，短/长蓄力、数量守恒及停止通过 Native；不是篮球入框计分完整玩法，也不覆盖任意 Mod 的 ItemEntity 专用拾取事件。
- AI 可发现并验证建模能力，使用 v2 参数化 sphere/torus 与平滑法线生成高精度几何，原 boxes/自由网格仍保留。真实生成的64×32主球最大三角面内缩约半径0.2405%，原生全分辨率3D预览和投掷回归通过。球面不再依靠堆盒子；物理仍是独立AABB，圆环几何不等于物理空心球框。客户端和服务端应一同更新到本测试包。
- DeepSeek `deepseek-flash` 使用高强度 Thinking，工具续轮保留所需传输信息但不展示到聊天。不注入人工输出 token 上限；Provider 自身边界与应用传输/操作次数保护仍存在。
- 方块/流体延迟观察；真实模型自主建造与修正刷石机后，独立测试连续三次移除产物均由流体重新生成。
- 单人本机所有者可以发现和读取当前实例 `schematics` 目录的 Create 导出 NBT。当前只读结构和材料，不执行或放置 NBT，不读取详细方块实体参数。
- 联网工具提供 MC 百科站内搜索与公开 HTTPS 正文读取，回答需引用来源；不执行网页脚本、不携带游戏登录 Cookie 或模型 API Key。通用 DuckDuckGo HTML 后端在部分网络会返回验证码/202，遇到此情况明确失败，不伪造搜索结果。

## 配置与限制

API Key 只在本机游戏设置中填写，不放入聊天、代码、Issues 或截图。联网功能可由管理员在设置中开关。网页内容、蓝图、物品文字均为数据，不能增加世界/本机权限。

当前仍缺少：聊天修改已发布物品包及所有现存栈迁移、球框/入框计分与篮球完整玩法、Create 及附属 Mod 运行兼容、任意 Mod 方块交互、完整种子地图、本机命令执行适配、记忆 TTL 等。语音识别按当前范围跳过；不将这些项目描述为已交付。

优先在独立测试实例中备份后安装，不同时放入多个同 ID 的 Mod JAR。来源中不包含任何用户存档、API Key、私人聊天记录或测试运行产物。

公开历史与发布方式见 [PUBLISHING.md](PUBLISHING.md)。依赖与资源说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)；`.public-source-manifest.json` 列出本快照公开文件的校验值。
