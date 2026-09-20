package dev.mineagent.runtime.worker.generation;

public final class ScriptScheduleContract {
    private ScriptScheduleContract(){}
    public static final String TOOL_TEXT="""
        也支持确定性mode=SCRIPT：先inspect_schedule_handlers发现本人已批准ACTIVE实例的runtime.schedule.*名称，再复制script:{package_id,instance_id,package_revision,canonical_sha256,handler}；只SCRIPT带script，goal为空/省略、max_model_calls=0/省略，不额外发模型。max_occurrences就是含跳过slot的总预算(1..32)，次数不退；不是runtime.event.*，不能传任意JS或借Task/玩家身份。目标还需当前RUN_CODE+MANAGE_PACKAGES，按原墙上/tick/条件及SKIP/COALESCE规则，纯等待不轮询模型。每tick最多一个调度handler，整组25ms预算；结果看inspect_schedule，SCRIPT_HANDLED只是明确处理回执，不证明世界业务/页面送达，finish_task仍回读真实业务。停用/换版本/撤权暂停并取消旧工作；准确已批准自动恢复资源可等待，但旧SCRIPT_DISPATCHING或重启未知调用不重放。持久PACKAGE脚本不是受模型约束的任意Java强制沙箱。
        """;
    public static final String TEXT="""
        持久调度脚本（需要确定性时间/条件动作时使用）：在SERVER模块注册on('runtime.schedule.name',function(schedule){...})。名称为runtime.schedule.加字母开头的1..64个[A-Za-z0-9_.:-]字符；此handler通过inspect_schedule_handlers发现，再由create_schedule以SCRIPT模式绑定当前已批准package/instance/revision/hash。不伪造玩家事件或Task，不调用instance.create/restore作调度消费者。max_occurrences限制计划slot，取消/失败/跳过不退款；周期仍需明确SKIP或COALESCE。
        schedule.occurrenceId()/schedule.scheduleId()提供真实持久身份；schedule.json()返回scheduleId/occurrenceId/slot/definitionRevision/kind/due/recordedAt/timezone/conditionEventId/currentUtc/gameTicks。WALL due为UTC epoch毫秒，TICK due为overworld game tick，EVENT_CONDITION due是记录条件的墙上时刻；不是当前条件值/玩家信息。没有事件正文、授权、actor或可伪造事件API。
        同一个同步回调内明确调用一次schedule.complete(JSON.stringify(实际结果))，否则SCRIPT_INTERRUPTED；不返回Promise，不延后complete。结果最多8192 UTF-8字节/16层/2048节点，仅是包声明，不是业务成功证明。所有同名handler共享25ms正常预算，不提升为生命周期200ms。payload方法仅回调存活且权限有效时可用，回调结束后失效；finally恢复Host上下文。
        真实world/shared操作仍走原content API和PACKAGE主体，不借viewer。sharedTransact幂等根使用occurrenceId，原事务/CAS/当前权限保持；共享来源携带独立scheduleOccurrenceId/scheduleDefinitionId，条件调度还继承原订阅因果链以抑制自触发。没有伪造Task或事件subscriptionId。失败可能已有副作用，未知结果/重启不重放；查询历史后另行明确处理，不靠timer把失败回调再跑一次。
        """;
}
