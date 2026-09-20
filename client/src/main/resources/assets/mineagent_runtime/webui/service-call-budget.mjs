export const serviceBudgetBounds={
  'services.budget.taskAttempts':{min:0,max:1000000},
  'services.budget.dailyAttempts':{min:0,max:1000000},
  'services.budget.requestAttempts':{min:0,max:10000},
};
export const serviceBudgetErrors={
  SERVICE_BUDGET_TASK_CONTEXT:'实际 Task 身份或版本已变化，未开始新的调用',
  SERVICE_BUDGET_TASK_UNRESOLVED:'旧 Task 的派生来源未知，不能按新的任务链限制获得额度',
  SERVICE_BUDGET_TASK_LIMIT:'这条 Task 及其派生任务的累计调用额度已耗尽',
  SERVICE_BUDGET_PAUSED:'管理员已暂停新智能服务调用',
  SERVICE_BUDGET_DAILY_LIMIT:'全服 UTC 当日派发额度已用完',
  SERVICE_BUDGET_REQUEST_LIMIT:'这个 Worker requestId 的派发额度已用完',
  SERVICE_BUDGET_REPLAY:'此 requestId 已有派发或拒绝记录，不允许重放',
  SERVICE_BUDGET_CAPACITY:'预算账本保留容量已满，未开始新的调用',
  SERVICE_BUDGET_UNAVAILABLE:'预算账本不可用或前次回执未收束，未开始新的调用',
  SERVICE_BUDGET_NOT_CONFIGURED:'Worker 预算账本尚未初始化',
  SERVICE_BUDGET_CONFIG_INVALID:'已保存的预算配置无效，未开始新的调用',
};
const categories={SEMANTIC:'文本 / 摘要',PLANNING:'规划',CODING:'代码 / Patch',VISION:'Vision',EMBEDDING:'Embedding',IMAGE:'图像作业',TTS:'语音合成'};
const count=v=>Number.isSafeInteger(v)&&v>=0?String(v):'未知';

/** Trusted-shell aggregate only: never requests a Worker RPC or exposes prompts/request ownership. */
export function renderServiceBudget(root,data,canView){
  root.replaceChildren();root.hidden=!canView;if(!canView)return;
  const add=(tag,text,cls)=>{const n=document.createElement(tag);n.textContent=text;if(cls)n.className=cls;root.append(n);return n;};
  add('h3','智能服务用量快照');
  add('p','这里是同一 runtime 数据目录的全服聚合，不是个人额度。另有 Task 派生链累计限制，详情在任务历史查看。一次派发尝试不代表费用、token、HTTP 子请求数或业务成功。','muted');
  if(data?.state!=='SNAPSHOT'){
    add('p',data?.state==='NOT_INITIALIZED'?'预算账本尚未初始化；不能据此认定用量为零。':'预算快照暂不可用；不会显示为零用量，也不会调用 Worker 探测。','warning');
  }else{
    add('p',`每条已知来源 Task 派生链累计上限：${data.taskLimit===0?'不额外限制':count(data.taskLimit)+' 次'}。不按日重置，暂停/归档/重规划不清零。旧来源未知任务在启用该限制后被拒绝。`,'muted');
    const rows=Array.isArray(data.categories)?data.categories:[],used=rows.reduce((sum,r)=>sum+(Number.isSafeInteger(r.used)&&r.used>=0?r.used:0),0);
    add('p',`UTC ${data.day} · 已保留 ${count(used)} / ${data.dailyLimit===0?'不额外限制':count(data.dailyLimit)} 次 · ${data.paused?'新调用已暂停':'未暂停'} · 每 requestId ${data.requestLimit===0?'不额外限制':count(data.requestLimit)+' 次'}`,data.paused?'warning':'muted');
    if(data.clockBehind)add('p','系统时钟落后于持久高水位：继续使用高水位所在 UTC 日期，回拨不会重新获得当日额度。','warning');
    const list=add('div','');list.className='service-budget-list';
    for(const [key,label] of Object.entries(categories)){
      const r=rows.find(r=>r.category===key)||{used:0,returned:0,failed:0,unknown:0,dispatching:0,denied:0};
      const row=document.createElement('p');row.textContent=`${label}：保留 ${count(r.used)} · 返回 ${count(r.returned)} · 异常 ${count(r.failed)} · 待收束 ${count(r.dispatching)} · 中断未知 ${count(r.unknown)} · 拒绝 ${count(r.denied)}`;list.append(row);
    }
    if(data.lastRejection)add('p',`账本最近一次已保留拒绝（跨日期）：${serviceBudgetErrors[data.lastRejection]||'未知拒绝码'}。`,'warning');
    add('p',`累计保留 requestId ${count(data.retainedRequests)} / ${count(data.maxRequests)}，派发记录 ${count(data.retainedAttempts)} / ${count(data.maxAttempts)}。不删除去重记录腾出额度。`,'muted');
    add('p',`快照时间：${Number.isSafeInteger(data.observedAt)?new Date(data.observedAt).toISOString():'未知'}。通过“重新读取”刷新；不代表 Worker 当前存活或实时计费。`,'muted');
  }
  add('p','保存只影响下一次额度保留；已保留的在途调用不取消、不退款。异常和未知仍占额度；返回只证明服务方法返回，不证明内容已发布或播放。0 不额外限制，不覆盖原 Task、事件或摘要限制。','muted');
  add('p','本账本从启用时开始计量，不补造旧调用历史。ASR 按用户决定跳过，不计入此功能。账本故障只重试回执保存，不自动再次请求 Provider。','muted');
}

