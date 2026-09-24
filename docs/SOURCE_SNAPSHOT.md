# DivZero 源码与技术说明

[玩家功能](FEATURES.md) · [安装与构建](BUILD_JAR.md) · [发布流程](PUBLISHING.md)

## 运行环境

| 项目 | 配置 |
|---|---|
| Minecraft | 26.1.2 |
| NeoForge | 26.1.2.106 |
| Java | 25 |
| 网络协议 | 6，客户端与服务端同步更新 |
| Mod ID | `mineagent_runtime` |
| 技术包名 | `dev.mineagent.runtime` |
| Gradle Wrapper | 9.2.1 |
| 发布版本 | 提交 UTC 日期＋公开提交序号 |

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

当前公开选定 JVM 测试集合213项。真实 DeepSeek 场景覆盖聊天、工具调用、物品／生物创建、几何与建筑导入、装备／Buff、记忆、外观和本机确认执行等功能。

潜行与搭路的 Native 验证包含1.5格净空、客户端姿态、独立使用／放置／破坏、多格回滚、玩家走桥爬梯、第二 AI 复用台阶、生存材料消耗和取消保留。

待扩展验证包括双真实客户端创建者审批、更多 Mod 交互适配、骨骼与分部件动画、多平台本机命令、满2048³性能及复杂建筑数据引用。事件队列 BACKPRESSURE 状态与测试断言统一列入维护项。

构建命令与产物校验见 [BUILD_JAR.md](BUILD_JAR.md)，真实画面及原图 SHA-256 见 [截图说明](images/README.md)。


## 关于与图标（2026-09-24）

F2“更多→关于”、原生面板“关于”均显示项目名DivZero、项目地址、实际已加载Mod版本、开发者gaoshanliuni，并展示项目图标。提供复制地址与原生链接确认，版本不写死。Mod作者/主页metadata同步更新，Mod ID不变。

同一源PNG现在同时打入根目录logo.png和namespaced资源，metadata指向根目录；新增jar/check最终产物校验（条目、字节一致、1024×1024可解码PNG），公开构建也执行。上一包namespaced PNG原本存在，本轮补根目录通用引用，不改写历史。

0模型Native实测并查看F2、原生关于与NeoForge实际Mod列表截图；四字段/版本与PNG加载通过。原生测试首轮等待列表刷新问题已修正，失败证据保留。本轮不新增模型请求、不覆盖生产存档/配置。
