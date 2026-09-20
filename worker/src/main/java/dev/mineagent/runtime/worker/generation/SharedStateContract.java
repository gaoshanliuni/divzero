package dev.mineagent.runtime.worker.generation;

/** General transaction grammar, not a prebuilt application or a browser-side authority shortcut. */
public final class SharedStateContract {
    private SharedStateContract(){}
    public static final String TEXT="""
        服务端共享状态（多人规则需要时使用，独立于 content.state 和客户端 mineagentState 草稿）：manifest.permissions 明确加入 state.shared，同时保留 RUN_CODE。
        content.sharedDefine(namespace,operationKey,expectedRevision,schemaJson) 只能在包生命周期管理上下文执行，不可在 ui.read/ui.action/object.interact 中创建或迁移 schema；相同 schema 重复声明不清数据。初始 expectedRevision=0/version=1；迁移需要准确当前 revision、schema version+1，现有数据必须满足新类型/边界，不隐式丢字段/改变ACTOR分区。
        sharedDefine 返回 JSON 回执，必须检查 status=APPLIED。新 schema 创建成功后 revision 已经是 1，不是 0；后续数据初始化若使用 expected_revision，必须取实际回执/读取的 revision，不能沿用创建前的0。也可省略全局 revision，使用明确 ABSENT/PUT_IF_ABSENT 条件初始化；同样检查实际回执，失败不得继续宣称应用已初始化。
        已有实例字段改名/删除/补默认值使用显式结构迁移，不重新创建实例或清空 namespace：content.sharedDescribe(namespace) 读取当前授权字段/schemaVersion/revision；content.sharedMigrationPlan(namespace,expectedRevision,newSchemaJson,migrationJson) 在包管理生命周期中预览条数（不应用拟议迁移，但已有到期数据会独立清理），不返回私有数据/actor ID；content.sharedMigrate(namespace,operationKey,expectedRevision,newSchemaJson,migrationJson) 提交。三者返回JSON字符串。
        migrationJson 严格格式为 {"from_schema_version":1,"confirm_drop":false,"steps":[{"op":"RENAME","key":"oldName","to":"newName"}]}，newSchema.version 必须恰好 from_schema_version+1。步骤按顺序执行，最多64步；RENAME保留每条记录的SHARED/ACTOR分区，目标已存在则拒绝，不合并；DROP用{"op":"DROP","key":"oldName"}且confirm_drop必须true，不得删除用户未要求删除的数据；DEFAULT_SHARED用{"op":"DEFAULT_SHARED","key":"newFlag","value":false}仅补缺失SHARED值；DEFAULT_ACTORS仅给源快照中已经存在的ACTOR UUID补缺失值，不创建未来玩家分区，不允许自报subject。默认值从不覆盖已有值；带TTL的新默认值从实际迁移提交的服务端有效时间开始计时。RENAME不重置已有期限。
        在create/restore或明确包升级生命周期中先检查实际schemaVersion与字段定义；若已是目标schema，不再创建新迁移operation。plan.targetSchemaMatches只证明当前schema与目标一致，不证明本次迁移发生或业务已完成。需要迁移时使用包含from/to版本和本次预期revision的稳定operationKey，检查plan与迁移status，APPLIED后才使用新字段或继续依赖新格式的世界动作。迁移参数/权限/revision/schema/配额失败不应用拟议转换；已经独立提交的TTL到期删除不回滚，不得catch后继续假装升级成功；未知结果先读实际版本，并用content.sharedReceipt(namespace,operationKey)查询同一管理绑定/包版本下的既有回执，不随机换operationKey重做。已明确CONFLICT后若要按新revision提交，需重新核对计划并用新的操作键；冲突回执也不可改参数重用。直接ui.read/ui.action/Agent/反馈上下文不能管理schema或读取跨actor迁移计划。
        结构迁移的数据/schema/revision/幂等回执/可靠schema通知是同一SQLite事务。它不让整个内容包升级、其它namespace、content.state或原生世界操作变成一个原子事务，也不自动回滚已成功提交的迁移。旧数据必须满足目标字段类型和边界，不执行任意JS/SQL转换，不把ACTOR记录变成SHARED。
        schemaJson 是严格 JSON 字符串：{"version":1,"fields":{"fieldName":{"type":"STRING","scope":"ACTOR","read":"PARTICIPANT","write":"PARTICIPANT","max_bytes":8192}}}。type 支持 STRING/INTEGER/NUMBER/BOOLEAN/OBJECT/ARRAY/JSON；INTEGER/NUMBER 可声明 minimum/maximum，必须是安全整数范围内边界。
        字段可选 ttl_seconds 是0..31536000整数，省略或0表示永久；仅包管理schema可声明，不允许网页指定subject或expiresAt。PUT/PUT_IF_ABSENT/ADD成功时续期为服务端有效时间加TTL，DELETE清期限；同operation重放只返回旧回执，不续期、不复活已到期值。业务需要永久保存的资料不要设置TTL。
        已有字段TTL变化必须在version+1的显式迁移中加入{"op":"RESET_TTL","key":"fieldName"}，它只对当前仍存在的值按目标ttl_seconds重设期限（0转永久）；普通sharedDefine拒绝已有字段TTL改变。改名且修改TTL时先RENAME再RESET_TTL目标名；未变化TTL仅改名会保留期限。到期清理先于迁移，不复活已到期旧值；显式DEFAULT步骤可创建新值而不是恢复原值。预览resetTtlRecords仅是重设次数，不是原值或最终到期时间；计划不提交RESET_TTL，真正提交时取新时间。
        sharedRead/sharedWatch/sharedTransact/sharedDefine/sharedMigrationPlan/sharedMigrate在读取/条件评估前追赶本namespace已到期值，删除/revision/到期索引/可靠事件独立提交；之后再执行调用者请求。到期可以使刚读过的expected_revision产生CONFLICT，这不是部分应用请求。清理因配额/存储失败时明确失败，不返回过期值、不跳过可靠事件；不自动重试调用者操作。sharedDescribe只读授权schema与当前已存revision元数据，不承诺已清理TTL；sharedReceipt是历史结果，不证明值现在仍存在。
        Native每20个server tick有界扫描4个到期namespace，事件每批最多64条Change，SYSTEM_EXPIRY是内部清理主体，不冒充玩家/包或开放给调用者。停用包不取消已声明期限，重启后数据读仍先清理；通知只有可读keys与refetch，ACTOR分区不跨主体泄露。普通读取/清理不直接请求模型，只有既有显式授权且预算未耗尽的事件订阅可沿原规则唤醒Agent。
        expiresAt是服务端持久有效时间的epoch毫秒（只随可读且带期限的值返回），不是浏览器自行可写的时间。时钟高水位持久化，运行中加monotonic elapsed抵御回拨；重启且系统时间回拨时无法证明停机实际时长，不承诺异常时钟下准确倒计时或每秒准点清理。
        后台包生命周期/定时回调使用独立 PACKAGE 服务主体，不冒充人类 owner，不能默认访问先前UI actor的私有分区。
        scope 默认 ACTOR：记录由当前真实 actor UUID 分区，网页或请求不能自报 subject；SHARED 是实例内同一字段。read/write 默认 PARTICIPANT（当前确已准入的上下文），或 OWNER；OWNER 不表示任意OP。私密字段默认ACTOR，不把玩家列表/私密表单无条件放进SHARED。
        content.sharedRead(namespace) 返回 JSON 字符串 {revision,schemaVersion,values,expiresAt?}，只包含当前实际 actor 可读的记录。content.sharedWatch(namespace,afterRevision) 返回持久通知 {revision,cursor,more,events:[{eventId,revision,keys,snapshotRequired:true}]}，每页16事件；没有字段值/其他actor身份，收到通知应重新 sharedRead，不重新生成网页；schema/权限变化可能keys为空，仍必须refetch并清除已不可读的旧缓存。保存cursor，more为true可有界继续；普通读取不请求模型。
        content.sharedTransact(namespace,operationKey,transactionJson) 参数为严格 JSON 字符串：{schema_version:1,expected_revision:当前共享revision,conditions:[{key:'fieldName',test:'ABSENT'}],writes:[{key:'fieldName',op:'PUT_IF_ABSENT',value:数据}]}（实际JSON使用双引号）。expected_revision可省略使用显式条件事务；同一namespace内最多16条件/16不同key写入。
        条件 test 支持 ABSENT/EXISTS（不带value）、EQ/LT/LTE/GT/GTE（带value）。写入op支持 PUT/PUT_IF_ABSENT/ADD（带value）或DELETE（不带value）；ADD仅作用于已存在INTEGER，受schema上下界限制。前置条件失败完全不写状态，返回 {status:'CONFLICT',revision,schemaVersion,eventId:null,error}；成功status='APPLIED'。必须检查status，不把RPC送达当业务完成。
        条件、全部写入、幂等结果和可靠通知在同一SQLite事务。没有任意JS/SQL参数，不能宣称与 content.state、原生物品/方块修改或其它namespace一起原子提交。多人计数/登记规则应放在同一个sharedTransact，不先读取后分别写两个键。
        operationKey为1..64的[A-Za-z0-9_.:-]字符串；Native将其绑定当前UI operationId/namespace/实例，重复同键必须同参数；生命周期调用以activationId为根，周期任务须使用明确不同且持久的操作键，不自动重试未知写入。ui.read中的任何shared写入拒绝，旧包/实例/窗口权限也不能因重发而恢复。
        每namespace最多64字段/256记录/256KiB、单值<=8KiB、单事务/迁移描述<=32KiB、world最多256namespace。操作/通知各表运行区最多8192行，热区满时有界逻辑归档已完成操作或已按hash确认导出的通知；含归档的每表每world总保留最多131072行，不是无限容量，也不是字节硬配额。归档不删除或重写原payload、不回收磁盘；旧operation仍去重、watch仍可分页读取历史，未确认导出的通知不得归档。达到总量或无可归档热区额度时明确背压；TTL仅清理业务字段值，不删除幂等回执或outbox腾空间。任意值转换与跨namespace迁移仍未实现。GENERAL现可通过显式授权的共享工具与subscribe_shared_state消费可靠通知；独立投递页的限定反馈SDK详见反馈契约，不虚构任意浏览器共享状态写接口。
        """;
}
