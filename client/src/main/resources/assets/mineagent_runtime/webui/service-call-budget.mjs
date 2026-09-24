import {t as __uiT,tf as __uiF} from './i18n.mjs';
export const serviceBudgetBounds={
  'services.budget.taskAttempts':{min:0,max:1000000},
  'services.budget.dailyAttempts':{min:0,max:1000000},
  'services.budget.requestAttempts':{min:0,max:10000},
};
export const serviceBudgetErrors={
  SERVICE_BUDGET_TASK_CONTEXT:__uiT("实际 Task 身份或版本已变化，未开始新的调用"),
  SERVICE_BUDGET_TASK_UNRESOLVED:__uiT("旧 Task 的派生来源未知，不能按新的任务链限制获得额度"),
  SERVICE_BUDGET_TASK_LIMIT:__uiT("这条 Task 及其派生任务的累计调用额度已耗尽"),
  SERVICE_BUDGET_PAUSED:__uiT("管理员已暂停新智能服务调用"),
  SERVICE_BUDGET_DAILY_LIMIT:__uiT("全服 UTC 当日派发额度已用完"),
  SERVICE_BUDGET_REQUEST_LIMIT:__uiT("这个 Worker requestId 的派发额度已用完"),
  SERVICE_BUDGET_REPLAY:__uiT("此 requestId 已有派发或拒绝记录，不允许重放"),
  SERVICE_BUDGET_CAPACITY:__uiT("预算账本保留容量已满，未开始新的调用"),
  SERVICE_BUDGET_UNAVAILABLE:__uiT("预算账本不可用或前次回执未收束，未开始新的调用"),
  SERVICE_BUDGET_NOT_CONFIGURED:__uiT("Worker 预算账本尚未初始化"),
  SERVICE_BUDGET_CONFIG_INVALID:__uiT("已保存的预算配置无效，未开始新的调用"),
};
const categories={SEMANTIC:__uiT("文本 / 摘要"),PLANNING:__uiT("规划"),CODING:__uiT("代码 / Patch"),VISION:'Vision',EMBEDDING:'Embedding',IMAGE:__uiT("图像作业"),TTS:__uiT("语音合成")};
const count=v=>Number.isSafeInteger(v)&&v>=0?String(v):__uiT("未知");

/** Trusted-shell aggregate only: never requests a Worker RPC or exposes prompts/request ownership. */
export function renderServiceBudget(root,data,canView){
  root.replaceChildren();root.hidden=!canView;if(!canView)return;
  const add=(tag,text,cls)=>{const n=document.createElement(tag);n.textContent=text;if(cls)n.className=cls;root.append(n);return n;};
  add('h3',__uiT("智能服务用量快照"));
  add('p',__uiT("这里是同一 runtime 数据目录的全服聚合，不是个人额度。另有 Task 派生链累计限制，详情在任务历史查看。一次派发尝试不代表费用、token、HTTP 子请求数或业务成功。"),'muted');
  if(data?.state!=='SNAPSHOT'){
    add('p',data?.state==='NOT_INITIALIZED'?__uiT("预算账本尚未初始化；不能据此认定用量为零。"):__uiT("预算快照暂不可用；不会显示为零用量，也不会调用 Worker 探测。"),'warning');
  }else{
    add('p',__uiF("每条已知来源 Task 派生链累计上限：{0}。不按日重置，暂停/归档/重规划不清零。旧来源未知任务在启用该限制后被拒绝。",data.taskLimit===0?__uiT("不额外限制"):count(data.taskLimit)+__uiT(" 次")),'muted');
    const rows=Array.isArray(data.categories)?data.categories:[],used=rows.reduce((sum,r)=>sum+(Number.isSafeInteger(r.used)&&r.used>=0?r.used:0),0);
    add('p',__uiF("UTC {0} · 已保留 {1} / {2} 次 · {3} · 每 requestId {4}",data.day,count(used),data.dailyLimit===0?__uiT("不额外限制"):count(data.dailyLimit),data.paused?__uiT("新调用已暂停"):__uiT("未暂停"),data.requestLimit===0?__uiT("不额外限制"):count(data.requestLimit)+__uiT(" 次")),data.paused?'warning':'muted');
    if(data.clockBehind)add('p',__uiT("系统时钟落后于持久高水位：继续使用高水位所在 UTC 日期，回拨不会重新获得当日额度。"),'warning');
    const list=add('div','');list.className='service-budget-list';
    for(const [key,label] of Object.entries(categories)){
      const r=rows.find(r=>r.category===key)||{used:0,returned:0,failed:0,unknown:0,dispatching:0,denied:0};
      const row=document.createElement('p');row.textContent=__uiF("{0}：保留 {1} · 返回 {2} · 异常 {3} · 待收束 {4} · 中断未知 {5} · 拒绝 {6}",label,count(r.used),count(r.returned),count(r.failed),count(r.dispatching),count(r.unknown),count(r.denied));list.append(row);
    }
    if(data.lastRejection)add('p',__uiF("账本最近一次已保留拒绝（跨日期）：{0}。",serviceBudgetErrors[data.lastRejection]||__uiT("未知拒绝码")),'warning');
    add('p',__uiF("累计保留 requestId {0} / {1}，派发记录 {2} / {3}。不删除去重记录腾出额度。",count(data.retainedRequests),count(data.maxRequests),count(data.retainedAttempts),count(data.maxAttempts)),'muted');
    add('p',__uiF("快照时间：{0}。通过“重新读取”刷新；不代表 Worker 当前存活或实时计费。",Number.isSafeInteger(data.observedAt)?new Date(data.observedAt).toISOString():__uiT("未知")),'muted');
  }
  add('p',__uiT("保存只影响下一次额度保留；已保留的在途调用不取消、不退款。异常和未知仍占额度；返回只证明服务方法返回，不证明内容已发布或播放。0 不额外限制，不覆盖原 Task、事件或摘要限制。"),'muted');
  add('p',__uiT("本账本从启用时开始计量，不补造旧调用历史。ASR 按用户决定跳过，不计入此功能。账本故障只重试回执保存，不自动再次请求 Provider。"),'muted');
}

