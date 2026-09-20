import {OperationDraft} from './agent-management-state.mjs';

const kinds={WALL_ONCE:'现实时间 · 一次',WALL_PERIODIC:'现实时间 · 固定间隔',TICK_ONCE:'游戏 tick · 一次',TICK_PERIODIC:'游戏 tick · 周期',EVENT_CONDITION:'等待事件条件'};
const states={ACTIVE:'活动',PAUSED:'暂停',CANCELLED:'已取消',EXPIRED:'已过期',FINISHED:'时间槽已耗尽',RECORDED:'仅记录',QUEUED:'等待创建任务',CLAIMED:'已领取',DISPATCHED:'任务已创建',COMPLETED:'任务完成',FAILED:'任务失败',INTERRUPTED:'中断／结果未知',MODEL_BUDGET_EXHAUSTED:'规划次数已耗尽'};
Object.assign(states,{SCRIPT_QUEUED:'脚本排队',SCRIPT_DISPATCHING:'脚本调用前标记（非存活证明）',SCRIPT_HANDLED:'脚本已处理（非业务验证）',SCRIPT_CANCELLED:'脚本已取消',SCRIPT_INTERRUPTED:'脚本中断／结果未知'});
Object.assign(states,{PUSH_QUEUED:'推送排队',PUSH_DISPATCHING:'准备窗口快照',PUSH_WAITING:'等待读取回执',PUSH_NO_RECIPIENTS:'当时没有可用窗口',PUSH_READ_DELIVERED:'全部读取已交付（非绘制）',PUSH_PARTIAL_OR_FAILED:'推送部分失败',PUSH_INTERRUPTED:'推送中断'});
Object.assign(states,{DELIVERY_QUEUED:'邀请排队',DELIVERY_DISPATCHING:'邀请调用前标记',DELIVERY_RECORDED:'邀请已记账（不是显示）',DELIVERY_NO_RECIPIENTS:'没有解析到接收者',DELIVERY_CANCELLED:'邀请已取消',DELIVERY_INTERRUPTED:'邀请中断／结果未知'});
const errors={SCHEDULE_STALE:'定义已经变化或已终止。请重新读取，不重放旧工作。',SCHEDULE_EXPIRED:'定义已过期，不能通过恢复延长期限。',SCHEDULE_EXHAUSTED:'时间槽已经耗尽。恢复不会重置次数，需明确创建新定义。',SCHEDULE_MISSING:'调度不存在。',SCHEDULE_OPERATION_REUSED:'这个操作 ID 已用于不同请求。',SCHEDULE_STORAGE_BUDGET:'运行区配额已满且无安全归档空间。',RETENTION_TOTAL_ROW_BUDGET:'每世界对应表的总保留已达 131072 行；归档不能释放总量或磁盘。',SCHEDULE_ARCHIVE_REQUIRES_STOPPED:'仅可归档终态或已过期定义；可恢复的暂停项须先明确取消。',SCHEDULE_RESUME_AUTHORITY_REQUIRED:'当前 Agent、调度权限或准确的条件订阅不可用，不能恢复。',SCHEDULE_CONDITION_UNAVAILABLE:'条件订阅已失效或 revision 已变化；不会自动改绑到新条件。',FORBIDDEN:'不能管理此对象。'};
const label=value=>states[value]||value||'—';
function stamp(value,zone='UTC'){
  const date=new Date(Number(value));if(!Number.isFinite(date.getTime()))return '超出可显示日期范围';
  try{return `${new Intl.DateTimeFormat('zh-CN',{timeZone:zone,dateStyle:'medium',timeStyle:'medium',hour12:false}).format(date)} (${zone})`;}catch{return date.toISOString();}
}
export function createScheduleManagement({windowFor,send,openTasks,openEvents}){
  let deliveryView=null,deliverySerial=0;
  let root=null,ready=false,scope='',epoch=0,listSerial=0,detailSerial=0,listing=false,detailing=false,writing=false;
  let listOffset=0,historyOffset=0,selected=null,lastList=null,lastDetail=null,choice=null;
  const operation=new OperationDraft();
  const el=(tag,text,parent,cls)=>{const node=document.createElement(tag);if(text!=null)node.textContent=text;if(cls)node.className=cls;parent?.append(node);return node;};
  const button=(text,parent,action,allowed=true)=>{const node=el('button',text,parent);node.type='button';node.dataset.scheduleAllowed=String(allowed);node.onclick=()=>{if(ready&&!writing)action();};return node;};
  const query=selector=>root.querySelector(selector);
  function visible(){const host=root?.closest('.window');return ready&&root?.isConnected&&document.body.dataset.workspaceVisible!=='false'&&host&&getComputedStyle(host).display!=='none';}
  function status(text,bad=false){if(!root)return;const node=query('#schedule-status');if(node){node.textContent=text;node.className=bad?'error':'muted';}}
  function updateButtons(){if(!root)return;root.querySelectorAll('button[data-schedule-allowed]').forEach(node=>{node.disabled=!ready||writing||node.dataset.scheduleAllowed!=='true';});const filter=query('#schedule-state-filter');if(filter)filter.disabled=!ready||writing;}
  function errorText(error){return errors[error.message]||String(error.message||error);}
  async function read(args){const receipt=await send('scheduleManagement',args);if(receipt.values?.errorCode||receipt.code!=='OBSERVED')throw new Error(receipt.values?.errorCode||receipt.code||'SCHEDULE_READ_FAILED');return JSON.parse(receipt.values.state);}
  function details(title,value,parent){const node=el('details',null,parent);el('summary',title,node);el('pre',JSON.stringify(value,null,2),node,'event-json');}
  function clock(value){if(!value)return;query('#schedule-clock').textContent=`服务端采样：${value.utc} · overworld tick ${value.gameTicks}。Tick 不是现实时间，停机不会按墙上时间补 tick。`;}
  function renderList(){
    const list=query('#schedule-list');list.replaceChildren();clock(lastList?.clock);const usage=lastList?.retention;
    query('#schedule-retention').textContent=usage?`本人全部定义：运行区 ${usage.hot} · 已归档 ${usage.archived} · 共 ${usage.total}`:'等待读取本人保留统计…';
    if(!lastList?.items.length)el('p','没有符合筛选的本人调度。可前往任务，用自然语言明确时间或条件及次数预算；此管理页不会请求模型。',list,'muted');
    for(const d of lastList?.items||[]){const card=el('article',null,list,'event-card');card.setAttribute('aria-current',String(d.id===selected));el('strong',`${d.agentName} · ${kinds[d.kind]||d.kind}`,card);el('p',`${label(d.state)} · ${d.mode==='AGENT_WAKE'?'AI 唤醒':d.mode==='SCRIPT'?'脚本处理':d.mode==='STATE_PUSH'?'只读状态推送':d.mode==='CONTENT_DELIVERY'?'内容邀请':'仅记录'} · ${d.archived?'已归档':'运行区'}`,card);if(d.goal)el('p',d.goal,card);if(d.mode==='SCRIPT')el('p',`目标状态：${d.sourceState} · ${d.scriptReceiptPending?'等待持久结果回执':'无待写结果'}`,card,'muted');
      el('p',`已消费时间槽 ${d.consumedSlots} / ${d.maximumSlots}，其中跳过 ${d.skipped}；不代表业务成功。`,card,'muted');el('p',`r${d.revision} · 到期 ${stamp(d.expires,d.zone)}`,card,'muted');button('查看与管理',card,()=>select(d.id));
    }
    const paging=query('#schedule-list-paging');paging.replaceChildren();button('上一页',paging,()=>{listOffset=Math.max(0,listOffset-16);void loadList();},listOffset>0&&!listing);el('span',lastList?`${lastList.total} 个匹配定义 · 当前 ${lastList.items.length?listOffset+1:0}–${listOffset+lastList.items.length}`:'准备读取…',paging,'muted');button('下一页',paging,()=>{listOffset=lastList.nextOffset;void loadList();},!!lastList?.more&&!listing);updateButtons();
  }
  function select(id){deliveryView=null;deliverySerial++;selected=id;historyOffset=0;choice=null;operation.reset();lastDetail=null;renderDetail();renderList();void loadDetail();}
  async function loadList(){if(!ready||writing)return;const serial=++listSerial,generation=epoch,current=root;listing=true;
    try{const data=await read({kind:'list',state:query('#schedule-state-filter').value,offset:String(listOffset)});if(generation!==epoch||serial!==listSerial||current!==root||!root?.isConnected)return;lastList=data;listing=false;renderList();}
    catch(error){if(generation===epoch&&serial===listSerial)status(errorText(error),true);}finally{if(generation===epoch&&serial===listSerial){listing=false;updateButtons();}}
  }
  function renderDetail(){
    const pane=query('#schedule-detail');pane.replaceChildren();if(!lastDetail||lastDetail.definition.id!==selected){el('p',selected?'正在读取这个调度…':'选择一个调度查看真实时间、预算与每次执行记录。',pane,'muted');return;}
    const d=lastDetail.definition;el('h3',`${d.agentName} · ${kinds[d.kind]}`,pane);el('p',`${label(d.state)} · r${d.revision} · ${d.id}`,pane,'muted');
    const actions=el('div',null,pane,'actions');for(const [action,text,allowed] of [['PAUSED','暂停…',d.canPause],['CANCELLED','取消…',d.canCancel],['ACTIVE','恢复…',d.canResume],['ARCHIVE','归档…',lastDetail.retention.canArchive]])button(text,actions,()=>confirm(action),allowed);
    button('重新读取',actions,()=>{choice=null;operation.reset();void loadDetail();});const panel=el('section',null,pane,'event-confirm');panel.id='schedule-confirm';panel.hidden=true;
    el('p',`截止时间：${stamp(d.expires,d.zone)}。${d.mode==='AGENT_WAKE'?`每次唤醒最多 ${d.maxModelCalls} 次规划尝试；不等于包含 Coder/媒体等全部费用预算。`:'此定义不请求模型。'}`,pane,'muted');
    if(d.nextPlannedSlot!==undefined)el('p',`下一个未消费 slot：${d.kind.startsWith('TICK')?`overworld tick ${d.nextPlannedSlot}`:stamp(d.nextPlannedSlot,d.zone)}。这是计划槽位；实际可能按错过策略跳过，不是执行承诺。`,pane);
    if(d.kind.endsWith('PERIODIC'))el('p',d.missedPolicy==='SKIP'?'SKIP：跳过错过的周期；恢复不补跑已到期 slot。':'COALESCE：至多合并到最新未消费且仍有效的 slot，恢复后可能很快触发。',pane,'muted');
    if(d.kind==='EVENT_CONDITION')el('p',`准确条件订阅：${lastDetail.spec.conditionSubscription} · r${lastDetail.spec.conditionRevision}。恢复只接受此绑定并从当前记录水位继续；来源 revision 改变不会自动改绑。`,pane,'muted');
    if(!d.authorityReady)el('p','当前持续权限或条件不可用。仍可查看自己的定义、停止或归档，不能借原创建任务恢复授权。',pane,'warning');if(d.error)el('p',d.error,pane,'warning');
    if(d.state==='PAUSED'&&!d.canResume)el('p',d.resumeReason==='EXPIRED'?'已过期，不能恢复或延长期限。':d.resumeReason==='SLOTS_EXHAUSTED'?'槽位已耗尽，不能通过恢复重置次数。':'恢复需要当前 Agent、调度权限和准确条件同时有效。',pane,'warning');
    const count=lastDetail.retention.occurrences;el('p',`${d.archived?'定义已归档':'定义在运行区'} · occurrence 运行区 ${count.hot} / 已归档 ${count.archived} / 共 ${count.total}。归档不删除、不回收磁盘或退还预算。`,pane,'event-retention');
    if(lastDetail.retention.pushDeliveries){const p=lastDetail.retention.pushDeliveries;el('p',`窗口回执：运行区 ${p.hot} · 归档 ${p.archived} · 共 ${p.total}`,pane,'muted');}if(d.mode==='CONTENT_DELIVERY')el('p',`真实Native投递子任务，无模型规划步骤；目标 ${d.sourceState}。暂停/取消阻止新邀请，已发邀请需通过原投递撤回。${d.feedbackEnabled?'已启用反馈，后续用户提交可能按原policy唤醒模型。':''}`,pane,'muted');if(d.mode==='STATE_PUSH')el('p',`目标状态：${d.sourceState}。原Session只读刷新；无窗口也消耗slot，不请求模型，不重开窗口。`,pane,'muted');details('完整时间与条件定义（只读）',lastDetail.spec,pane);el('h4','最近执行记录',pane);
    if(!lastDetail.occurrences.length)el('p','本分页没有 occurrence；活动定义不代表条件已满足或业务已执行。',pane,'muted');
    for(const item of lastDetail.occurrences){const card=el('article',null,pane,'event-card');el('strong',`slot ${item.slot} · ${label(item.state)}`,card);el('p',item.id,card,'muted');el('p',`计划 ${d.kind.startsWith('TICK')?`tick ${item.due}`:stamp(item.due,d.zone)} · 记录 ${stamp(item.recordedAt,d.zone)}`,card,'muted');if(item.canInspectDeliveries)button('查看逐人投递记录',card,()=>void showDeliveries(item.id));if(item.deliveryResult)details('邀请操作回执（eligible 为解析时状态）',item.deliveryResult,card);if(item.taskId)el('p',d.mode==='CONTENT_DELIVERY'?`Native 投递子任务 ${item.taskId}（无模型规划）`:`Task ${item.taskId} · 规划尝试 ${item.modelAttempts} / ${item.maxModelCalls}`,card);if(item.conditionEvent)el('p',`条件记录 ${item.conditionEvent}（不读取事件正文）`,card,'muted');for(const row of item.pushDeliveries||[])el('p',`窗口 ${row.viewId}：${row.state} · 尝试 ${row.attempts} · 读取 r${row.readRevision}${row.error?` · ${row.error}`:''}（不是绘制或业务验证）`,card,'muted');if(item.error)el('p',item.error,card,'warning');if(item.scriptResult!==undefined)details('包声明的处理结果（非业务验证）',item.scriptResult,card);if(item.scriptResultRedacted)el('p','当前包/来源授权不可用，脚本结果已隐藏。',card,'warning');}
    const paging=el('div',null,pane,'actions');button('较新记录',paging,()=>{historyOffset=Math.max(0,historyOffset-8);void loadDetail();},historyOffset>0);button('更早记录',paging,()=>{historyOffset=lastDetail.nextOffset;void loadDetail();},lastDetail.more);updateButtons();
  }
  async function loadDetail(){if(!ready||writing||!selected)return;if(choice){choice=null;operation.reset();}const id=selected,serial=++detailSerial,generation=epoch,current=root;detailing=true;
    try{const data=await read({kind:'detail',scheduleId:id,offset:String(historyOffset)});if(generation!==epoch||serial!==detailSerial||current!==root||id!==selected||!root?.isConnected)return;lastDetail=data;clock(data.clock);renderDetail();}
    catch(error){if(generation===epoch&&serial===detailSerial)status(errorText(error),true);}finally{if(generation===epoch&&serial===detailSerial)detailing=false;}
  }
  async function showDeliveries(occurrenceId,offset=0){
    if(!ready||writing||!selected)return;choice=null;operation.reset();detailSerial++;detailing=false;const scheduleId=selected,current=root,generation=epoch,serial=++deliverySerial;deliveryView={occurrenceId,offset};
    const pane=query('#schedule-detail');pane.replaceChildren();el('h3','关联投递记录',pane);button('返回执行历史',pane,()=>{deliveryView=null;deliverySerial++;void loadDetail();});
    el('p','这是关联真实投递子任务的只读历史。未知 occurrence 即使查到邀请也不变成完整成功；空列表也不证明操作从未发生。',pane,'muted');const body=el('section',null,pane);el('p','读取中…',body,'muted');updateButtons();
    try{const data=await read({kind:'deliveries',scheduleId,occurrenceId,offset:String(offset)});if(generation!==epoch||serial!==deliverySerial||current!==root||selected!==scheduleId||!deliveryView||!body.isConnected)return;body.replaceChildren();el('p',`共 ${data.total} 条关联记录 · 当前 ${data.deliveries.length} 条`,body,'muted');
      if(!data.deliveries.length)el('p','当前没有找到关联邀请记录，不会自动重新发送。',body,'muted');
      for(const row of data.deliveries){const card=el('article',null,body,'event-card');el('strong',`${row.title} · ${row.status}`,card);el('p',`接收者 ${row.recipient} · ${row.mode}`,card);el('p',`delivery ${row.deliveryId} · batch ${row.batchId}`,card,'muted');el('p',`r${row.revision} / data r${row.dataRevision} · 截止 ${stamp(row.expiresAt)}`,card,'muted');el('p',`资产绘制 ${row.paintedAt?stamp(row.paintedAt):'尚无回执'} · 数据绘制匹配当前版本 ${row.dataPaintMatchesLatest?'是':'否'} · 关闭确认 ${row.closeConfirmed?'是':'否'}（均不是人类已读或业务验证）`,card,'muted');if(row.error)el('p',row.error,card,'warning');}
      const paging=el('div',null,body,'actions');button('上一页',paging,()=>void showDeliveries(occurrenceId,Math.max(0,offset-16)),offset>0);button('下一页',paging,()=>void showDeliveries(occurrenceId,data.nextOffset),data.more);button('刷新记录',paging,()=>void showDeliveries(occurrenceId,offset));updateButtons();
    }catch(error){if(generation===epoch&&serial===deliverySerial&&body.isConnected){body.replaceChildren();el('p',errorText(error),body,'error');button('重新读取',body,()=>void showDeliveries(occurrenceId,offset));updateButtons();}}
  }
  function confirm(action){if(!lastDetail||writing)return;listSerial++;detailSerial++;listing=detailing=false;const d=lastDetail.definition;choice={id:d.id,revision:d.revision,action};operation.reset();const panel=query('#schedule-confirm');panel.replaceChildren();panel.hidden=false;
    const text=action==='ARCHIVE'?'归档此终态或已过期定义、最多 256 条终态 occurrence 和 256 条终态窗口回执。过期项先停止旧未完成工作；旧历史和去重回执保留，不删除数据或回收磁盘。':action==='ACTIVE'?`恢复不重放旧 occurrence、不延长期限或重置次数。条件从当前记录水位继续；SKIP 跳过错过周期，COALESCE 或尚未消费的一次性任务可能立即触发。${d.mode==='AGENT_WAKE'?'未来触发可请求模型并计费。':d.mode==='SCRIPT'?'未来触发会执行已批准的包脚本，可能产生世界副作用。':d.mode==='STATE_PUSH'?'未来触发仅刷新已打开且opt-in的窗口；不打开窗口，不提供新权限。':d.mode==='CONTENT_DELIVERY'?'未来触发会创建无模型的真实投递子任务并写入内容邀请，接收者需明确接受。反馈若启用可按原policy唤醒模型。':''}`:action==='PAUSED'?'暂停此定义并阻止旧排队/在途任务继续获得执行许可；不承诺回滚已发生的副作用。':'取消此定义，不能恢复，历史保留；已发生的世界副作用不回滚。';el('p',text+(d.mode==='CONTENT_DELIVERY'&&action!=='ACTIVE'?' 已提交的邀请不会自动撤回，保留原有效期；需要撤回应另行使用原投递管理。':''),panel);
    const line=el('label',null,panel),check=el('input',null,line);check.type='checkbox';el('span',`确认对 r${d.revision} 的此调度执行上述操作`,line);const notice=el('p','',panel,'muted');button('确认',panel,()=>{if(check.checked)void write(notice);else notice.textContent='请先勾选确认。';});button('放弃',panel,()=>{choice=null;operation.reset();panel.hidden=true;});updateButtons();
  }
  async function write(notice){if(!ready||!visible()||writing||!choice)return;const snapshot=choice,current=root,generation=epoch;writing=true;listSerial++;detailSerial++;listing=detailing=false;updateButtons();notice.textContent='提交中…';
    try{const args=operation.request({kind:'write',scheduleId:snapshot.id,expectedRevision:String(snapshot.revision),action:snapshot.action,confirmed:true},()=>crypto.randomUUID());const response=await send('scheduleManagement',args);if(generation!==epoch||current!==root||!root?.isConnected)return;
      if(response.values?.errorCode||response.code!=='APPLIED')throw new Error(response.values?.errorCode||response.code||'SCHEDULE_WRITE_FAILED');const result=JSON.parse(response.values.state),receipt=result.receipt;
      status(`${result.duplicate?'读取原操作回执，未再次执行':'操作已提交'}；${receipt.action==='ARCHIVE'?`原操作归档定义 ${receipt.archivedDefinitions}、occurrence ${receipt.archivedOccurrences}、窗口回执 ${receipt.archivedPushDeliveries??0}。`:''}当前 ${label(result.currentState)} · r${result.currentRevision}。管理请求本身没有调用模型。`);choice=null;operation.reset();
    }catch(error){if(generation===epoch&&current===root){notice.textContent=`${errorText(error)} 结果未知请先读取；不会自动重发，当前操作 ID 仍保留。`;notice.className='error';}}
    finally{if(generation===epoch&&current===root){writing=false;updateButtons();if(!choice){await loadList();await loadDetail();}}}
  }
  function build(){if(root.childElementCount)return;el('p','本人持久调度 · 不依赖聊天窗口或原创建任务存活。管理请求不调用模型，恢复后的未来唤醒仍按定义执行。',root,'muted');const message=el('p','准备读取…',root,'muted');message.id='schedule-status';message.setAttribute('aria-live','polite');el('p','等待读取本人保留统计…',root,'event-retention').id='schedule-retention';el('p','',root,'muted').id='schedule-clock';
    const toolbar=el('div',null,root,'event-toolbar'),labelNode=el('label','定义状态 ',toolbar),filter=el('select',null,labelNode);filter.id='schedule-state-filter';for(const [value,text] of [['ALL','全部状态'],...Object.entries(states).filter(([value])=>['ACTIVE','PAUSED','CANCELLED','EXPIRED','FINISHED'].includes(value))])el('option',text,filter).value=value;
    filter.onchange=()=>{listOffset=0;void loadList();};button('刷新',toolbar,()=>{void loadList();if(deliveryView)void showDeliveries(deliveryView.occurrenceId,deliveryView.offset);else if(selected&&!choice)void loadDetail();});button('前往任务',toolbar,openTasks);button('事件与订阅',toolbar,openEvents);el('div',null,root,'actions').id='schedule-list-paging';const grid=el('div',null,root,'event-management-grid');el('section',null,grid).id='schedule-list';el('section',null,grid).id='schedule-detail';
    el('p','每世界定义运行区最多 128 条，每 owner/Agent 32 条；occurrence 和操作回执各运行区 8192 行，各受保护表含归档最多 131072 行。次数按 slot 保留、不因取消退还。RECORDED／DISPATCHED 不等于业务完成。',root,'muted');renderList();renderDetail();
  }
  function open(){const next=windowFor('runtime-schedules','定时与条件任务');if(root!==next){deliveryView=null;deliverySerial++;root=next;epoch++;listing=detailing=writing=false;lastList=lastDetail=null;choice=null;operation.reset();}build();updateButtons();if(ready){void loadList();if(selected)void loadDetail();}else status('等待服务端可信会话。');}
  setInterval(()=>{if(visible()&&!listing&&!detailing&&!writing&&!choice&&!deliveryView){void loadList();if(selected)void loadDetail();}},5000);
  return {open,session(value){deliveryView=null;deliverySerial++;epoch++;listSerial++;detailSerial++;listing=detailing=writing=false;choice=null;operation.reset();ready=!!value;const key=JSON.stringify([value?.binding?.worldId,value?.binding?.viewerPlayerId]);if(scope!==key){root?.replaceChildren();lastList=lastDetail=selected=null;listOffset=historyOffset=0;}scope=key;if(root?.isConnected){build();renderDetail();updateButtons();if(visible()){void loadList();if(selected)void loadDetail();}}},reset(){deliveryView=null;deliverySerial++;epoch++;listSerial++;detailSerial++;ready=false;listing=detailing=writing=false;choice=null;operation.reset();if(root){query('#schedule-confirm')?.replaceChildren();updateButtons();status('会话已失效，停止读取与管理操作。',true);}}};
}
