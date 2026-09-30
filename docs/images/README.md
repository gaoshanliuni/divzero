# 当前原生界面实机截图

[首页](../../README.md) · [功能说明](../FEATURES.md)

以下为已验收隔离 Minecraft 实例的原始 PNG，按原字节发布，未用生成图、合成图或旧浏览器画面冒充新界面。已检查画面无 API Key、私人对话或路径，PNG 不含文本/EXIF 元数据。图片展示对应场景，功能边界见相关验收文档。

| 图片 | 场景 | 原图 SHA-256 |
|---|---|---|
| [native-workspace.png](native-workspace.png) | LDLib2 MC 主题 F2 工作区；空会话布局 | `904e5b3509d927e5466b91b5edbbb258c904f854f8ca706a1ba37dd20ae74139` |
| [native-agent-profile.png](native-agent-profile.png) | 右键 AI 的专属状态、人设、会话、内容与背包面板 | `e1938ac01828dccd032f7b588405eea6a5d06e1d3ea7aaf7aef71d8af3a839d9` |
| [native-preview.png](native-preview.png) | LDLib2 原生几何预览测试；旋转、平移与缩放入口 | `058360c4bcef3896702d88b4c10c464137c04946df123b5e7e22f191d1ddc44b` |
| [native-shop.png](native-shop.png) | 真实模型创建并修改后的原生商店；保留“橡木”搜索；按钮仅扣本地演示余额 | `54c146df8c3ca540db8a33432079cf0c0f7063c43c90b2d30f242f70e37d2d8a` |
| [native-furnace.png](native-furnace.png) | 真实模型创建的原版熔炉计时与进度附加层 | `97a2eda6d5fb5f2258c5028517eaa0b57b2f6464bd735fa4d0e7fa1fe73e8c31` |
| [player-takeover.png](player-takeover.png) | 1.0.22 真人农务托管与原生正面第三人称；MC 面板暂停、退出、追加命令、视角切换 | `c6e0d0a19529d34fa4ab69f07d280c480a1c4ba41b4b89ff174e94e9d3853fc7` |

工作区、专属面板与预览来自原生 UI 联验；商店来自真实模型定义的布局修正复验；熔炉来自真实模型创建；托管来自玩家原生输入与后台执行联验。商店示例不发放物品，预览图展示几何测试体，不代表所有模型或 Renderer。

## 历史世界画面与旧 UI 留档

以下原图及校验信息保留其历史含义。`native-model-settings.png`、`preview.png`、`live-readout.png` 为旧界面记录，已从当前功能插图中替换，不能代表 1.0.20 的操作入口。其余世界建造、生物和多 AI 图片仍展示相应游戏内容。

### 历史截图目录

[全部功能与操作入口](../FEATURES.md)

截图均由 Minecraft 实际运行画面直接保存，保留原图。已检查画面与 PNG 元数据中的凭据和个人信息。潜行与道路画面在隔离测试世界采集，原生动作回执与客户端姿态记录一同核对。

| 图片 | 展示内容 | 原图 SHA-256 |
|---|---|---|
| [basketball.png](basketball.png) | 篮球、球架与实际入框计分 | `c03b9f27720475b1fdc0aff3a71d20304be521754451f51dc39d199084f46023` |
| [creature.png](creature.png) | 自定义星球伙伴与生物交互 | `d66ac919fe2137d852bb3b2363d9d5004cdcbaccc6f87a2b10aac27f590123fe` |
| [imported-house.png](imported-house.png) | 上传投影并导入177方块房屋 | `963b225934a4f998861a594f33d271ca42a5fc6141a25a479c11ae3360e680c9` |
| [runtime-item.png](runtime-item.png) | 新钥匙发放与右键修改同一物品 | `6057e30d3ca9bb52b105963160e719268ece11e67acb7703b61e45054a192d29` |
| [live-readout.png](live-readout.png) | 原生 WebUI 实时坐标与方块统计 | `0d9c65d7d7ef187050471a72771d59d5d68fb2b8e46593d09549f3f20c701032` |
| [preview.png](preview.png) | 包管理与模型自由旋转、缩放预览 | `04356700e10ac369bb5626abf31946063eed11b2ffd6474a6e95e7aff2a15e6b` |
| [native-model-settings.png](native-model-settings.png) | Ctrl+M 原生模型页：URL、模型、Key 同页 | `cadfc927ff188bf506531bdd79a27e88ba27043a0e138afe586154cbe4dfcf6a` |
| [crouch-navigation.png](crouch-navigation.png) | AI 通过1.5格净空时的实际潜行姿态 | `45f62ed1dcc38f2feff08fe8bbad0fb9e07f0fd10e1de6886d28a92dd9836486` |
| [reusable-paths.png](reusable-paths.png) | AI 原生搭建后保留的桥、台阶与附梯子高柱 | `e05e9c8906d19a046659006ecb9baa60d27489d8ea77f3dfe79c1a777754eb72` |