export function renderTaskBudget(root,data){
  root.replaceChildren();
  const add=(tag,text,cls)=>{const n=document.createElement(tag);n.textContent=text;if(cls)n.className=cls;root.append(n);return n;};
  add('h3',__uiT("Task 派生链累计用量"));
  const lineage=data?.lineage;
  if(lineage){
    add('p',__uiF("当前 Task：{0}",lineage.task),'muted');
    add('p',__uiF("直接来源：{0} · {1} · 层级 {2}",lineage.parent||__uiT("无已记录父任务"),lineage.relation,lineage.depth),'muted');
    add('p',__uiF("根任务：{0}",lineage.root||__uiT("旧来源未知，不能补猜")),'muted');
  }
  if(data?.state==='LEGACY_UNRESOLVED'){
    add('p',__uiT("这条旧任务没有可靠的创建时来源。已有全服调用记录保持，不能把它及其新派生任务当作拥有新额度的独立 root。启用任务链限制后会明确拒绝服务调用；此页不会迁移归属、清零或重新生成。"),'warning');return;
  }
  if(data?.state!=='SNAPSHOT'){add('p',__uiT("预算记录尚未初始化或暂不可读，不能据此认定用量为零。"),'warning');return;}
  const rows=Array.isArray(data.categories)?data.categories:[];
  const used=rows.reduce((n,r)=>n+(Number.isSafeInteger(r.used)&&r.used>=0?r.used:0),0);
  add('p',__uiF("此链累计保留 {0} / {1} 次 · 已记录 {2} 个 Task",count(used),data.limit===0?__uiT("不额外限制"):count(data.limit),count(data.total)),'muted');
  add('p',__uiT("所有已记录子任务共用根任务额度；状态变更、跨日、重规划和明确修复均不退款。只有明确新建的独立任务拥有独立预算，不自动拆任务绕过限制。"),'muted');
  for(const [key,label] of Object.entries(categories)){
    const r=rows.find(r=>r.category===key);if(!r)continue;
    add('p',__uiF("{0}：保留 {1} · 返回 {2} · 异常 {3} · 待收束 {4} · 未知 {5} · 拒绝 {6}",label,count(r.used),count(r.returned),count(r.failed),count(r.dispatching),count(r.unknown),count(r.denied)));
    if(r.lastCode)add('p',__uiF("该类最近已保存拒绝：{0}",serviceBudgetErrors[r.lastCode]||r.lastCode),'warning');
  }
  if(!rows.length)add('p',__uiT("这条有明确来源的任务链尚无本预算账本的服务保留记录。"),'muted');
  add('p',__uiT("这里只读本人持久来源与服务计数，不调用 Worker、不恢复任务。返回不代表内容已发布或业务成功；普通聊天/摘要/私密朗读没有 Task 来源，不强行归入最近任务。"),'muted');
}
