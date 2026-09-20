package dev.mineagent.runtime.worker.generation;
/** Current server binding reference, not a library of finished objects. */
public final class WorldContentPrompt {
    private WorldContentPrompt(){}
    public static String build(String request){return """
        为 Minecraft 26.1.2 / NeoForge 26.1.2.106 / Java 25 的 MineAgent Runtime 生成独立世界内容。
        只输出严格 JSON 对象，根字段 manifest、files，不要 Markdown；没有成品模板和失败回退。
        manifest 仅允许 name、version、type、activationMode、permissions、entrypoints、definitions、dependencies、nativeCompatibility 这9个字段。HOT_RUNTIME/DATA_RELOAD/WORLD_REOPEN 的 nativeCompatibility 必须有 {schema:1,targets:{SERVER:{minecraft,loader,loaderVersion,namespace,javaFeature,requiredMods:{}}}}；RESOURCE_RELOAD 使用下文的 CLIENT 目标契约，不要求虚构 SERVER 入口。各字符串/数字版本来自实际 SERVER 环境，requiredMods 只填实际需要的 Mod 与准确版本，不能用 latest、* 或范围；含 COMMON/CLIENT 原生 JS 的包还须有 CLIENT 目标，ui/ 浏览器 JS 不需要 CLIENT 原生声明。网页入口放 manifest.entrypoints.ui，不能新增 manifest.ui/hud/feedback/viewSettings。
        type 按需求选择 CONTENT、FEATURE、SKILL、ADAPTER、EXTENSION。permissions 包含 RUN_CODE，无依赖时 dependencies={}。
        activationMode 必须如实选择 HOT_RUNTIME、RESOURCE_RELOAD、DATA_RELOAD、WORLD_REOPEN、BOOT_EXTENSION；HOT_RUNTIME 走实例执行器，DATA_RELOAD 走真实 Vanilla 数据包安装与重载；WORLD_REOPEN 走明确暂存与重开后原生 Registry/维度消费。需要重载/重启的功能必须明确声明，不得伪报 HOT_RUNTIME 或模拟生效。
        HOT_RUNTIME 的 entrypoints 是 ID 到 {path,side} 的映射，必须有 server:{path:'server/main.js',side:'SERVER'}（实际 JSON 使用双引号）。确有本机客户端原生逻辑时，可另加 client:{path:'client/main.js',side:'CLIENT'} 交付Rhino，或 client_java:{path:'client/<类路径>.java',side:'CLIENT'} 交付Java 25源码；可同时存在。client/中对应文件使用CLIENT/COMMON并准确声明nativeCompatibility.targets.CLIENT。CLIENT Java主类必须实现dev.mineagent.runtime.scripting.javaext.ClientRuntimeExtension，通过bindings中的dev.mineagent.runtime.api.packages.ClientRuntimeHost访问客户端。package dependencies可混合CLIENT Rhino/Java，但每个准确版本都必须已由玩家本机下载并逐个批准运行。Rhino用callPackage(UUID字符串,export,args数组)调用直接依赖；Java从bindings的packages取得dev.mineagent.runtime.api.packages.ClientPackageBridge，Java依赖若提供跨语言调用则实现ClientPackageExports。调用仅复制null/Boolean/String/安全Number/List/Map<String,...>有界数据，不共享运行时对象或生命周期。下载后仍须每台客户端单独确认，本机才在实际CLIENT classpath上Javac并加载；服务端授权不等于本机执行许可。不要把ui/浏览器脚本放入client入口。
        HOT_RUNTIME 的 definitions 非空数组，每项包含 definitionId（有效 UUID）、name、kind（BLOCK/ITEM/ENTITY/MACHINE/RULE/OTHER）、entrypointId:'server'、resourcePaths:[]、settingsSchema:{}、revision:1、stateSchemaVersion:1。不要把具体对象预置进主体。
        files 每项 path、side、mediaType、encoding、content。JS mediaType=application/javascript，encoding=utf8，side=SERVER。
        无需输出 sha256，可信阶段从文件字节计算；每个模块需实际位于 files。可使用 require('server/文件.js')，不是 Node 或浏览器。
        BOOT_EXTENSION 使用 type=EXTENSION、activationMode=BOOT_EXTENSION、definitions=[]；无依赖时dependencies={}，需要已有BOOT包时将用户明确给出的RuntimePackage UUID映射到准确发布版本，不能猜UUID或填buildId。构建只解析已明确安装的真实BOOT artifact及传递闭包，不代装、不使用library enabled冒充安装；其它原生Mod仍在nativeCompatibility.requiredMods声明准确版本。entrypoints.boot={path:"boot/extension.json",side:"CLIENT"或"SERVER"或"COMMON"}，其它非UI资源同侧。SERVER 是物理 dedicated server；整合服务器在 CLIENT JVM，需声明 CLIENT 或 COMMON 和对应精确环境，不把逻辑SERVER当物理侧。COMMON 必须声明两侧目标。
        boot/extension.json 严格为 {"schema":1,"modId":"按包用途生成的唯一小写id","entrypoint":"自己的包名.Main","mixins":[]}。boot/src/<Java包路径>/<类名>.java 提供完整 Java 源码，入口实现 dev.mineagent.runtime.api.extension.BootExtension，public 无参构造，initialize(java.util.Map<String,Object> context)；context 的 modEventBus 是真实 IEventBus，modContainer 是真实 ModContainer，physicalSide 是实际 Dist 名称，另有 modId/packageId/packageHash。启动时没有世界/玩家/server 对象，按真实 Mod 事件和 Registry API 注册，不用虚构方法。不要自行声明 @Mod，不覆盖 java/javax/jdk/net.minecraft/net.neoforged/dev.mineagent.runtime 类；可信 builder 合成唯一 @Mod Bootstrap 与 neoforge.mods.toml。
        资源放 boot/resources/，输出 JAR 去除此路径前缀；可有真实 assets/、data/、META-INF/accesstransformer.cfg 和 Mixin JSON。mixins 数组列实际根目录配置文件名；Mixin 类仍来自 Java 源码。不要生成二进制.class/.jar、自定义服务发现或覆盖META-INF Loader元数据。最多64个Java文件、4MiB源码，总资源16MiB。构建只编译不执行，禁 annotation processors；依赖实际 Raw Module 符号，AT/Mixin 后符号未由编译证明。玩家在 F2 包目录选择构建后，还必须另行明确全局安装并自行重启；不得报告生成/编译成功就已加载，也不得隐藏停用无法回滚旧存档数据。
        DATA_RELOAD 和 WORLD_REOPEN 都交付纯数据包：entrypoints.datapack={path:"datapack/pack.mcmeta",side:"SERVER"}，definitions=[]；服务器资源全放 datapack/ 下且 side=SERVER，可另外有 ui/CLIENT 网页。不要混入 server/shared Rhino JS，也不要虚构实例。pack.mcmeta 使用实际 MC 26.1.2 数据格式：{"pack":{"description":"包的真实用途","min_format":[101,1],"max_format":[101,1]}}。提供 data/<namespace>/recipe、function、loot_table、tags 等实际文件；namespace/路径小写。具体数据与命令由需求决定，不是演示模板。重载会影响全服并可能触发数据包的 load 函数；生成后仍须玩家另行审批安装。维度、worldgen 及其它启动期 Registry 必须 WORLD_REOPEN，不能伪报 DATA_RELOAD。WORLD_REOPEN 使用 data/<namespace>/dimension/<id>.json、dimension_type、worldgen 等目标版本真实 JSON；只按用户需求生成，不内置固定维度。原生 WorldLoader 在重新打开后读取，暂存不等于当前已创建维度，影响未来生成而不是重建旧区块。不得删除原已安装版本的启动期 Registry 文件来假装完成迁移。BOOT_EXTENSION 必须走独立构建 / 全局安装 / 重启流程，生成完成不是生效。
        RESOURCE_RELOAD 交付纯客户端资源包：entrypoints.resourcepack={path:"resourcepack/pack.mcmeta",side:"CLIENT"}，definitions=[]，其它资源放resourcepack/assets/<namespace>/...并标CLIENT或COMMON，可另有ui/网页；不混入SERVER/Java/Rhino入口。MC26.1.2资源包格式84.0，pack metadata min_format/max_format都用[84,0]。必须声明nativeCompatibility.targets.CLIENT准确的minecraft/loader/loaderVersion/namespace/javaFeature/requiredMods；CLIENT并未从SERVER环境推断其Mod清单，不能猜第三方Mod版本。纯Vanilla资源可以只要求锁定MC26.1.2/NeoForge26.1.2.106/official/Java25及空requiredMods，实际客户端还会重新检查。下载不等于启用，玩家要另行确认本机全局资源选择与重载；所有账号/世界/菜单可能受影响，不声称只是当前服务器界面。
        以下 content/instance 与 Rhino 规则仅适用于 HOT_RUNTIME，不要求 DATA_RELOAD/WORLD_REOPEN 生成这些模块。
        HOT_RUNTIME 必须生成完整 Rhino JS，在明确玩家启用时执行；不需要 HTML 入口，除非需求明确要求网页。网页使用 ui/CLIENT 和独立 ui entrypoint，半透明 glass-sage 宿主主题。
        真实绑定：server 是 MinecraftServer，level 是实例所在 ServerLevel；可用 Java interop 调用真实对象方法，不是隔离的 Java 安全沙箱。不要猜测未列出、未核验的引擎全局函数或 Minecraft 方法签名。
        instance 是 RuntimeInstance Java record，instance.instanceId().toString()、instance.definitionId().toString()、instance.location().x()/y()/z() 可用。
        content 是当前实例的受管宿主，content.placeBlock(partKey,dx,dy,dz,blockId) 在实例原点的整数偏移放置原版方块，返回是否改变；只能放入空气，不能覆盖现有世界物件。
        partKey 为唯一的 [a-zA-Z0-9_.-] 标识。同 partKey 重试必须同坐标/方块；每实例最多 128 个受管方块，偏移绝对值<=32。不要使用 mineagent_runtime 的旧演示 IDs。
        content.state(key) 返回持久字符串（不存在为空串），content.state(key,value) 更新本实例持久值，不要用浏览器存储。key 不得以下划线开头。计数等请显式 Number(...) 转换。
        content.blockCount() 是当前仍与实际原版方块匹配的受管块数，不是模型声明成功。
        通用 3D 世界物件：content.createObject(partKey,modelPath,dx,dy,dz) 从本定义声明的模型资源创建 RuntimeObjectEntity，返回真实 Native 实体；最多32件/实例、128件/世界，偏移绝对值<=32。
        模型 JSON 文件 side=COMMON、mediaType=application/json；其路径必须列入 definition.resourcePaths。可选 texture PNG 也必须是 COMMON/CLIENT 的 image/png 资源且列入 resourcePaths。不能读 SERVER 源码作为可公开模型。
        模型严格 JSON version=1；collision 为[-width/2,0,-depth/2,width/2,height,depth/2]，各尺寸0.05..32。这是独立轴对齐碰撞箱，不是三角形精确碰撞；空心通道应拆成多个受管物件，不伪称一个大包围盒有洞。
        几何可混用 boxes:[{from:[x,y,z],to:[x,y,z],color:'#RRGGBB'}]（最多128盒）与 vertices:[[x,y,z,u,v],...]、triangles:[[a,b,c,'#RRGGBB'],...]。坐标绝对值<=32，最多4096顶点/8192三角形，不允许退化三角形；color 也可用 #AARRGGBB。
        physics 可省略（静态），或完整写 {dynamic:true,mass:1,gravity:0.04,restitution:0.7,drag:0.99}；重力按20Hz服务端Tick，gravity/restitution/drag为0..1，mass>0且<=10000。最大速度4块/Tick，Native AABB 扫掠碰撞有界分步，不把它描述成任意刚体引擎。
        texture 可省略（原生白底乘顶点颜色），或指向真实 PNG。PNG 文件<=1MiB，尺寸<=1024×1024。没有有效 PNG 时使用几何颜色，不能虚构合法图片字节。
        content.object(partKey) 获取本实例已加载的实际实体；entity.velocity(vx,vy,vz) 设置有界速度；entity.spring(worldX,worldY,worldZ,stiffness,damping) 增加到世界坐标固定锚点的弹簧约束，后两参数0..1；clearSpring() 清除。位置可读 getX()/getY()/getZ()，setYRot(degrees) 改视觉朝向，轴对齐碰撞箱不旋转。
        通用 HOT 物品：content.giveItem(partKey,modelPath,count,displayName) 给本实例 Owner 背包真实物品栈，返回实际插入数，0..count；count=1..64。主体只有 mineagent_runtime:runtime_item 通用 Registry 载体，独立包提供模型/脚本，不是原版物品改名，更不是新 FML Registry ID。
        生成/发布包不会执行脚本；instance.create 只有在玩家已明确批准启用时才执行。用户要求批准后给予物品时，应在 instance.create 调用 giveItem 一次；不要漏掉发放，也不要等待不存在的额外“外部执行器发放”接口。
        同实例相同 partKey 只发放一次；重复相同参数返回已记录插入数，不再次给予。背包不足不丢地上、不自动重试。恢复回调不要新发放，旧物品随原生存档持久化，恢复后仍绑定原实例/包。
        物品模型仍是上述 RuntimeMesh JSON，路径属于本定义 COMMON/CLIENT 资源；当前物品只支持几何颜色，不能含 texture，原始模型最多8192 UTF-8 bytes、2048顶点/三角形。顶点 x/z 在[-0.5,0.5]，y在[0,1]；collision/physics仍按模型格式写，物品本身不因此获得刚体物理。可自由生成 boxes/vertices/triangles，不能拿旧固定演示物品顶替。
        on('item.use',function(event){...}) 是实际手持物品右键事件。event.part()、event.player()、event.operationId() 可读取；content.state读写记录本实例状态。event.restyle('models/other.json','新名称') 只修改这次本人实际手持的同一栈模型与名称，另一个模型也须本定义声明且满足物品限制，保留数量/其它组件。可据状态切换外观/交互逻辑，无需注册新Item或重载资源。
        物品行为来自生成的 Rhino handler，不是固定玩法模板；停用/失效包后旧物品仍保存且可渲染，但不调用脚本。当前未提供通用蓄力/投掷/拾回物理，也不保证任意新Registry物品可HOT创建。不要生成假的成功消息代替真实item.use或restyle。
        content.objectCount() 只计算真实加载且匹配绑定的物件。新区块/实体可能尚未可查询，周期回调应先核对 objectCount，不用空引用假称已操作；创建回调可直接使用 createObject 的返回实体。
        on('object.interact',function(event){...}) 是实际 Native 玩家主手交互的定向事件，event.part() 和 event.player() 是真实 partKey/ServerPlayer，event.operationId() 是本次 Native 交互 UUID；不是网页点击回读。sharedTransact 的操作键在该回调内自动绑定此 UUID，同一次回调重用相同键仍去重，后续真实点击不与初始化或上一次点击共用 activation 根。事件回调沿用25ms普通预算，不能调用生命周期专用预算或等待模型。
        重启获准恢复时 createObject 用相同 partKey/modelPath/初始偏移绑定已有 UUID，不新建或重置运动状态；缺失或未知对象不自动重放创建。物件和几何/物理不依赖 WebGUI timer。停用清理JS并冻结物件，不静默删除世界实体；任意原生副作用仍需显式清理。
        on('tick',function(tick){...})、schedule(delayTicks,function(){...}) 和 track(AutoCloseable) 为现有 Runtime 生命周期；不要每 tick 请求模型/网络，不生成无界循环或大规模写入。
        顶层是这次明确创建实例的执行，不因关聊天而停止。停用清理受管 JS handler/timer，世界块和实例数据保留，不声称任意原生副作用可回滚。
        不自行写死玩家世界坐标，不自行创建多个独立假实例；使用 instance 原点。实际无法满足的能力必须失败，不用文字宣称物理/动画/物品等已实现。
        新 HOT_RUNTIME 世界脚本使用注册/创建/恢复分离：声明 entrypoints["server.restore"]，与 entrypoints.server 完全相同的 path 和 side（同一份 server/main.js，哈希由可信阶段计算）。
        对其他定义入口，也用其 entrypointId 加 ".restore" 作为别名，指向同一模块。别名只是恢复能力声明，不是授权；只有玩家明确单独允许才能自动恢复。
        注册模块顶层只能有 'use strict'、字面量/JSON 数组对象初始化的 var/let/const、命名函数声明和 on('事件', function(...) {...})。不要顶层调用 content/server/level、require、schedule、IIFE 或动态初始化；不得重定义 on/require/schedule/track/content/instance/server/level。
        on('instance.create',function(){...}) 只在首次明确创建时运行，放置方块与初始化持久状态放在这里。
        on('instance.restore',function(){...}) 在重启获准恢复时运行；必须保留已有状态，不清零，不重新创建世界块。可以检查已有块、恢复连接或记录恢复次数。
        on('tick',function(tick){...}) 注册在顶层，函数体内照常访问 Native API；tick 在创建/恢复成功后继续调度。函数体内仍是完整 Rhino/Java 互操作，没有有限玩法模板。
        辅助模块也必须满足纯注册顶层；需要 require 时放进生命周期回调。至少明确注册 instance.create 和 instance.restore，即使恢复回调只验证已有状态。
        需求：
        """+WorldUiContract.TEXT+SharedStateContract.TEXT+ScriptEventContract.TEXT+ScriptScheduleContract.TEXT+SchedulePushContract.TEXT+FeedbackContract.TEXT+FeedbackContract.SERVER_TEXT+"\n用户需求：\n"+request;}
}
