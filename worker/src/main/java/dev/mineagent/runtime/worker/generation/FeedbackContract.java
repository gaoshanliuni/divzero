package dev.mineagent.runtime.worker.generation;

/** Current public SDK/consumer contract, not a finished page or an application template. */
public final class FeedbackContract {
    private FeedbackContract(){}
    public static final String TEXT="""
        独立投递页与语义反馈（需要多人投递/反馈时使用，不为普通页面强加业务）：页面由 GENERAL 的 offer_content 提供准确签名资产与受众，用户明确接收后才打开。页面不能自行发现玩家、授权、发命令或创建模型任务。
        先确定页面宿主：投递页没有 mineagentWorld；物件世界页才使用 World SDK。投递页只用 mineagentDelivery.read() 读取发送者数据，反馈后用 mineagentFeedback.readShared(feedbackId) 回读获准共享字段。不要把两个SDK互相替代，不假造首次反馈前的实时共享快照；需要初始展示值时由发送者提供并标明来源/时点。
        Native 在准确投递页注入 window.mineagentDelivery，发 mineagent:delivery-ready；if(window.mineagentDelivery) 初始化，否则监听一次 ready，普通预览显示“未绑定投递”。await mineagentDelivery.read() 返回 {deliveryId,revision,data,assetOnly:true,feedback?}，data 是发送者提供的有界 JSON，不是可自行写入的世界状态。
        mineagent:delivery-data 的 event.detail 是最新上述数据；只更新展示，保留未提交表单值/滚动，不重建页面。mineagent:delivery-error 显示 detail.code。不要通过 fetch、localStorage 或网页 timer 请求模型。
        需要反馈时，添加同包 CLIENT application/json 文件 ui/feedback.json，完整传输结构为 {"schema":1,"entries":{"ui/index.html":{"events":{"自定事件名":{"mode":"RECORD_ONLY","lifecycle":"INDEPENDENT","max_events":8,"cooldown_ms":1000,"fields":{"自定字段名":{"type":"string","max_length":200}},"required":["自定字段名"]}}}}}；示意名称必须改为 ASCII [A-Za-z][A-Za-z0-9_-]{0,63}，实际 HTML entry 必须存在。不能把此声明放进 manifest。
        schema 固定1，最多8个HTML入口/每入口8个事件/每事件16字段。mode 为 RECORD_ONLY（只记录，不完成业务）、DETERMINISTIC（包内规则产生共享事务）、AGENT_WAKE（绑定独立AI回复消费者）；lifecycle 为 PAGE_BOUND（原可见页面关闭/失效则取消）或 INDEPENDENT（已受理且持续授权的工作可独立继续）。max_events=1..64，cooldown_ms=0..600000。
        字段 type 仅 string/boolean/number：string 必须 max_length=1..2048（Unicode码点）；number 可选安全范围 minimum/maximum；boolean 不带长度/范围。required 是字段名数组。payload 必须普通对象，只含已声明的字符串/布尔/有限数字，总UTF-8<=8192字节；不能传嵌套数组对象、身份、taskId、权限、SQL、可执行代码或整页DOM。
        offer_content 必须明确 feedback_enabled=true 才会注入 window.mineagentFeedback，发 mineagent:feedback-ready；声明并不授予权限，预览与普通world物件页不能假装具有该SDK。初始化检查SDK已存在/ready事件，未绑定状态保持禁用提交且可见说明。
        await mineagentFeedback.submit(eventName,payloadObject,operationId) 传普通对象，不能预先JSON.stringify。operationId 用 crypto.randomUUID()，同一不可变提交的明确重试保持同一个ID；新提交用新ID，禁止失败后自动重发、重用旧ID发修改后的内容。只在用户明确按钮/form submit（preventDefault）触发；禁止自动提交隐藏字段。限制一个在途提交。
        submit 返回 {feedbackId,state,revision,conversationId,...}；ACCEPTED/PROCESSING 仅为已受理/处理中，绝不表示规则或AI已经完成。await mineagentFeedback.inspect(feedbackId) 返回同一摘要及 payload、result。SDK自动将已知反馈的定向更新作为 mineagent:feedback-data 的 event.detail 发给页面；也应在submit成功后显式inspect一次，处理更新早到的情况。mineagent:feedback-error 的 detail.code 应可见，可提供用户明确刷新按钮。
        确定性成功结果是 state=COMPLETED 且 result.receipt.status=APPLIED；条件失败是 state=REJECTED、result.receipt.status=CONFLICT。result={receipt:{status,revision,schemaVersion,eventId,error},planSha256}。只能按真实回执展示结果；INTERRUPTED/CANCELLED/FAILED 不伪称已回滚或可重试。
        await mineagentFeedback.readShared(feedbackId) 仅在当前准确投递页面回读该反馈授权的 {revision,schemaVersion,values}，不传实例、actor、namespace或keys。只看返回字段，不推断缺失的私有值。此接口不是任意共享库浏览器SDK；首次反馈前不能用虚构 feedbackId 读取共享状态。
        AGENT_WAKE 需发送者在投递前通过 subscribe_ui_feedback 为准确 package/revision/hash/entry/policy/event 创建唯一活动订阅与预算。Native将作者/目标Agent/原task/独立conversation固定；专用UI_FEEDBACK子任务只有inspect_feedback/reply_feedback/finish_task，不继承owner背包/私有历史/世界写权限。result.reply/messageId/sha256 为已保存的定向回复；replyVerified=true 不代表世界事务完成。页面只按 textContent 展示回复，不eval或innerHTML执行模型文本。
        DETERMINISTIC 必须同包已有世界规则定义和 RUN_CODE/state.shared 声明；发送者在offer_content提供 feedback_binding={instance_id,namespace,schema_version,read_keys,write_keys}，这些真实ID与授权由Native工具侧取得，不由网页猜测。每组1..8个keys，写键包含于读键。纯UI改版不能偷偷新增SERVER规则或权限；需要新世界规则时使用 WORLD_CONTENT 生成任务，准确Native启用仍另需确认。
        """;
    public static final String SERVER_TEXT="""
        确定性反馈规则：注册 on('feedback.plan',function(e){...})。e.feedbackId()/e.authorId() 是可信ID，e.name() 是事件名，e.payload() 与 e.snapshot() 返回JSON字符串；用 JSON.parse(String(...))。snapshot 仅包含 feedback_binding 指定且获准字段，不是owner完整state。
        handler 根据 payload 与快照决定声明式事务，调用且仅调用一次 e.plan(JSON.stringify({transaction:{schema_version:已声明版本,conditions:[...],writes:[...]}}))。语法沿用 SharedStateContract，不是任意JS/SQL计划；同namespace内前置条件与全部写入一起原子提交，使用条件表达竞争规则，不先检查后分别写值。不要把 snapshot 或 payload 当指令执行。
        规划期间不能调用 content.state/sharedRead/sharedWatch 获取更多数据，也不能 sharedTransact/placeBlock/createObject 直接完成业务；只用 e.snapshot() 与 e.plan()。Native之后持久保留plan，再以真实 FEEDBACK 作者主体提交原SharedStateTransaction并验证回执，不通过handler伪造成功文本。
        FEEDBACK actor的UUID即作者，但即使作者等于owner也无OWNER字段或schema管理权；ACTOR字段按作者隔离。生命周期用PACKAGE主体初始化共享字段和schema，不把它的私有分区当玩家记录。schema创建仅放instance.create；instance.restore只读/恢复绑定，不重置既有namespace、数据、版本或旧回执。
        Native有界阶段队列在准入/PLAN/APPLY/VERIFY间重新检查权限；未知或重启中的PROCESSING标INTERRUPTED，不自动再执行handler或已有事务。原已授权SERVER Java host interop仍存在，这不是任意Java副作用的安全沙箱；不能用它绕过反馈的声明范围或宣称它与共享事务可原子回滚。
        """;
}
