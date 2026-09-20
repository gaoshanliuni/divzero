package dev.mineagent.runtime.worker.generation;

public final class ScheduleDeliveryContract {
    private ScheduleDeliveryContract(){}
    public static final String TOOL_TEXT="""
        真正定时内容邀请选mode=CONTENT_DELIVERY，不是STATE_PUSH。先create_audience/inspect_audience得到本人Agent的真实audience_id/revision（SNAPSHOT固定成员，LIVE_AT_TRIGGER每个新occurrence解析当前成员），再inspect_content_contract确认本人发布包的实际入口。delivery:{audience_id,audience_revision,offer:{package_id,package_revision,canonical_sha256,entry_id,mode,data,ttl_seconds,feedback_enabled,feedback_binding}}，offer沿原offer_content规则，但不能填audience_snapshot_id；未来Runtime解析后填真实snapshot，不猜未来UUID。data是此定义固定JSON，不动态套用事件私有字段；mode为CONTENT/HUD，HUD必须真实HUD入口，feedback默认false，显式启用时沿原policy/绑定/权限验证。主调度goal为空、max_model_calls为0，无script/push，slot次数不退。
        每次到期持久创建真实scheduled_delivery子任务（没有plan/execute模型步骤），完成真实受众解析和原offer_content，随后只完成该Native邀请步骤，不自动调用Provider。DELIVERY_RECORDED仅邀请账本写入，DELIVERY_NO_RECIPIENTS明确无人，均不等于接收者接受/页面绘制/业务/人类已读。F2调度页或query_deliveries查看真实batch逐人状态。接收者必须从原收件箱明确接受；反馈若明确启用，后续真实用户提交可能按已批准AGENT_WAKE策略计费。
        新邀请要求当前SCHEDULE_TASKS+OFFER_CONTENT+DISCOVER_OBJECTS及Agent关系，反馈另需原授权。受众过期/撤销、目标包版本变化、权限改变阻止新投递，不自动延长受众TTL。取消/暂停只阻止尚未提交的新邀请；已经提交的邀请沿自己ttl_seconds与原投递撤回/关闭规则，需撤回请revoke_content，不谎称撤销所有已发内容。CONTENT/HUD签名发布资源不要求虚构世界物件或SERVER实例；只有DETERMINISTIC数据绑定需要真实批准实例。
        每occurrence受众解析/offer操作有稳定ID，未知结果/重启不自动重新解析或补发。原Task4096创建账本、受众和投递配额仍生效，满明确失败；不借已结束的旧Task或viewer权限。失败后可只读核查关联子任务既有邀请，没找到记录也不证明未执行，不自动补发。需要证明实际显示时仍按content_delivery的真实ASSET_PAINT/DATA_PAINT标准，不以调度结束或Task完成替代。
        """;
}
