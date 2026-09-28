package dev.mineagent.runtime.worker.generation;

/** Current public SDK/consumer contract, not a finished page or an application template. */
public final class FeedbackContract {
    private FeedbackContract(){}
    public static final String TEXT="""
        多人投递由GENERAL offer_content指定准确签名版本和受众，玩家明确接收后显示原生内容包；通知不能自动接受或抢焦点。
        reads 使用 delivery.read，arguments={}，data={deliveryId,revision,data,assetOnly:true,feedback?}；宿主负责实际读取见证与独立原生绘制回执，不把收到邀请当作已经显示。
        需要反馈时，添加CLIENT application/json ui/feedback.json：{"schema":1,"entries":{"ui/index.json":{"events":{"answer":{"mode":"RECORD_ONLY","lifecycle":"INDEPENDENT","max_events":8,"cooldown_ms":1000,"fields":{"answer":{"type":"string","max_length":200}},"required":["answer"]}}}}}。
        schema=1，最多8入口/入口8事件/事件16字段，事件名ASCII标识符；实际原生JSON入口必须存在。mode=RECORD_ONLY、DETERMINISTIC、AGENT_WAKE；lifecycle=PAGE_BOUND或INDEPENDENT；max_events=1..64，cooldown_ms=0..600000。
        字段type=string/boolean/number。string需max_length=1..2048，number可带minimum/maximum；payload仅声明的标量字段，UTF8<=8192，不包含身份、taskId、权限、SQL或代码。
        只有 offer_content feedback_enabled=true且策略匹配才可提交。actions中声明 feedback.submit arguments={event:"answer",payload:动态对象表达式}，只在明确按钮交互emit，不自动提交。文档身份和作者由Native确定。
        提交回执data={feedback:{feedbackId,state,revision,conversationId,...},businessVerified,modelDispatched}；真实反馈摘要位于 data.feedback（也等于 values.feedback），不能把 feedbackId/state 当作 data 的直接字段。ACCEPTED/PROCESSING不表示业务完成。reads可用 feedback.read arguments={feedbackId:真实ID表达式} 回读data={feedback:摘要,payload:原始标量字段对象,result:实际完成结果,recovery:恢复信息}；feedback.stateRead回读该反馈获准共享字段（revision/schemaVersion/values）。首次反馈前不得伪造ID或共享值。
        确定性成功需state=COMPLETED且result.receipt.status=APPLIED；冲突为REJECTED/CONFLICT。显示实际拒绝，不伪称回滚，也不自动重放未知结果。
        AGENT_WAKE需要发送者为准确package/revision/hash/entry/policy/event配置独立消费者/预算；专用任务不能继承owner私有信息或世界写权限。result.reply作为纯文字显示。
        DETERMINISTIC需同包已有SERVER世界规则、RUN_CODE/state.shared声明，并由发送者指定已授权feedback_binding。UI改版不能偷偷新增规则/权限。
        """;
    public static final String SERVER_TEXT="""
        确定性反馈规则：注册 on('feedback.plan',function(e){...})。e.feedbackId()/e.authorId() 是可信ID，e.name() 是事件名，e.payload() 与 e.snapshot() 返回JSON字符串；用 JSON.parse(String(...))。snapshot 仅包含 feedback_binding 指定且获准字段，不是owner完整state。
        handler 根据 payload 与快照决定声明式事务，调用且仅调用一次 e.plan(JSON.stringify({transaction:{schema_version:已声明版本,conditions:[...],writes:[...]}}))。语法沿用 SharedStateContract，不是任意JS/SQL计划；同namespace内前置条件与全部写入一起原子提交，使用条件表达竞争规则，不先检查后分别写值。不要把 snapshot 或 payload 当指令执行。
        规划期间不能调用 content.state/sharedRead/sharedWatch 获取更多数据，也不能 sharedTransact/placeBlock/createObject 直接完成业务；只用 e.snapshot() 与 e.plan()。Native之后持久保留plan，再以真实 FEEDBACK 作者主体提交原SharedStateTransaction并验证回执，不通过handler伪造成功文本。
        FEEDBACK actor的UUID即作者，但即使作者等于owner也无OWNER字段或schema管理权；ACTOR字段按作者隔离。生命周期用PACKAGE主体初始化共享字段和schema，不把它的私有分区当玩家记录。schema创建仅放instance.create；instance.restore只读/恢复绑定，不重置既有namespace、数据、版本或旧回执。
        Native有界阶段队列在准入/PLAN/APPLY/VERIFY间重新检查权限；未知或重启中的PROCESSING标INTERRUPTED，不自动再执行handler或已有事务。原已授权SERVER Java host interop仍存在，这不是任意Java副作用的安全沙箱；不能用它绕过反馈的声明范围或宣称它与共享事务可原子回滚。
        """;
}