export function renderTaskBudget(root,data){
  root.replaceChildren();
  const add=(tag,text,cls)=>{const n=document.createElement(tag);n.textContent=text;if(cls)n.className=cls;root.append(n);return n;};
  add('h3','Task 派生链累计用量');
  const lineage=data?.lineage;
  if(lineage){
    add('p',`当前 Task：${lineage.task}`,'muted');
    add('p',`直接来源：${lineage.parent||'无已记录父任务'} · ${lineage.relation} · 层级 ${lineage.depth}`,'muted');
    add('p',`根任务：${lineage.root||'旧来源未知，不能补猜'}`,'muted');
  }
  if(data?.state==='LEGACY_UNRESOLVED'){
    add('p','这条旧任务没有可靠的创建时来源。已有全服调用记录保持，不能把它及其新派生任务当作拥有新额度的独立 root。启用任务链限制后会明确拒绝服务调用；此页不会迁移归属、清零或重新生成。','warning');return;
  }
  if(data?.state!=='SNAPSHOT'){add('p','预算记录尚未初始化或暂不可读，不能据此认定用量为零。','warning');return;}
  const rows=Array.isArray(data.categories)?data.categories:[];
  const used=rows.reduce((n,r)=>n+(Number.isSafeInteger(r.used)&&r.used>=0?r.used:0),0);
  add('p',`此链累计保留 ${count(used)} / ${data.limit===0?'不额外限制':count(data.limit)} 次 · 已记录 ${count(data.total)} 个 Task`,'muted');
  add('p','所有已记录子任务共用根任务额度；状态变更、跨日、重规划和明确修复均不退款。只有明确新建的独立任务拥有独立预算，不自动拆任务绕过限制。','muted');
  for(const [key,label] of Object.entries(categories)){
    const r=rows.find(r=>r.category===key);if(!r)continue;
    add('p',`${label}：保留 ${count(r.used)} · 返回 ${count(r.returned)} · 异常 ${count(r.failed)} · 待收束 ${count(r.dispatching)} · 未知 ${count(r.unknown)} · 拒绝 ${count(r.denied)}`);
    if(r.lastCode)add('p',`该类最近已保存拒绝：${serviceBudgetErrors[r.lastCode]||r.lastCode}`,'warning');
  }
  if(!rows.length)add('p','这条有明确来源的任务链尚无本预算账本的服务保留记录。','muted');
  add('p','这里只读本人持久来源与服务计数，不调用 Worker、不恢复任务。返回不代表内容已发布或业务成功；普通聊天/摘要/私密朗读没有 Task 来源，不强行归入最近任务。','muted');
}
