# 功能截图

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
