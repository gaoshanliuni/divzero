# Cinematic feature films

## Delivered recording set

39 individual feature films, a 2:03 promotional montage, and an 8:23 full compilation. Both combined videos have music and silent variants. Chinese/English titles and captions are burned in; editable ASS and a combined bilingual SRT accompany the local delivery. Files use 1920×1080, 30fps, H.264/yuv420p and MP4 faststart; music variants use stereo AAC at 48kHz.

The local delivery is under `build/feature-films/delivery/`, with an HTML gallery, an exact clip index, provenance, complete-decode checks, and 14 grouped YouTube chapters. Individual clips are silent. Background music is an original offline procedural composition, without external songs, samples, microphone or desktop recordings. Local videos, profiles, credentials, databases, generated content and recording caches are excluded from the public source snapshot.

The films cover the early simple interactions as well as the later additions:

1. 对话塑造伙伴 / Give your companion a personality
2. 记住你的偏好 / Remember your preferences
3. 每个 AI 独立选模型 / Choose a model for each AI
4. 跟随与停止 / Follow and stop
5. 拿出与丢出物品 / Equip and drop items
6. 给现有物品附魔 / Enchant an existing item
7. 领取新附魔物品 / Create a new enchanted stack
8. 修改状态效果 / Manage status effects
9. 局内切换外观 / Change a skin in game
10. 创建与应用 PNG 皮肤 / Create and apply a PNG skin
11. 操作原生容器与方块 / Interact with containers and blocks
12. 对话修改游戏规则 / Change game rules through chat
13. 查询附近矿物 / Locate nearby ore
14. 联网查攻略 / Research a guide online
15. 本机 Python 与确认 / Local Python with chat approval
16. 真实身体挖掘与放置 / Mine and place with a real body
17. 自动潜行通过低通道 / Crouch through low passages
18. 搭出可复用的路 / Build a path you can use
19. 留下梯子，玩家也能走 / Leave a route the player can follow
20. 多个 AI 同时行动 / Many companions, working together
21. 可交互的动态物品 / Interactive runtime items
22. 篮球：蓄力、投掷与计分 / Basketball: charge, throw, and score
23. 几何造型建造 / Build with geometric shapes
24. 搭建能工作的刷石机 / Build a working cobblestone generator
25. 导入建筑并调整朝向 / Import and orient a structure
26. 保留建筑内容 / Preserve structure contents
27. 游戏内文件库 / An in-game file library
28. 自由查看 3D 模型 / Inspect a 3D model
29. 生成可交互网页界面 / Generate an interactive in-game UI
30. 让物品成为交互钥匙 / Use an item as an interaction key
31. 生物右键交易 / Trade with a custom creature
32. 投料触发生物交互 / Barter by dropping an item
33. 喂食与繁殖 / Feed and breed creatures
34. 跟随、巡逻与战斗 / Follow, patrol, and fight
35. 骑乘自定义生物 / Ride a custom creature
36. 靠近触发事件 / Trigger events by approaching
37. 层级骨骼动画 / Hierarchical bone animation
38. 派生原生生物 / Derive native creatures
39. 局部模型替换 / Remix a single model part

## Reusing the filming tools

- `scripts/record-feature-video.py --config <local JSON> --scene <name>` creates a unique isolated profile and launches an existing native fixture. Configuration supplies the version directory, Java executable, Actions-built artifact, base mods, a test seed world, output directory, FFmpeg, and per-scene properties/camera/input files. A provider database is only required for a scenario that explicitly needs it; saved scenarios retain a zero-provider audit budget.
- `scripts/edit-feature-films.py <edit JSON>` renders explicitly reviewed in/out points with bilingual captions, thumbnails and an ordered compilation. Slow motion is labeled. Each output is checked with FFprobe for resolution, codec, pixel format, frame rate and duration.
- `scripts/finish-feature-films.py <edit JSON>` adds an original NumPy-generated music bed, a short promotional montage, and silent combined variants.

The tested filming toolchain is Windows, Python, FFmpeg with libass and NVIDIA NVENC, Minecraft 26.1.2, NeoForge 26.1.2.106 and Java 25. Build the runtime through the public GitHub Actions workflow. Its `build_only` dispatch option creates the recording artifact without publishing a Release.

`-Dmineagent.cinematic=true` and `-Dmineagent.cinematic.ffmpeg=<absolute executable>` opt the isolated integrated client into recording. `cinematic.json` in that profile selects native UI views or phase-specific orbit/follow shots. Capture reads the Minecraft framebuffer into a bounded queue, records constant-rate timestamps, and writes encoder/phase receipts and inspection previews.

`CinematicCameraMixin` changes only the rendered camera pose, retaining the actual local player as input owner. This preserves climbing and riding. Existing fixture completion holds six seconds for result footage. Additional pauses in selected original fixtures make brief interactions readable. Normal game sessions do not enable filming. Give the launcher a verification seed world; each take is copied into a new isolated game directory.

## Provenance and verification boundaries

- World actions, shapes, items, UI, entities and scores are native game output. Captions and music are editing layers. No generated image or synthetic gameplay footage is used.
- Basketball and the interactive key reuse the previously verified generated script/model files. Only the old runtime-version compatibility declaration was migrated for the filming artifact; originals and migration records are retained.
- Creature, file/skin, Python and some building scenes reuse saved verified outputs. Saved building modes route those outputs through the same production tools and retain the original fluid-regeneration and geometry/state checks. They do not manufacture new model conversations or provider receipts.
- The recorded ore-search run returned native ore locations but failed the separate expected empty-first-scan strategy assertion. That clip demonstrates observed locations only; it makes no progressive-search-strategy claim. Original failure evidence is retained.
- The camera's initial interpolation and input-ownership failures, early obstructed takes, expired unapproved Python take, legacy version-declaration rejection and fresh-model failures were retained. Final filming uses corrected views and explicit saved-output modes where appropriate. The original unrelated PackageAssetSmoke working edits were not included.
- Four final combined files passed complete FFmpeg decoding, and all 39 individual outputs passed encoding/duration checks. Raw receipts and selected-frame inspections remain beside the local delivery. This recording set is not a new all-features/V1 acceptance certificate.

The capture records video only. Music in the combined files is the separately composed backing track, not Minecraft audio. YouTube chapters are grouped so each section is at least ten seconds. No video upload, production installation or automatic Release is performed by these tools.