房屋图片展示方块放置结果，源文件中的红色标记随建筑保留。内容保留功能另有容器、命名实体与延迟 Tick 验证。

原生模型页的 Key 输入框为空，保存后的 Key 以保密方式管理。模型设置同时支持 Ctrl+M 与 F2，详细路径见功能说明的“常用操作入口”。

潜行、道路与梯子通过本地 Native 场景重现后拍摄；对话调用能力另有真实 DeepSeek 联验记录。截图和验证范围对应各自列出的场景。

## 多 AI 并行与地形测试

以下两张图片由用户提供，按原始 PNG 字节发布；已核对画面无 Key，PNG 仅含 IHDR / IDAT / IEND，不含文本或 EXIF 元数据。

| 图片 | 对应能力 | 原图 SHA-256 |
|---|---|---|
| [multi-ai-parallel.png](multi-ai-parallel.png) | 多 AI 同场并行行动 | `83c1cfab4881a2928011673aa391b0b207d48ab6403e84aa91debe001636c11c` |
| [multi-ai-terrain.png](multi-ai-terrain.png) | 独立寻路、复杂地形和低净空潜行 | `8c93e962ca496a2df86c050ac76b6aa3587b0ef8fa524e6fd271a996dbd101aa` |

画面来自1.0.6 Native场景；1.0.7并发持久化修复另有新世界复验。实体数不等于同时运行的模型请求数，完整范围见[多 AI 并行说明](../MULTI_AI_PARALLEL.md)。

## 1.0.23 界面修复

这两张为本轮较早的回归截图；最新尺寸入口和布局见下方“包管理与紧凑界面补齐”。

| 图片 | 实际场景 | SHA-256 |
|---|---|---|
| [native-agent-inventory.png](native-agent-inventory.png) | 原生背包尺寸 4 实机验收：命名头盔装备、数字键交换、右键拆分后拖拽分配物品 | `d9f125003e5790e6dece00f671734c1849818b8071b6d23af180bb514051d7b4` |
| [native-workspace-scale-four.png](native-workspace-scale-four.png) | F2 尺寸 4 实机验收：紧凑布局、保留草稿、实际选择已归档筛选 | `5b73da35e6b0bcd4ba08a22e0951ef6e1796cb79bc39aaa3405c510b101b2300` |

## 包管理与紧凑界面补齐

以下为隔离验收世界的真实截图，未编辑画面内容。

| 图片 | 实际场景 | SHA-256 |
|---|---|---|
| [native-settings-scale-four.png](native-settings-scale-four.png) | 尺寸 4 设置页：独立滚动区、固定保存栏及二级尺寸入口 | `b1db794bf722e0225a33fd4f481ff2f4d7f6cb59706f1061f18e3ebac99a6eba` |
| [native-recovery-report.png](native-recovery-report.png) | 图形化恢复报告：状态、坐标和方块图标对比，无原始 JSON | `c6d07df70eff4ce1ea58bd06d50ebbfaa4e1799bc2987533cacbe3718ca45640` |
| [native-selector-layout.png](native-selector-layout.png) | 创建页下拉选择框与相邻按钮分开布局，标识垂直居中 | `fd0d40507046a16228da7c58cb02c42504044d736c58dddc12296efafd1756bb` |
| [native-content-catalog.png](native-content-catalog.png) | 已打开包管理后创建内容，目录自动出现物品、生物和原版修改 | `65c7879c8b5198522e299f175e94c831c9180124dfaee7db2187cf099d143385` |
