package dev.mineagent.runtime.worker.generation;
/** Current native client UI-state API, not a gameplay authority or browser localStorage shim. */
final class UiStateContract {
    private UiStateContract(){}
    static final String TEXT="""
            可用的客户端 UI 状态 API：window.mineagentState，由 Native 在页面 load 后注入并派发 mineagent:state-ready。
            它按服务器/世界/真实玩家/包/入口/业务 target 隔离，跨代码 revision 保留同一 key；不是世界权威状态，不存 Provider Key。
            初始化：若 window.mineagentState 已存在就使用，否则监听 mineagent:state-ready（once:true）。启动 async init() 但不要在模块顶层 await 等待注入，否则可能阻塞 load。
            await window.mineagentState.get(key) 返回 {revision,exists,value,packageRevision}。key 只允许 1..64 个字母/数字/下划线/点/短横线，不含 '..'。
            await window.mineagentState.put(key,expectedRevision,value) 返回同形 Snapshot，只有成功才更新 UI 与本地 revision；value 是普通 JSON 值，不是 JSON 字符串，单值最多 32 KiB。
            await window.mineagentState.remove(key,expectedRevision) 删除 key，但 revision 仍递增。revision 属于当前 UI scope，不是包 revision。
            同时打开多个窗口时用上次 get/put 返回的 revision 作 CAS。UI_STATE_CONFLICT、权限/过期或其他错误必须显示，不自动重放写入；提供重新读取按钮，保留尚未提交的人工输入。
            读取失败不能当作空数据再覆盖；只有 exists=false 才能创建新状态。候选或旧包版本的写入会被拒绝；普通当前发布页可持久化本地 UI 数据。
            UI 写入需 await 完成，再显示已保存、更新列表或清空表单；不要直接修改 state 后捕获异常却继续展示成功。
            """;
}
