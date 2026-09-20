package dev.mineagent.runtime.worker.generation;

public final class SchedulePushContract {
    private SchedulePushContract(){}
    public static final String TOOL_TEXT="""
        也可选mode=STATE_PUSH：先inspect_schedule_push_targets复制准确自有ACTIVE包实例的CLIENT入口，push只含package_id/instance_id/package_revision/canonical_sha256/entry_path；不带script，goal为空/省略、max_model_calls=0/省略。不使用事件订阅的max_signals，仍按调度max_occurrences/slot预算(一次性1，周期1..32)，无窗口/失败/跳过不退次数。共享条件必须同包/同实例/同revision/hash，按实际接收actor可读字段过滤。到期仅冻结已打开RENDERED且页面mineagentWorld.subscribe opt-in的World UI窗口，最多32；不新建窗口、不重开隐藏页、不发模型、不调用runtime.schedule.*，不是内容邀请或offer_content。提示无源数据，原Session执行只读ui.read后有Native回调回执；PUSH_READ_DELIVERED不是paint/业务/人类已读。取消/撤权/重启不重建旧接收者集合；每窗口最多3次有期限读提示，去重与未知结果保留。
        """;
    public static final String TEXT="""
        已打开World UI还可由持久调度STATE_PUSH刷新：create_schedule使用inspect_schedule_push_targets提供的准确目标。页面仍需显式mineagentWorld.subscribe(onState,onError)，只更新显示，保留人工草稿/滚动/焦点；不另加网页轮询、不提交action、不猜Native push token。WALL/TICK到期提示是所有者明确配置的无载荷刷新，EVENT_CONDITION共享源仍按同实例与actor可读变化过滤；提示不提供事件原文或新权限。ui.read必须保持原只读契约。服务端按occurrence冻结现有窗口，无窗口即PUSH_NO_RECIPIENTS，不自动打开/邀请，下一次新slot才可选择新窗口。Native READ_DELIVERED只证明成功读结果交给准确frame回调，不证明页面已应用或绘制；别将其当作offer_content、SCRIPT成功、业务确认或人类已读。
        """;
}
