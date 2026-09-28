package dev.mineagent.runtime.worker.generation;
/** Real Native instance UI binding, not a finished gameplay or UI template. */
public final class WorldUiContract {
    private WorldUiContract(){}
    public static final String TEXT="""
        独立物件界面（仅在需求需要 GUI 时使用）：声明 ui:{path:'ui/index.json',side:'CLIENT'}，divzero-native-ui/1 JSON 是 CLIENT 资源；仍保留独立 SERVER 脚本和 Native 几何，不能用 Canvas 代替世界物件。
        在 on('object.interact',function(event){ content.openUi(event.player(),'ui'); }); 中打开此实例 GUI。openUi 只允许当前真实 Native 交互或已授权 ui.action 内调用，不能在 tick/create/顶层主动弹窗。entry 参数是同包声明的 CLIENT 原生界面入口 ID。
        注册 on('ui.read',function(event){...}) 和 on('ui.action',function(event){...})。event.player() 是实际 ServerPlayer，event.part() 是绑定的 Native partKey，event.action() 是 action 名称，event.payload() 是 JSON 数据字符串，event.revision() 是当前实例 revision。
        每个 UI handler 在同一个同步回调内且仅调用一次 event.reply(JSON.stringify(明确选择的输出字段))，否则失败。不得返回 Promise 或推迟 reply。回复和输入最多8192字符/16层；不要直接暴露全部私有 content.state。
        ui.read 是只读：可读 content.state/Native 数据；不能调用 content.state(key,value)、createObject/placeBlock/openUi 等写接口。直接 Java 不是强制沙箱，仍必须遵守只读契约，不在读取时产生副作用。
        ui.action 根据 event.action() 验证名称和 JSON.parse(String(event.payload())) 数据，再改变实际实例/Native 对象并回复实际结果。操作在服务端记入持久 intent；相同 operationId 不重复执行，未知结果不自动重放。
        原生界面 reads 声明 worldui.read，arguments={}，返回 data={revision,data,executionMode}，内层data仅为上述ui.read选择的输出字段。
        actions 声明 worldui.action，arguments={expectedRevision:上次read的revision表达式,name:动作名,payload:动态对象表达式}。只由明确按钮点击emit触发；读取和结构热更绝不自动重放写入。
        每秒只读刷新用 reads.intervalTicks=20；状态推送用 arguments={listen:"true",listenRevision:1}、intervalTicks=0。宿主保管推送令牌、合并刷新、读回和确认，页面只得到实际读数，不能伪造确认。
        操作失败保留表单并显示服务端冲突，重新读取后由玩家再次明确提交。持久实例revision不是高频动画时间；避免每tick写持久角度。
        在ui.read中可用content.observePlayer(event.player())返回位置、视角、生命/饥饿/经验的JSON字符串；content.observeBlockPage(event.player(),radius,offset)返回以玩家当前位置为中心的实际已加载方块计数分页，radius=1..2047，每页最多4096格。维持一次统计的center/min/max不可把移动后的不同页拼成同一瞬时快照；小半径快速观察，较大区域明确标注分批/未知格。observePlayer返回{position:[x,y,z],yaw,pitch,health,food,experience,gameTick}；observeBlockPage返回{center:[x,y,z],radius,offset,nextOffset,totalCells,counts:{"minecraft:stone":数字,...},unknown,gameTick}。需JSON.parse后读取，计数不在blocks字段。也可直接读event.player().getX()/getY()/getZ()及原生只读API。每页数据必须标注读数时刻。
        关闭/隐藏此窗口或离开授权范围使UI会话失效，实例继续存在；停用包/实例或版本改变后旧窗口不可写，重新真实交互才获取新会话。
        默认MC主题，界面窗口可移动/缩放；可用 ui/view-settings.json 指定原生 GUI 单位的位置和尺寸。普通界面不借助浏览器背景或备用渲染。
        """+NativePackageContract.TEXT;
}
