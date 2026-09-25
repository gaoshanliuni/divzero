# DivZero 源码与技术说明

[玩家功能](FEATURES.md) · [安装与构建](BUILD_JAR.md) · [发布流程](PUBLISHING.md)

## 运行环境

| 项目 | 配置 |
|---|---|
| Minecraft | 26.1.2 |
| NeoForge | 26.1.2.106 |
| Java | 25 |
| 网络协议 | 9，客户端与服务端同步更新 |
| Mod ID | `mineagent_runtime` |
| 技术包名 | `dev.mineagent.runtime` |
| Gradle Wrapper | 9.2.1 |
| 当前 Mod 版本 | `1.0.12` |

## 操作入口

直接命令适合快速完成常用操作：

```mcfunction
/ai accept
/ai create "星河"
/ai list
/ai default
/ai thinking see
/ai thinking deep
/ai chat delete
```

原生面板默认 **Ctrl+M**，Web 工作区默认 **F2**。

| 功能 | 原生面板 | F2 |
|---|---|---|
| 创建 AI | AI 玩家 → 创建 | AI 管理 → 创建 AI |
| API URL 与模型 | 模型 | 更多 → API 设置 |
| Key | 模型页同页输入并保存 | API 设置 → 设置 / 替换 API Key → 本机保密输入 |
| 模型列表／自定义名称 | 模型 → 选择模型／名称输入 | API 设置 → 模型列表／使用自定义模型 |
| 对话 | 会话与选择 | 对话 |
| 原生思考显示 | 会话与选择 → 聊天思考 | 对话中的显示开关 |

## 对话与身份

原生 @ 补全和 F2 对话进入流式工具调用流程。模型读取真实状态、执行工具、依据回执回答；长消息分段传输，思考内容单独保存，F2 默认折叠。

默认响应按世界／当前玩家 UUID 绑定指定 AI。创建者可直接使用自己的 AI；访客请求由创建者选择单次或持续允许／拒绝，F2 提供响应策略与名单管理。游戏操作和本机操作分别校验权限。

忙时消息排队，可打断或取消待发送消息。会话删除会终止在途请求与队列，后续使用新上下文。事实／偏好记忆由 AI 自主读写，支持过期时间和同主题更新。

模型配置支持 DeepSeek、GLM、OpenAI 兼容 API 和 Ollama。保存设置后可获取标准 models 目录；每个 AI 可继承全局模型或单独选择模型，API URL 与 Key 共用。真实推理验收覆盖 DeepSeek，GLM 与 Ollama 联验待完成。

## 世界与身体工具

| 能力 | 技术范围 |
|---|---|
| 玩家与物品 | 玩家信息、物品组件／附魔、Buff、背包和已注册物品读取／修改 |
| 原生命令 | 当前玩家实际命令权限，完整输出分页 |
| 方块扫描 | 最大4096³坐标范围、游标读取、已加载区块 |
| 方块几何 | 线、面、墙、壳、拉伸、曲线／曲面、圆柱／圆顶、变换、阵列、材料规则、完整 BlockState |
| 几何计划 | 包围盒每轴2048格含端点，磁盘 spool 与分 Tick 应用 |
| AI 身体 | 寻路、转向、奔跑、潜行、选取／丢物、跟随／停止、传送、真实持物方块动作 |
| 低净空 | STANDING／CROUCHING 尺寸与真实 CollisionShape，提前潜行、安全恢复 |
| 永久搭路 | `build_agent_path` 原生逐块搭桥、台阶、跳跃垫高并附梯子；单格宽、稳定完整方块、已加载区块 |
| 交互规则 | block_place／block_break／block_use 以及 entity_use／entity_attack 独立配置，钥匙、名单、回调与 CAS |
| 持续接管 | 玩家明确提出后启动，HUD 展示行动摘要；Esc 停止，界面／失焦暂停输入 |

搭路保留已建方块，生存消耗 AI 背包材料；垂直需要梯子。放置规则匹配实际落点，多格放置由 NeoForge snapshot 回滚。规则范围为玩家交互事件；世界直接写入与自动化使用各自机制。

## 内容、模型与界面

RuntimePackage 提供动态源码、模型和行为。HOT 物品使用通用热载体，支持 item.use、restyle、蓄力投掷、拾回、碰撞和持久记分。

