package dev.mineagent.runtime.worker.generation;

/** Read-only event-to-existing-view routing, not a new data publication or display permission. */
public final class StatePushContract {
    private StatePushContract(){}
    public static final String TOOL_TEXT="""
        纯刷新可明确选mode=STATE_PUSH：先inspect_state_push_targets发现自有已批准World UI目标，push对象必须package_id/instance_id/package_revision/canonical_sha256/entry_path/max_signals(1..4096)。目标只能当前合法实例的CLIENT ui/*.html；源为共享状态时必须同包同实例同版本，私有ACTOR变化还按各视图实际actor过滤。与script互斥，goal空/max_wakes与max_model_calls为0或省略。它只发不含原事件数据的refetch提示给已打开、RENDERED且SDK明确subscribe的视图；不打开/重开、抢焦点、恢复旧Session或转移数据权限。每个接收者按自己的原Session重新worldui.read，界面代码仅更新显示，保护输入草稿。没有可用视图为PUSH_NO_RECIPIENTS，不冒称送达；PUSH_READ_DELIVERED只证明受信任Native客户端把成功读取交给准确frame，不证明paint、页面handler应用或业务。看inspect_subscription的consumerState/pushDeliveries逐窗口结果，不以单人成功代替全体。重启/失效不补发旧窗口，新的页面subscribe初读取得当前状态。此适配器面向World UI；独立投递页仍走原delivery数据推送，不把这些回执互相替代。
        """;
}
