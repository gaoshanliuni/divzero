package dev.mineagent.runtime.worker.generation;

/** Shared creation/editing contract for arbitrary LDLib2 trees and scoped, declarative KubeJS interactions. */
public final class NativePackageContract {
    private NativePackageContract(){}
    public static final String TEXT="""
        界面统一使用 LDLib2 MC 主题，动态控件由 DivZero 的 KubeJS 桥构建。内置 F2 不依赖 KubeJS。
        UI 入口必须是 ui/ 下的 CLIENT application/json 文件，格式 divzero-native-ui/1；不生成 HTML/CSS/DOM 或浏览器脚本，不依赖 MCEF，不把 HTML 放进 Rhino 执行。
        传输示例（不是固定界面模板）：manifest.entrypoints.ui={path:"ui/index.json",side:"CLIENT"}；files 中对应 content 是下面文档的完整 JSON 字符串。
        文档 {"format":"divzero-native-ui/1","view":{界面定义},"reads":{},"actions":{}}。
        view 可以包含任意已支持控件树、样式、输入、数据表达式和本地事件；以下通用界面语法中的 sources/handlers 在签名包内不用，改用下面的 reads/actions，以保留准确服务端内容会话。
        """+dev.mineagent.runtime.core.ui.dynamic.InterfaceDefinition.CONTRACT+"""
        签名包可用 reads:{"live":{"action":"worldui.read","arguments":{},"result":"live","intervalTicks":20}}。
        result 是独立数据键，回执形状 {code,values,data}；values 中的 JSON 字符串由宿主解析，data 优先取 values.state，否则取整个 values。使用 get(data("live"),"data") 读取实际状态，必须检查 code 和业务拒绝字段。
        reads 的 intervalTicks=0 表示准入后读一次，5..72000 表示有界无重叠轮询；常规实时数据用20，每次只更新绑定控件，不重新建树，不覆盖未提交输入。旧连接/文档/权限不能接受回调。
        actions 以任意稳定名字定义 {action,arguments,result}；events.click 中 emit 的 action 必须引用此名字。参数值使用表达式，宿主把字符串直接传递，其他JSON值序列化成协议字符串。
        动态对象用 {"op":"object","args":["query",{"data":"query"},"count",{"op":"number","args":[{"data":"count"}]}]}；array 构造有界数组；json 把任意数据转为 JSON 文本。普通 string 仅转换标量。get 对缺失值返回 null，at 越界报错，使用 lazy if 保护未加载或越界的行。
        本地 set/toggle 只修改界面数据，emit 的 event 参数和 eventValue 可供参数表达式读取。不会凭按钮成功回调假称业务已完成。
        每次明确交互生成唯一操作ID；挂起/未知结果不自动重放。业务结果单独显示，失败保留输入，读取成功后才更新依赖的版本号。
        计分读数：scoreview.read arguments={}，data={sourceId,sourceReference,viewRevision,snapshot:{viewId,revision,title,rows,layout}}；rows 每行={holder,displayName,score,formattedScore,iconSha256}。不创造影子分数。
        计分布局：scoreview.patch arguments={expectedViewRevision:上次真实版本表达式,patch:对象表达式}，支持 title/sort/topN 等原有布局；返回真实状态。这不修改计分目标/分数或背包。
        常驻计分展示默认 HUD：另声明 entrypoints.hud={path:"ui/hud.json",side:"CLIENT"}，view.surface=HUD。HUD 独立读取，同包编辑页可为SCREEN。多个视图独立ID；普通HUD不抢鼠标，不能通过自动写入模拟实时显示。
        真实容器：另声明 entrypoints.container={path:"ui/container.json",side:"CLIENT"}；container.read arguments={} 返回原Native menu快照（revision/menuId/nativeStateId/menuType/actorId/slots/carried），不伪造库存。
        container.act arguments={expectedRevision:真实revision表达式,action:动作对象表达式}。动作对象={kind:"CLICK",slot:槽位索引,button:0,clickType:"PICKUP",slots:[]}，CLICK还支持QUICK_MOVE/SWAP/CLONE/THROW/PICKUP_ALL；拖分为{kind:"DRAG",slot:-999,button:模式0/1/2,clickType:"QUICK_CRAFT",slots:[索引...]}；先读真实 revision，服务端原版规则校验，冲突只重新读取。关闭隐藏使真实菜单关闭、光标物品按原规则归还。
        package:ui/icon.png 引用本包经签名/hash校验的 PNG/JPEG，不能读取其他包私有纹理。Minecraft公开资源可用命名空间路径。不能用URL、本机文件、CDN。
        输入可声明 secret:true；保密字段不进入观察器、私有截图或草稿持久化。Provider Key 仍使用专门的内置保密设置入口。
        MC 控件默认样式已加载；布局建议 padding-all、margin-bottom、width、height、flex-grow、gap 等实际 LDLib2 LSS。不使用 Modern。结构更新先验证新树，失败保留原界面及具体错误；稳定id/bind保留匹配输入。
        """;
}