物品几何包含13种参数化图形及自由网格、平滑法线，物理碰撞使用独立 AABB。生物支持三类阵营与七类交互，动画提供 idle／walk／random／hurt／attack／death／interact／ride 的整体网格关键帧。

F2 窗口独立显示、关闭、悬浮；结构／模型以连续拖动旋转、平移和滚轮缩放。包管理包含物品模型和自有生物预览。实时 WebUI 可订阅坐标、方块统计等本地读数。

PNG 皮肤支持导入、生成、导出和修改，热更新 GameProfile textures 与客户端追踪，保留 AI 身份及进行中的任务。YSM 通过安装目录内的兼容模型切换外观。

## 搜索与文件

联网工具读取搜索结果与公开正文，回答引用实际来源。访问受限时，聊天按钮可打开 F2 文件入口，由用户选择文件后继续。

文件服务绑定 `127.0.0.1:25510`，按世界／Owner 验证。支持文件与文件夹上传、读取、创建和下载；单份上传8GiB、自动直链下载64MiB，面向单人本机使用。

建筑读取支持 Litematica、Create／原版 NBT、Sponge Schem、数字 ID Schematic、Java Anvil 世界选区和 ZIP。导入提供朝向、旋转、镜像，默认保留所支持格式中的方块、BlockEntity／容器、选区实体及延迟 Tick。复杂跨坐标引用和超大 NBT 流式读取列入后续适配。

## 本机运行

Java 管理固定 SHA 校验的 CPython 3.13.15 `python-build-standalone install_only_stripped` 和专用 venv。`python_execute` 与 `python_install_packages` 通过原生聊天查看代码、确认、拒绝或停止，运行范围为 Windows x64 单人本机 Owner 权限。

stdout／stderr 完整保存并分页读取。超时／取消保留实际发生的效果，结果核对后继续。语音识别 ASR 为最低优先级，暂缓实现；TTS 与媒体使用对应配置和可选工具。

## 验证范围

公开 CI 执行选定 JVM 测试集合，具体结果以对应 Actions 记录为准。真实 DeepSeek 场景覆盖聊天、工具调用、物品／生物创建、几何与建筑导入、装备／Buff、记忆、外观和本机确认执行等功能。

潜行与搭路的 Native 验证包含1.5格净空、客户端姿态、独立使用／放置／破坏、多格回滚、玩家走桥爬梯、第二 AI 复用台阶、生存材料消耗和取消保留。

待扩展验证包括双真实客户端创建者审批、更多 Mod 交互适配、骨骼与分部件动画、多平台本机命令、满2048³性能及复杂建筑数据引用。事件队列 BACKPRESSURE 状态与测试断言统一列入维护项。

构建命令与产物校验见 [BUILD_JAR.md](BUILD_JAR.md)，真实画面及原图 SHA-256 见 [截图说明](images/README.md)。


## 关于与图标（2026-09-24）

F2“更多→关于”、原生面板“关于”均显示项目名DivZero、项目地址、实际已加载Mod版本、开发者gaoshanliuni，并展示项目图标。提供复制地址与原生链接确认，版本不写死。Mod作者/主页metadata同步更新，Mod ID不变。

同一源PNG现在同时打入根目录logo.png和namespaced资源，metadata指向根目录；新增jar/check最终产物校验（条目、字节一致、1024×1024可解码PNG），公开构建也执行。上一包namespaced PNG原本存在，本轮补根目录通用引用，不改写历史。

0模型Native实测并查看F2、原生关于与NeoForge实际Mod列表截图；四字段/版本与PNG加载通过。原生测试首轮等待列表刷新问题已修正，失败证据保留。本轮不新增模型请求、不覆盖生产存档/配置。

当前项目版本为 **1.0.4**；构建配置、JAR metadata、关于页与源码manifest使用同一版本。版本号不等于所有历史验收均已完成，本文列出的边界继续有效。


## 中英双语界面

默认跟随Minecraft游戏语言，无需首次手选；中文系列映射简体中文，其它语言回退English。F2“更多→语言”和原生控制中心底部“语言”可恢复跟随游戏，或手动选中文/English。偏好保存在本机，切换重开界面；会话草稿和窗口布局先保存，其它未保存表单请先保存。不会修改服务器、模型、人设或Minecraft自身语言。

