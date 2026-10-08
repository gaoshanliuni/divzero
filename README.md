[简体中文](#chinese) · [English](#english)

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

<a id="chinese"></a>

# 除零 · DivZero AI Runtime

> **世界未定义，创造不设限。**

**把大模型变成真正存在于 Minecraft 世界里的玩家、建造者与运行时创作者。**

DivZero AI Runtime 不是一个只会在聊天框回答问题的 AI Mod。它让 AI 拥有世界感知、身体、背包、行动能力、长期记忆和动态创造能力，可以理解你的自然语言目标，观察当前世界，然后实际移动、建造、战斗、操作物品、创建内容并持续完成任务。

你可以把它当作 AI 队友、自动化玩家、世界编辑助手，也可以让它现场创造新的物品、生物、交互规则和原生界面，把一句自然语言需求逐步变成真正存在于游戏中的玩法。

**[下载](https://github.com/gaoshanliuni/divzero/releases) · [全部功能](docs/FEATURES.md) · [安装说明](docs/BUILD_JAR.md) · [问题反馈](https://github.com/gaoshanliuni/divzero/issues/new/choose)**

> 具体 Minecraft、Mod Loader、Java 与依赖版本要求会随发布包更新，请以 [Releases](https://github.com/gaoshanliuni/divzero/releases) 和 [安装说明](docs/BUILD_JAR.md) 为准。README 只介绍长期稳定的功能与使用方式。

## 核心能力

| 能力 | DivZero 可以做什么 |
|---|---|
| **AI 玩家** | 创建拥有独立身份、身体、血量、背包、人设与会话的 AI，并让它在世界中真实移动和行动。 |
| **自主执行** | 跟随、巡逻、警戒、漫步、战斗、农务、钓鱼、寻路、搭桥、垫高、使用方块与物品，并在中断后核对状态继续任务。 |
| **世界理解** | 读取玩家、实体、方块、容器、装备、状态效果、命令与可发现的 Mod 内容，根据当前环境规划下一步。 |
| **建筑与导入** | 用几何规划建造道路、墙体、曲面和结构，也可导入蓝图、投影、Schematic 或世界选区并调整方向、旋转和镜像。 |
| **动态创造** | 通过自然语言创建物品、生物、行为、交互规则和运行时内容包，让新玩法直接进入世界。 |
| **原生动态界面** | 内置 F2 原生工作区；AI 还可以创建和持续修改原生界面与 HUD，而不是依赖网页 GUI。 |
| **多 AI 协作** | 多个 AI 可以同时处理各自的对话、移动和任务，并保持会话与取消操作相互隔离。 |
| **长期记忆** | AI 可以保存事实与偏好，在后续会话中继续使用；玩家可管理人设、会话、记忆和响应权限。 |
| **真人托管** | 玩家明确要求后，AI 可以接管真人角色执行任务；你可以观察、暂停、追加命令或随时退出。 |
| **脚本与扩展** | 使用 JavaScript、运行时内容包以及受确认控制的本机 Python 任务扩展能力，并与已安装 Mod 的可发现内容配合。 |

## 一个真正会行动的 AI 玩家

AI 不只是生成操作建议，而是可以亲自完成操作。它会根据地形和身体状态移动，跨越高低差、楼梯、半砖、门和低净空区域；需要时潜行、跳跃、搭路或报告无法通过，而不是直接穿墙。

它可以拿出真实物品、操作背包和装备、放置或破坏方块、使用容器与交互对象，并把工作、战斗方式和交战规则分开。比如你可以直接说：

> “继续种田，遇到怪就边打边拉开距离，结束后接着种。”

常规行动由本地执行循环持续完成，模型负责理解目标、处理变化和做高层决策。

![多个 AI 玩家在同一世界并行行动](docs/images/multi-ai-parallel.png)

## 让 AI 创造玩法，而不仅是执行命令

DivZero 的目标不是预先写死一组指令，而是让 AI 在运行时组合和创造内容。

你可以要求它做一个篮球和篮球架，实现蓄力投掷与进球计分；也可以创建友好、中立或敌对的新生物，为它加入交易、跟随、骑乘、繁殖、巡逻和战斗行为。

![篮球架与实际计分](docs/images/basketball.png)

![自定义星球伙伴](docs/images/creature.png)

运行时内容可以继续被修改，而不是每次都重新做一份。物品、生物和世界交互修改会进入统一的内容管理入口，便于后续查看和管理。

## 原生工作区与 AI 动态界面

DivZero 使用 LDLib2 构建内置原生工作区，F2 与 Ctrl+M 可以进入同一套界面。AI 专属面板、对话、设置、内容管理、文件、记忆和诊断等功能都在游戏内完成。

![LDLib2 MC 主题的 F2 工作区](docs/images/native-workspace.png)

AI 还可以通过 KubeJS 创建原生动态界面。你可以先让它“做一个商店”，再继续说“增加搜索框”“换一种布局”“改成深色”，它会修改同一个已经存在的界面，并尽量保留已有输入和状态。

![AI 动态原生商店](docs/images/native-shop.png)

HUD 与普通界面遵循不同交互方式，被动 HUD 不会默认抢占游戏输入。内置功能不依赖 MCEF / WebGUI。

## 建造、道路与结构导入

AI 可以直接在世界里放置真实方块并沿自己建好的道路继续移动。搭桥、连续台阶、跳跃垫高和带梯子的垂直路线都可以成为世界的一部分，玩家和其他 AI 也能继续使用。

![永久道路、台阶与带梯子的高柱](docs/images/reusable-paths.png)

除了现场建造，你也可以从 F2 → 文件或对话附件把建筑交给 AI。它可以处理结构蓝图、投影、Schematic 和 Java 世界选区，并提供预览、朝向、旋转、镜像与内容保留选项。

![导入建筑实景](docs/images/imported-house.png)

## 长期记忆、多人权限与多 AI

每个 AI 都可以拥有独立的人设、会话和记忆。事实与偏好能够跨会话继续使用，例如记住地点、建筑偏好或你希望它采用的交流方式。

你创建的 AI 默认服务于你本人。其他玩家尝试调用时，可以按一次允许、长期允许、一次拒绝、长期拒绝或允许名单进行控制。涉及世界修改、命令、本机任务等能力时，仍遵循对应的 Minecraft 权限和 DivZero 授权流程。

多个 AI 可以同时行动：它们的对话、移动、计划和取消操作彼此隔离，同时共享同一个真实 Minecraft 世界。

## 真人托管

如果你明确要求，AI 可以直接控制你的玩家角色执行任务。托管模式使用原生客户端输入，你仍然可以观察当前过程、切换视角、暂停、继续、追加命令或退出。

![真人托管控制面板](docs/images/player-takeover.png)

托管适合需要使用真人身份、现有装备或客户端交互的任务。死亡、断线、世界变化或权限变化等情况会释放控制。

## 模型决定上限，DivZero 负责把能力接进世界

复杂建造、实体设计、多步骤规划和工具调用会明显受到所选模型能力影响。DivZero 提供 Provider、模型选择、工具调用、世界上下文和执行层；你可以按自己的服务选择兼容模型。

更强的模型通常更适合长任务和复杂创造，但任何模型都可能犯错。DivZero 会尽可能通过权限、状态核对、失败恢复和实际执行结果，让模型基于真实世界状态继续工作。

## 快速开始

1. 从 [Releases](https://github.com/gaoshanliuni/divzero/releases) 下载当前发布包，并按 [安装说明](docs/BUILD_JAR.md) 安装所需依赖。
2. 首次进入世界时在聊天中选择 **启用**，无需退出重进。
3. 打开 **F2 → 设置 → Provider**，配置 API URL、Key 和模型。
4. 输入 **`/ai create "星河"`** 创建 AI，也可以从 F2 → AI 玩家创建。
5. 在原生聊天输入 `@星河 你好` 开始对话；`@` 后可按 Tab 补全 AI 名称。
6. 输入 `/ai default` 可选择默认响应 AI，之后直接发送消息即可。

内置 F2 工作区需要 DivZero 与 LDLib2；如果要使用 AI 创建的动态原生界面，请按发布说明安装对应的 KubeJS 等依赖。API Key 使用本机保密输入，不要发送到聊天中。

## 可以这样玩

| 想做什么 | 对 AI 说 |
|---|---|
| 看装备、聊探索 | `@星河 看看我的装备，给我一些探索建议。` |
| 潜行与搭路 | `@星河 潜行进来，再从这里搭路到对岸，留给我走。` |
| 做一台机器 | `@星河 帮我做一个刷石机，并检查它能否运行。` |
| 创造物品与玩法 | `@星河 做一个篮球和篮球架，能蓄力投篮，进球后加分。` |
| 创造伙伴 | `@星河 做一个友好的星球伙伴，能跟随、交易和骑乘。` |
| 修改装备与效果 | `@星河 给我手里的剑加上合适的附魔，再给我一段时间的速度效果。` |
| 设置交互规则 | `@星河 这扇门只有拿木棍时才能打开，旁边仍然能放方块。` |
| 导入建筑 | `@星河 把这个投影放到前方，正门朝北。` |
| 人设与记忆 | `@星河 以后你是简洁友善的星际向导，记住我喜欢石英建筑。` |
| 真人托管 | `帮我接管角色，把这片区域探索完，遇到危险先保证生存。` |

**[查看全部功能、边界与更多截图 →](docs/FEATURES.md)**

## 脚本与扩展

DivZero 支持通过 **JavaScript** 和运行时内容包编写自定义交互、回调和 AI 行为。AI 可以在对应权限与生命周期内使用开放的游戏 API 与对象。

需要本机自动化时，可使用由 Java 管理的专用 Python 运行环境；本机任务会经过游戏内确认。具体能力、依赖与安全边界请查看项目文档。

## 下载、兼容性与项目状态

README 不固定展示具体游戏、加载器、Java、协议或依赖版本，避免首页信息随着发布快速过期。

请始终以以下位置为准：

- **当前可下载构建与对应依赖**：[Releases](https://github.com/gaoshanliuni/divzero/releases)
- **安装与运行要求**：[安装说明](docs/BUILD_JAR.md)
- **完整功能、验证范围和已知边界**：[全部功能](docs/FEATURES.md)
- **源码与技术说明**：[SOURCE_SNAPSHOT](docs/SOURCE_SNAPSHOT.md)

## 许可证与免责声明

DivZero 使用 [Apache License 2.0](https://github.com/gaoshanliuni/divzero/blob/main/LICENSE) 开源许可。

Copyright 2026 gaoshanliuni and DivZero contributors。项目版权与许可声明见 [NOTICE](NOTICE)；第三方组件遵循各自许可证，详见[第三方声明](docs/THIRD_PARTY_NOTICES.md)。

**本项目不包含任何第三方 AI 服务密钥。** 你需要自行配置服务提供商、API URL、Key 和模型，并承担对应 AI 服务产生的费用。模型可用性、能力与价格由对应服务提供商决定。

---

<a id="english"></a>

# DivZero AI Runtime

> **An undefined world. Unlimited creativity.**

**Turn an LLM into a real Minecraft player, builder, and runtime creator.**

DivZero AI Runtime is not just an AI chatbot inside Minecraft. It gives AI agents world awareness, a body, inventory, actions, persistent memory, and runtime creation tools so they can understand natural-language goals, inspect the current world, move, build, fight, use items, create content, and continue multi-step tasks.

Use it as an AI companion, autonomous player, world-building assistant, or runtime creator that can turn a sentence into new items, creatures, interaction rules, interfaces, and gameplay inside the world.

**[Download](https://github.com/gaoshanliuni/divzero/releases) · [All features](docs/FEATURES.md#english) · [Installation](docs/BUILD_JAR.md) · [Report an issue](https://github.com/gaoshanliuni/divzero/issues/new/choose)**

> Exact Minecraft, mod-loader, Java, and dependency requirements change with releases. Check [Releases](https://github.com/gaoshanliuni/divzero/releases) and the [installation guide](docs/BUILD_JAR.md) for the current compatibility matrix. This README focuses on stable product capabilities instead of release numbers.

## Core capabilities

| Capability | What DivZero can do |
|---|---|
| **AI players** | Create agents with their own identity, body, health, inventory, persona, and conversations, then let them act in the real world. |
| **Autonomous execution** | Follow, patrol, guard, wander, fight, farm, fish, navigate, bridge, pillar, use blocks and items, and resume work after interruptions. |
| **World understanding** | Read players, entities, blocks, containers, equipment, effects, commands, and discoverable mod content, then plan from the current state. |
| **Building and importing** | Build roads, walls, surfaces, and structures from geometry, or import blueprints, schematics, and world selections with orientation controls. |
| **Runtime creation** | Create items, creatures, behaviors, interaction rules, and reusable content packages through natural language. |
| **Native dynamic UI** | Use the built-in F2 native workspace, while AI-created interfaces and HUDs can also be generated and modified at runtime. |
| **Multi-agent work** | Run multiple AIs with independent conversations, movement, tasks, and cancellation boundaries in the same world. |
| **Persistent memory** | Store facts and preferences across conversations, with player controls for persona, memory, sessions, and response permissions. |
| **Player takeover** | Explicitly hand your own player to the AI, then watch, pause, add instructions, change perspective, or exit. |
| **Scripting and extensions** | Extend the runtime with JavaScript, content packages, and confirmation-gated local Python tasks, while integrating discoverable mod content. |

## An AI player that actually acts

DivZero agents do not only describe what you should do. They can perform the actions themselves. They navigate terrain, stairs, slabs, doors, height changes, and low-clearance spaces; crouch or build routes when needed; and report blocked paths instead of clipping through walls.

They can hold real items, manipulate inventory and equipment, place and break blocks, use containers and interactions, and keep work rules separate from combat behavior. For example:

> “Keep farming. If monsters appear, kite them, then go back to farming.”

Local execution loops handle routine behavior while the model interprets goals, reacts to changes, and makes higher-level decisions.

![Multiple AI players acting concurrently in one world](docs/images/multi-ai-parallel.png)

## Create gameplay, not just commands

DivZero is designed around runtime composition instead of a fixed list of commands.

Ask for a basketball and hoop with charged throws and scoring, or create a friendly, neutral, or hostile creature with trading, following, riding, breeding, patrol, and combat behaviors.

![Basketball hoop and actual scoring](docs/images/basketball.png)

![A custom planet companion](docs/images/creature.png)

Created content can be modified later instead of being rebuilt from scratch. Items, creatures, and persistent world interaction changes are exposed through a unified content-management flow.

## Native workspace and AI-created interfaces

DivZero uses LDLib2 for its built-in native workspace. F2 and Ctrl+M open the same in-game environment for chat, settings, AI profiles, content, files, memory, and diagnostics.

![LDLib2 MC-themed F2 workspace](docs/images/native-workspace.png)

AI-created native interfaces can be built through KubeJS and changed while they are already open. You can ask for a shop, then add search, reorganize the layout, or change the style while retaining existing state where possible.

![AI-created native shop](docs/images/native-shop.png)

Passive HUDs and normal interfaces use different interaction rules so a HUD does not need to steal game input. Built-in functionality does not depend on MCEF / WebGUI.

## Building, reusable routes, and imports

Agents place real blocks and can continue moving across the routes they build. Bridges, continuous steps, jump-and-place pillars, and ladder routes remain in the world for players and other AIs to use.

![Permanent bridges, steps, and a pillar with a ladder](docs/images/reusable-paths.png)

You can also hand a build file to the AI from F2 → Files or a chat attachment. Supported workflows include structure blueprints, schematics, and Java world selections, with preview, orientation, rotation, mirroring, and content-preservation controls.

![Imported build in the world](docs/images/imported-house.png)

## Persistent memory, multiplayer permissions, and multiple AIs

Each AI can maintain its own persona, conversations, and memory. Facts and preferences can carry into later conversations, such as important locations, building preferences, or the communication style you want that AI to use.

An AI you create serves you by default. When other players try to invoke it, you can allow or deny individual requests, save a persistent decision, or maintain an allow list. World modification, commands, and local-computer tasks still follow their corresponding Minecraft permissions and DivZero authorization flows.

Multiple AIs can act at the same time. Their conversations, movement, plans, and cancellation boundaries stay isolated while they share the same real Minecraft world.

## Player takeover

When explicitly requested, the AI can control your real player through native client input. You can observe the run, switch perspective, pause, continue, add instructions, or exit.

![Player takeover controls](docs/images/player-takeover.png)

Takeover is useful when a task needs your existing identity, equipment, or client-side interactions. Death, disconnects, world changes, and permission changes release control.

## The model sets the ceiling; DivZero connects it to the world

Complex construction, entity design, multi-step planning, and tool use depend strongly on the model you choose. DivZero provides provider configuration, model selection, tool calls, world context, and the execution layer; you bring a compatible model and service.

Stronger models are usually better at long and creative tasks, but any model can make mistakes. DivZero uses permissions, state verification, recovery, and real execution results to help the model continue from what actually happened in the world.

## Quick start

1. Download the current build from [Releases](https://github.com/gaoshanliuni/divzero/releases) and follow the [installation guide](docs/BUILD_JAR.md) for its dependencies.
2. Choose **Enable** in chat the first time you enter a world; no rejoin is required.
3. Open **F2 → Settings → Provider** and configure your API URL, key, and model.
4. Run **`/ai create "Nova"`** or use F2 → AI Players to create an AI.
5. Type `@Nova Hello` in Minecraft chat. Press Tab after `@` to complete an AI name.
6. Run `/ai default` if you want one AI to answer messages without being mentioned every time.

The built-in F2 workspace requires DivZero and LDLib2. AI-created dynamic native interfaces require the additional dependencies listed for the current release. API keys use local confidential input; do not post them in chat.

## Things to try

| Goal | Tell your AI |
|---|---|
| Equipment and exploration advice | `@Nova Check my equipment and suggest how I should prepare for exploring.` |
| Sneaking and bridge building | `@Nova Sneak through here, then build a bridge to the other side that I can use too.` |
| Build a machine | `@Nova Build a cobblestone generator and check that it works.` |
| Create items and gameplay | `@Nova Make a basketball and hoop, with charged throws and points for scoring.` |
| Create a companion | `@Nova Make a friendly planet companion that can follow, trade, and be ridden.` |
| Equipment and effects | `@Nova Improve the sword in my hand with suitable enchantments and give me a temporary speed effect.` |
| Interaction rules | `@Nova Make this door open only when I hold a stick, without blocking placement beside it.` |
| Import a build | `@Nova Place this schematic in front of me with its entrance facing north.` |
| Persona and memory | `@Nova Be a concise, friendly space guide, and remember that I like quartz buildings.` |
| Player takeover | `Take over my player, explore this area, and prioritize survival if anything dangerous appears.` |

**[See all features, boundaries, and more screenshots →](docs/FEATURES.md#english)**

## Scripts and extensions

DivZero supports **JavaScript** and runtime content packages for custom interactions, callbacks, and AI behaviors. Agents can use exposed game APIs and objects within the relevant permissions and lifecycle.

For local automation, DivZero can use a dedicated Python runtime managed by Java, with in-game confirmation for local tasks. See the project documentation for the exact capability, dependency, and safety boundaries.

## Downloads, compatibility, and project status

The README intentionally does not pin specific game, loader, Java, protocol, or dependency versions, so the project homepage does not become stale every time a release changes.

Use these sources instead:

- **Current downloadable builds and dependencies**: [Releases](https://github.com/gaoshanliuni/divzero/releases)
- **Installation and runtime requirements**: [Installation](docs/BUILD_JAR.md)
- **Complete feature scope, validation, and known boundaries**: [All features](docs/FEATURES.md#english)
- **Source and technical notes**: [SOURCE_SNAPSHOT](docs/SOURCE_SNAPSHOT.md)

## License and disclaimer

DivZero is open source under the [Apache License 2.0](https://github.com/gaoshanliuni/divzero/blob/main/LICENSE).

Copyright 2026 gaoshanliuni and DivZero contributors. See [NOTICE](NOTICE) for project copyright and license notices. Third-party components retain their respective licenses; see [Third-party notices](docs/THIRD_PARTY_NOTICES.md).

**No third-party AI service keys are included.** Configure your own provider, API URL, key, and model. You are responsible for charges from your chosen AI service. Model availability, capabilities, and pricing are determined by the provider.
