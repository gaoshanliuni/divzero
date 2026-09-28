package dev.mineagent.runtime.worker.generation;
/** Scoped local-state calls exposed as declarative native requests, never gameplay authority. */
final class UiStateContract {
    private UiStateContract(){}
    static final String TEXT="""
        原生本地状态通过 reads/actions 的 state.get/state.put/state.remove 使用，按服务器/世界/真实玩家/包/入口/业务target隔离；只存界面数据，不存Provider Key或伪造世界状态。
        state.get arguments={key:"draft"} 返回 data={revision,exists,value,packageRevision}，读取失败不能当作空值；exists=false才可新建。
        state.put arguments={key:"draft",expectedRevision:上次get的revision表达式,valueJson:有界数据表达式}；state.remove arguments={key,expectedRevision}。只在明确交互触发，成功返回后显示已保存。
        同时打开多个窗口时使用真实CAS版本；UI_STATE_CONFLICT显示错误、保留输入、明确重新读取，不自动覆盖。
        预览和HUD不能写入；版本、权限和文档仍需有效。一般表单草稿自动按稳定输入ID恢复，不通过全局reload。
        """;
}