固定UI采用本地词典，无运行时翻译请求；AI回复、玩家/AI名字、文件名、模型ID、输入、代码样例、内容包及服务端原始回执保留原文。不同上下文的Creative模式与默认模型标签分别处理。Native实测双向切换/重开、磁盘重载和中文名字保留，Node/JVM相关回归通过；不等于全部历史V1验收已完成。

按最新要求统一由GitHub Actions构建main；当前Mod版本1.0.4，Tag/Release标题使用Mod版本，主JAR文件名为DivZero-mineagent-1.0.4.jar。

## 1.0.1 入图崩溃修复

修复 `invalid scoreboard objective snapshot`：旧观察 DTO 对 objective/team 使用16字符、holder使用40字符限制，与当前 Minecraft 原生字符串不兼容。模组自身生成的 `ma_` 加32位标识即为35字符，也会触发该异常；外层错误曾被误标为数据库无法打开。

- 快照、来源绑定和投影保留原生名称，不截断、不重命名、不删除计分板。观察文本采用现代 UTF 字符串边界，修改权限和命令写入校验保持独立。
- 计分板展示 Tick 遇到 RuntimeException 暂停同步、清除旧的模组展示，并以100 Tick间隔尝试恢复读取；连续故障提示/日志限频。不会自动重放世界修改或模型请求。
- 初始化读取失败时关闭新建数据库连接，并区分数据库打开失败与原生来源读取失败。
- 新增35字符/长名字、持久化重开、原生分数不变、故障隔离/退避/恢复回归，由 Actions 执行 API 与 core 测试。单元测试不等于用户原世界的真实入图复验。

升级前退出游戏并备份存档，仅替换主模组旧JAR；不要删除数据库或计分板。1.0.0 Tag保持不动；1.0.1为独立版本，网络协议仍为6。

## 1.0.2 并发与局部动画

生物新增父子骨骼与局部关节/网格、按骨骼动画轨道，AI通过inspect_creatures/define_creature调用。支持刚性骨骼，暂不支持顶点权重蒙皮。自动潜行仅用于潜行确实能通过的低净空，不因普通高低差触发。原生命令方块修改数上限在服务器启动时设为2147483647。

独立任务采用Virtual Threads，无固定并发/排队数量门槛；同一会话有序、本机Python环境串行且逐请求聊天确认。Worker按requestId路由和取消，不因一条请求超时终止其它AI。规划占用按任务；文件列表与传输分离，上传/ZIP锁按具体任务或文件；包准备按不可变hash复用产物，下载与释放独立。F2变更推送、批量正文读取、revision缓存与局部更新；常规Tick流式增量合并后异步写入。

网络协议7要求双端同步升级。新增Node/JVM及受控HTTP协议回归，编译由Actions执行；本批真实Provider并发取消结果见下节；游戏骨骼画面和复杂地形/多AI联验仍待完成，单测不冒称Native验收。1.0.1修复继续保留。

### 1.0.2 验证补充

