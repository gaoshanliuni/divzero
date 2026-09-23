package dev.mineagent.runtime.worker.generation;
/** Real Native instance UI binding, not a finished gameplay or UI template. */
public final class WorldUiContract {
    private WorldUiContract(){}
    public static final String TEXT="""
        独立物件界面（仅在需求需要 GUI 时使用）：声明 ui:{path:'ui/index.html',side:'CLIENT'}，HTML/浏览器 JS 是 CLIENT 资源；仍保留独立 SERVER 脚本和 Native 几何，不能用 Canvas 代替世界物件。
        在 on('object.interact',function(event){ content.openUi(event.player(),'ui'); }); 中打开此实例 GUI。openUi 只允许当前真实 Native 交互或已授权 ui.action 内调用，不能在 tick/create/顶层主动弹窗。entry 参数是同包声明的 CLIENT HTML 入口 ID。
        注册 on('ui.read',function(event){...}) 和 on('ui.action',function(event){...})。event.player() 是实际 ServerPlayer，event.part() 是绑定的 Native partKey，event.action() 是 action 名称，event.payload() 是 JSON 数据字符串，event.revision() 是当前实例 revision。
        每个 UI handler 在同一个同步回调内且仅调用一次 event.reply(JSON.stringify(明确选择的输出字段))，否则失败。不得返回 Promise 或推迟 reply。回复和输入最多8192字符/16层；不要直接暴露全部私有 content.state。
        ui.read 是只读：可读 content.state/Native 数据；不能调用 content.state(key,value)、createObject/placeBlock/openUi 等写接口。直接 Java 不是强制沙箱，仍必须遵守只读契约，不在读取时产生副作用。
        ui.action 根据 event.action() 验证名称和 JSON.parse(String(event.payload())) 数据，再改变实际实例/Native 对象并回复实际结果。操作在服务端记入持久 intent；相同 operationId 不重复执行，未知结果不自动重放。
        浏览器的 window.mineagentWorld 由可信宿主注入，version=1。页面若已有它可初始化，否则监听 mineagent:world-ready。SDK 初始化不自动读写。
        await mineagentWorld.read() 得到 {revision,data,executionMode}；data 只含服务端 ui.read 明确回复的 JSON。await mineagentWorld.action(expectedRevision,actionName,payload,operationId) 发送动作，参数不包含 actor、实例或包身份。operationId 可省略自动生成，但不确定结果的重试必须保存原ID和原参数。
        需要持续坐标、生命值、周围方块读数时可显式const stop=mineagentWorld.watch(onState,onError,1000)。这是同一当前PLAYER会话每秒只读刷新，只有上次完成才下一次，无并发积压、不重放写入；隐藏文档暂停，关闭/导航停止，权限/实例失效报错后停止。onState只改数字/显示，不重建整页或盖掉草稿。不要同时watch和额外setInterval。watch不同于事件推送subscribe；不需为纯读数反复调用模型或创建32次的有限调度。
        在ui.read中可用content.observePlayer(event.player())返回位置、视角、生命/饥饿/经验的JSON字符串；content.observeBlockPage(event.player(),radius,offset)返回以玩家当前位置为中心的实际已加载方块计数分页，radius=1..2047，每页最多4096格。维持一次统计的center/min/max不可把移动后的不同页拼成同一瞬时快照；小半径快速观察，较大区域明确标注分批/未知格。observePlayer返回{position:[x,y,z],yaw,pitch,health,food,experience,gameTick}；observeBlockPage返回{center:[x,y,z],radius,offset,nextOffset,totalCells,counts:{"minecraft:stone":数字,...},unknown,gameTick}。需JSON.parse后读取，计数不在blocks字段。也可直接读event.player().getX()/getY()/getZ()及原生只读API。每页数据必须标注读数时刻。
        WebGUI窗口是独立可移动/缩放/最小化/关闭的网页视图，不是聊天消息，也不是Minecraft物品建模的Canvas替代。World UI绑定实际对象与当前玩家；读数窗口持续显示不意味着持续占用键鼠，悬浮与交互是不同状态。距离/权限边界仍生效，不能假称跨地图跟随显示。
        可显式const stop=mineagentWorld.subscribe(onState,onError)开启只读状态订阅；此时进行一次初读，后续仅在Native推送提示到达时有界合并刷新，不并行轮询。stop()解除当前页面订阅，不撤销其他窗口。回调拿到同read的{revision,data,executionMode}，仅更新展示节点，不能覆盖未提交表单、滚动或焦点；失败显示诊断，不自动重放action。即便实例revision没增加，共享数据或Native状态仍可变化，不能按相同revision跳过推送刷新。
        事件STATE_PUSH的目标由GENERAL在inspect_state_push_targets后明确配置；SDK.subscribe只是当前文档同意接收，不自建全局事件订阅或取得写权限。推送不带源事件私有数据，Native给原Session只读拉取；隐藏/关闭/导航/委派变化后旧令牌失效，重新打开需新文档subscribe初读。不要将Native交付读取回执当作页面绘制或用户已读证明。
        action 的 payload 直接传 JSON 数据对象，SDK 会编码；配置对象不要二次 JSON.stringify 成字符串。若业务 reply 含 ok/error 字段，页面须检查实际 data.ok/data.error，不能吞掉拒绝后显示成功。
        页面不能使用 mineagentUi/ScoreView API 冒充世界实例数据。草稿不要被轮询覆盖；没有 localStorage，不自动提交表单。冲突显示 WORLD_UI_STATE_CONFLICT，允许只读刷新后由玩家明确再次提交，不自动重放写入。
        read 的 revision 是实例持久状态 revision；避免每 tick 写持久角度等高频状态，使配置动作无法通过 CAS。角度可来自 Native entity.getYRot()，持久 state 仅保存需要恢复的配置。周期 read 建议<=1次/秒且不重叠。
        关闭聊天不关闭此窗口；关闭/隐藏此窗口或离开8格范围会使此UI会话失效，但不会停止世界实例。停止包/实例或更换源码后旧GUI不可写，需另一次真实交互重新打开。当前此接口是 PLAYER，不借玩家OP委派AGENT。
        未声明自有外观时，受管网页沿用默认 glass-sage 半透明主题及高对比文字，模型/图片/Canvas 的语义色不套 UI tint。
        可用独立 CLIENT application/json 文件 ui/view-settings.json 声明初始位置/大小：{"schema":1,"entries":{"ui/index.html":{"anchor":"TOP_RIGHT","width":560,"height":440,"offsetX":-24,"offsetY":12,"appearance":"PACKAGE"}}}。
        entries 键须为同包真实 CLIENT HTML 入口路径。anchor 允许 TOP_LEFT/TOP_RIGHT/BOTTOM_LEFT/BOTTOM_RIGHT/CENTER；宽高为64..8192 CSS 像素，偏移为-32768..32768，宿主按可用区域调整并回读实际值。玩家保存的布局优先于默认值，普通刷新不重置。
        appearance 默认 GLASS_SAGE；明确选择 PACKAGE 后由该页正常 HTML/CSS 设计配色、字体、边框等，不强制重染，但私有不透明底层、权限与桥接限制保留。可选 opacity 为0..1有限数值，仅用于客户端明确启用的LIVE_ATLAS Native后端；未确认支持时省略，不使用CSS opacity替代；不支持的客户端明确拒绝该声明。
        """;
}