[Actions 36018535786](https://github.com/gaoshanliuni/divzero/actions/runs/36018535786) 的构建、选定JVM回归、Node缓存测试和发布通过。使用其实际Worker产物又做了2次官方DeepSeek Flash并发流式请求（high Thinking、不设max_tokens）：取消其中一个，另一个完成且Worker继续响应；骨骼定义JSON包含双腿独立walk轨道。Provider usage未由当前Worker回执透传，记录为不可用。该测试不等于游戏画面或世界动作验收；Native骨骼、复杂高低差和大规模并发仍待联验。

## 1.0.3 F2 输入与滚动

F2接入Minecraft原生文本输入焦点和IME预编辑显示，退出后释放；组合中的Esc/Enter不触发网页动作，中文提交只转发一次。流式消息原位更新、默认跟随末尾；主动翻历史时保持阅读锚点，“最新消息”可恢复跟随。活跃回复不再固定只取前4096字符，思考展开状态保留。协议仍7。

Node回归和实际浏览器DOM/滚动/中文草稿保持检查已通过；浏览器检查不等于游戏窗口的Windows输入法候选词实测，具体IME兼容仍待Native复验。编译发布继续使用Actions，不覆盖既有Tag。

## 1.0.4 原生聊天显示

默认保留1024条，最多16384条，按完整消息计数。`/ai msg limit 16384`调整，`/ai msg`提供点击预设。**不增加可见时间前缀**；悬停`[AI名字]`显示该条接收时的日期和时间，正文、名字颜色、签名和点击按钮保持不变。

`/ai msg mark time`默认完整日期/时分秒，date只显示日期，off关闭。支持日期格式或带文字模板，如`/ai msg mark 消息时间：{yyyy-MM-dd HH:mm:ss}`；这是日期格式模板而非正则匹配。设置只影响本人客户端原生聊天，不修改F2持久历史；降低条数会裁剪旧显示缓存。AI通过inspect_chat_messages/set_chat_messages调用，等待本机保存与显示刷新回执，不以“已发送设置”冒称完成。

1.0.4验证补充：[Actions 36027772930](https://github.com/gaoshanliuni/divzero/actions/runs/36027772930)通过；同一发布JAR在隔离Minecraft中验证默认/最大条数、缩减/恢复、无前缀、名字HoverEvent及原文/签名/按钮保留，并完成limit/mark命令的真实客户端保存和回执。另1次官方DeepSeek Flash high Thinking请求正确选择set_chat_messages及16384/datetime参数；模型工具选择与Native设置往返分别验证，不冒称一次完整端到端模型执行。

## Native acceptance preparation (1.0.5)

Opt-in isolated acceptance now observes actual hierarchical bone render matrices and captures native screenshots. It exercises five terrain cases, 64 concurrent AI bodies, 63 native placements, 16 independent plans for one owner, and eight separate real DeepSeek conversations with one cancellation and one queued follow-up. These are sample sizes, not runtime concurrency limits. At this checkpoint the new Native run is pending; source availability and CI do not establish visual or large-scale acceptance.

Test-only provider auditing can explicitly record independent parallel paid requests. Default unknown-outcome fencing remains enabled. No API keys, runtime databases, screenshots, or player worlds are included in this snapshot.

Native preflight correction (1.0.6): the deterministic rig now uses valid origin-based carrier collision boxes for its downward-facing bone meshes; a bundled-resource parser test guards this fixture. The initial two Native starts failed in test setup (provider order, then fixture collision) with zero model requests. Acceptance remains pending.

Native results: real generated bone visuals, all five terrain cases, 64-agent navigation with isolated cancellation, 16 same-owner plans and eight real-model conversations passed their checks. Concurrent placement exposed a real defect: 63 blocks changed, but 42 change-journal writes failed and were incorrectly reported as rejected. 1.0.7 reserves SQLite CAS write transactions before reading and marks post-mutation journal failures PARTIAL, never safe-to-replay. Focused new-target verification is pending; the failed batch is retained and will not be replayed.

The expanded CI stress test also exposed writer starvation at the tool journal timeout. Short transactions now share a fair per-database write gate; unrelated databases, reads, model calls and world planning remain independent. No AI-task concurrency or queue-count cap is added.

### Native acceptance closure — 1.0.7

| Sample | Result |
|---|---|
| DeepSeek-generated hierarchical bones and native screenshots | Passed (1.0.6 full run; renderer unchanged) |
| Five terrain cases, including normal steps without crouching and low-clearance crouching | Passed |
| 64 simultaneous AI bodies, one cancellation / 63 arrivals | Passed |
| 63 independent placements and durable world-change journals | Passed after fix; 63/63 verified after shutdown |
| 16 same-owner independent geometry plans / 128 placed blocks | Passed |
| Eight overlapping real DeepSeek requests, isolated cancellation and ordered follow-up | Passed |

Actions run `36037269439` succeeded. The focused new-world Native rerun used no credentials and zero model calls; the earlier failed run was preserved, not replayed. The full live batch used 21 official deepseek-flash requests, all high Thinking with no artificial output-token cap: 20 completed and one intentionally cancelled. Available completed usage was 318488 total tokens; the cancelled request's usage is unknown.

Performance remains hardware-bound: the focused 64-body movement sample had median 18.56 ms / p95 37.70 ms per server tick, with a 186.04 ms peak; placement peak was 71.89 ms. This is not a stable-20-TPS or unlimited-scale guarantee. Bone support is rigid hierarchy, not weighted skinning. These results close the specified Native sample, not every outstanding V1 or third-party Mod compatibility case.

## Native chat delivery and interruption correction (1.0.8)

Native chat now reads message metadata, reasoning and body slices as a single synchronized snapshot, instead of racing background stream appends between revision reads. Strict Web revision checks remain. A display failure never retries the model request and no longer masquerades as a provider failure.

Interrupt buttons resolve their exact persisted request rather than depending on a live display subscription. Automatically promoted queued messages retain their request identity: their original button can stop that request without resending it. Old buttons for completed requests never cancel newer requests; pending entries are authenticated before removal. Deterministic concurrent-store tests and an isolated real-provider Native button/stream regression are added; validation is pending at this source checkpoint.

## In-place native streaming (1.0.9 / protocol 8)

Native replies now update one unsigned ChatComponent history entry per request; transport chunk boundaries do not add newlines or new messages. Both normal text and Provider-returned thinking stream through request-scoped packets. Thinking defaults to one live tail row above the answer; full thinking remains in F2 and in the local entry, with `/ai msg thinking` offering tail/full buttons. The AI can inspect/set the same client-acknowledged `thinking` preference via inspect_chat_messages / set_chat_messages. Name-hover timestamps, colors, interruption and history retention are preserved. Updating one entry reflows only its wrapped lines and retains the scroll anchor.

The historical 24000-character tool-result rejection is removed. Full results can pass the existing 4 MiB tool transport envelope; mutation receipt storage also accepts those results and still exposes complete paged reads without replay. Provider context limits and transport safety boundaries remain; no output max_tokens or cumulative tool-round limit is introduced. Both client and server must update to protocol 8. Native real-provider verification is pending at this checkpoint.

The 1.0.9 no-thinking real-provider run passed: 20 observed in-place updates, one native history entry, full text preserved, stable receipt time and scroll. The initial thinking/settings run completed its real model response and applied full thinking, but its test fixture produced a tool result below the intended 24k boundary, so that combined acceptance was marked failed rather than passed. The 1.0.10 fixture reads 16 actual inventory stacks and verifies a random marker at the end of the large result. No production data or failed paid requests are replayed.

### In-place streaming verification completed

1.0.10 Actions run `36091081945` passed. The real high-thinking Native test observed 41 updates to one GuiMessage, with all 850 body / 1655 reasoning UTF-16 units matching storage. Tail mode added exactly one reasoning row (34 total wrapped rows vs 86 in full mode). The unchanged no-thinking implementation passed separately with 20 updates, one entry and 773 body units / zero reasoning. Receipt timestamps and scroll positions stayed stable.

The AI consumed an actual 32128-character inventory tool result and returned its random tail marker, then applied `thinking=full` through the client-acknowledged tool. Active and promoted-queue interrupt buttons were checked against real requests; old buttons did not cancel newer requests or resend a queued message. The failed undersized fixture run was retained. Native screenshots are viewport observations, not proof that a whole long reply fits on screen; full data equality and native layout were verified separately. These are functional samples, not an unlimited-context or all-provider guarantee.

## Entity interoperability preparation (1.0.11 / protocol 9)

New AI tools inspect loaded vanilla/Mod entities, attributes, Goal/Brain observations, multipart identity, and actual client ModelPart draw poses. Persistent rules can block or modify interaction, attacks, incoming damage, knockback, targeting, base movement and selected goals; declared callbacks can add effects, sounds, messages or owner-authorized Minecraft commands. Animation overlays support whole-model and local-part tracks and restoration without leaking changes across shared model instances.

The requested third-party target is Twilight Forest Naga. Its official Maven build 4.9.3722 declares Minecraft 26.1.2 compatibility; only an isolated test instance will load it. The optional adapter intercepts Naga's attack before shield effects and multipart contact before pushing/damage, and exposes its public daze/circle/crumble state transitions. Native tests and real DeepSeek verification are pending at this checkpoint.

Derived hot creatures can reference the installed source living renderer/textures and mapped attributes/food traits. This does not copy private Java AI, boss progression, loot tables or multipart hitboxes. A Naga reference borrows its head renderer, not a complete multipart boss clone. Proprietary renderers outside the Model/ModelPart pipeline report unavailable rather than a fabricated success. Real players and AI-player bodies remain on their existing owner-scoped control/skin APIs.

Native preflight found Twilight Forest login synchronization sending custom payloads to the AI body's deliberately clientless connection. 1.0.12 drops these packets at that synthetic listener before client-channel validation, matching its existing packet sink. Real observer connections and terminal packet lifecycle are unchanged. The official Maven build also requires its declared Beanification 1.9.132 game library. No paid request had been started in either failed preflight.
