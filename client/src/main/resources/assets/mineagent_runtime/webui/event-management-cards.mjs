import {OperationDraft} from './agent-management-state.mjs';

const modes={RECORD_ONLY:'仅记录',AGENT_WAKE:'AI 唤醒',SCRIPT:'脚本处理',STATE_PUSH:'状态推送'};
const states={ACTIVE:'活动',PAUSED:'暂停',CANCELLED:'取消',QUEUED:'等待创建任务',CLAIMED:'已领取',DISPATCHED:'任务已创建',COMPLETED:'任务完成',RECORDED:'仅记录',INTERRUPTED:'中断／结果未知',THROTTLED:'冷却限制',BACKPRESSURE:'队列已满',BUDGET_EXHAUSTED:'预算已满',CYCLE_REJECTED:'循环已拒绝',SCRIPT_QUEUED:'脚本排队',SCRIPT_DISPATCHING:'脚本分派标记（非存活证明）',SCRIPT_HANDLED:'脚本已处理（非业务验证）',SCRIPT_CANCELLED:'脚本已取消',SCRIPT_INTERRUPTED:'脚本中断',PUSH_QUEUED:'推送排队',PUSH_WAITING:'等待读取回执',PUSH_NO_RECIPIENTS:'当时没有可用窗口',PUSH_READ_DELIVERED:'全部窗口读取已交付',PUSH_PARTIAL_OR_FAILED:'推送部分失败',PUSH_INTERRUPTED:'推送中断'};
const errors={EVENT_SUBSCRIPTION_STALE:'订阅已变化，请重新读取后操作。',EVENT_SUBSCRIPTION_EXPIRED:'订阅已过期，恢复不会延长期限。',EVENT_CONSUMER_BUDGET_EXHAUSTED:'调用预算已耗尽；需要另一个明确的新订阅，不能恢复重置预算。',EVENT_RESUME_AUTHORITY_REQUIRED:'当前 Agent、来源或消费目标权限不足，不能恢复。',EVENT_RESUME_SOURCE_UNAVAILABLE:'当前来源尚不可用，不能恢复。',FEEDBACK_CONSUMER_ALREADY_BOUND:'已有活动反馈消费者，不能重复恢复。',EVENT_OPERATION_REUSED:'这个操作 ID 已用于不同参数，请先读取现有结果。',EVENT_STORAGE_BUDGET:'事件存储配额已达上限。',FORBIDDEN:'没有执行此操作的权限。',PERMISSION_DENIED:'没有执行此操作的权限。',EVENT_SUBSCRIPTION_MISSING:'订阅不存在。'};
const label=value=>states[value]||value||'—';
Object.assign(errors,{RETENTION_TOTAL_ROW_BUDGET:'该世界对应表的总保留行数已达 131072，归档不会释放总量；停止新写入并由管理员处理容量，勿删除去重记录。',EVENT_ARCHIVE_REQUIRES_STOPPED:'只可归档已取消或已过期的订阅。仍可恢复的暂停项请先明确取消。'});
const date=value=>Number.isFinite(Number(value))?new Date(Number(value)).toLocaleString():'—';
export function createEventManagement({windowFor,send,report,openTasks}){
  let root=null,epoch=0,listSerial=0,detailSerial=0,scope='',ready=false,listing=false,detailing=false,writing=false,selected=null,confirmation=null,listOffset=0,historyOffset=0,lastList=null,lastDetail=null;
  const operation=new OperationDraft();
  const el=(tag,text,parent,cls)=>{const node=document.createElement(tag);if(text!=null)node.textContent=text;if(cls)node.className=cls;parent?.append(node);return node;};
  const button=(text,parent,action)=>{const node=el('button',text,parent);node.type='button';node.onclick=action;return node;};
  function visible(){const window=root?.closest('.window');return ready&&root?.isConnected&&document.body.dataset.workspaceVisible!=='false'&&window&&getComputedStyle(window).display!=='none';}
  function message(text,bad=false){const node=root?.querySelector('#event-management-status');if(node){node.textContent=text;node.className=bad?'error':'muted';}}
  function fail(error){message(errors[error.message]||String(error.message||error),true);}
  async function read(args){const receipt=await send('eventManagement',args);if(receipt.values?.errorCode)throw new Error(receipt.values.errorCode);if(receipt.code!=='OBSERVED')throw new Error(receipt.code||'EVENT_READ_FAILED');return JSON.parse(receipt.values.state);}
  function updateButtons(){if(!root)return;root.querySelector('#event-list-refresh').disabled=!ready||listing||writing;root.querySelector('#event-list-previous').disabled=!ready||listing||writing||listOffset===0;root.querySelector('#event-list-next').disabled=!ready||listing||writing||!lastList?.more;root.querySelectorAll('[data-event-state]').forEach(node=>{node.disabled=writing||!ready||node.dataset.allowed!=='true';});root.querySelectorAll('#event-action-confirm button,[data-event-read]').forEach(node=>{node.disabled=writing||!ready;});}
  function renderList(){const list=root.querySelector('#event-management-list');list.replaceChildren();const usage=lastList?.retention;root.querySelector('#event-retention-summary').textContent=usage?`本人全部定义：运行区 ${usage.hot} · 已归档 ${usage.archived} · 保留总数 ${usage.total}（不随筛选变化）`:'等待读取本人保留统计…';if(!lastList?.items.length){root.querySelector('#event-list-page').textContent='没有匹配订阅';el('p','没有符合筛选的本人订阅。通过任务按需求创建，管理页本身不调用模型。',list,'muted');return;}
    for(const sub of lastList.items){const card=el('article',null,list,'event-card');card.dataset.subscriptionId=sub.id;card.setAttribute('aria-current',String(selected===sub.id));const heading=el('div',null,card,'actions');el('strong',`${sub.agentName} · ${modes[sub.mode]||sub.mode}`,heading);el('span',label(sub.state),heading,'agent-state');
      el('p',`${sub.sources.join(' / ')} · ${sub.target}`,card,'muted');el('p',sub.mode==='RECORD_ONLY'?'只记录，不创建模型任务':`已保留 ${sub.used} / ${sub.maximum}${sub.mode==='AGENT_WAKE'?` · 每次最多 ${sub.planningCallsPerWake} 次规划尝试`:''}`,card,'muted');
      el('p',`r${sub.revision} · ${sub.archived?'已归档 · 历史仍保留':'运行区'} · 到期 ${date(sub.expiresAt)}`,card,'muted');const inspect=button('查看与管理',card,()=>{if(writing)return;selected=sub.id;historyOffset=0;confirmation=null;lastDetail=null;operation.reset();renderDetail();void loadDetail();renderList();});inspect.disabled=writing;
    }
    root.querySelector('#event-list-page').textContent=`本人 ${lastList.total} 个匹配订阅 · 当前 ${listOffset+1}–${listOffset+lastList.items.length}`;
  }
  async function loadList(){if(!ready||writing)return;const serial=++listSerial,generation=epoch,current=root;listing=true;updateButtons();
    try{const data=await read({kind:'list',mode:root.querySelector('#event-mode-filter').value,state:root.querySelector('#event-state-filter').value,offset:String(listOffset)});if(generation!==epoch||serial!==listSerial||current!==root||!root?.isConnected)return;lastList=data;renderList();}
    catch(error){if(generation===epoch&&serial===listSerial)fail(error);}finally{if(generation===epoch&&serial===listSerial){listing=false;updateButtons();}}
  }
  function jsonDetails(title,value,parent){const details=el('details',null,parent);el('summary',title,details);el('pre',JSON.stringify(value,null,2),details,'event-json');}
  function renderDetail(){const pane=root.querySelector('#event-management-detail');pane.replaceChildren();if(!lastDetail||lastDetail.subscription.id!==selected){el('p','选择一个订阅查看其来源、预算和逐次结果。',pane,'muted');return;}
    const sub=lastDetail.subscription;el('h3',`${sub.agentName} · ${modes[sub.mode]||sub.mode}`,pane);el('p',`${label(sub.state)} · r${sub.revision} · ${sub.id}`,pane,'muted');el('p',`到期：${date(sub.expiresAt)}${sub.error?` · ${sub.error}`:''}`,pane,'muted');
    const actions=el('div',null,pane,'actions');for(const [state,text,allowed] of [['PAUSED','暂停',sub.canPause],['CANCELLED','取消订阅',sub.canCancel],['ACTIVE','恢复…',sub.canResume]]){const node=button(text,actions,()=>showConfirmation(state));node.dataset.eventState=state;node.dataset.allowed=String(allowed);}
    const archive=button('归档已停止记录…',actions,()=>showConfirmation('ARCHIVE'));archive.dataset.eventState='ARCHIVE';archive.dataset.allowed=String(lastDetail.retention?.canArchive===true);
    button('重新读取',actions,()=>{if(writing)return;confirmation=null;operation.reset();void loadDetail();}).dataset.eventRead='true';if(sub.state==='PAUSED'&&!sub.canResume)el('p',sub.resumeReason==='BUDGET_EXHAUSTED'?'预算已耗尽，不能通过恢复增加额度。':sub.resumeReason==='EXPIRED'?'已过期，恢复不会延长期限。':'恢复需要当前 Agent、来源与目标授权和就绪状态。',pane,'warning');
    const retention=lastDetail.retention;if(retention){const summary=el('section',null,pane,'event-retention');el('strong',retention.definitionArchived?'定义已归档':'定义位于运行区',summary);for(const [title,counts] of [['触发记录',retention.triggers],['窗口回执',retention.pushDeliveries]])el('p',`${title}：运行区 ${counts.hot} · 已归档 ${counts.archived} · 共 ${counts.total}`,summary);el('p','逻辑归档不删除数据、不回收磁盘、不退还模型或脚本额度。可恢复的暂停项不归档。',summary,'muted');}
    const confirm=el('section',null,pane,'event-confirm');confirm.id='event-action-confirm';confirm.hidden=true;
    jsonDetails('来源与消费者定义（不是执行成功证明）',lastDetail.request,pane);jsonDetails('当前来源状态',lastDetail.sourceState,pane);
    el('h4','最近事件与处理结果',pane);if(!lastDetail.history.length)el('p','这个分页没有事件记录。活动订阅不等于事件已经发生。',pane,'muted');
    for(const item of lastDetail.history){const card=el('article',null,pane,'event-card');el('strong',label(item.state),card);el('p',`${date(item.createdAt)} · ${item.id}`,card,'muted');if(item.error)el('p',item.error,card,'warning');if(item.taskId)el('p',`关联任务：${item.taskId} · 模型规划尝试 ${item.modelAttempts} / ${item.maxModelCalls}`,card,'muted');
      if(item.event?.observationRedacted)el('p','当前来源权限/资源不可用，历史数据已隐藏；元数据仍保留。',card,'warning');else jsonDetails('事件数据',item.event,card);
      if(item.scriptResult!==undefined)jsonDetails('包声明的脚本结果（非业务验证）',item.scriptResult,card);
      for(const delivery of item.pushDeliveries||[])el('p',`窗口 ${delivery.viewId}：${label(delivery.state)} · 尝试 ${delivery.attempts} · 读取 r${delivery.readRevision}${delivery.error?` · ${delivery.error}`:''}（非绘制证明）`,card,'muted');
    }
    const paging=el('div',null,pane,'actions');const previous=button('较新记录',paging,()=>{historyOffset=Math.max(0,historyOffset-8);void loadDetail();}),next=button('更早记录',paging,()=>{historyOffset=lastDetail.nextOffset;void loadDetail();});previous.disabled=historyOffset===0||writing;next.disabled=!lastDetail.more||writing;updateButtons();
  }
  async function loadDetail(){if(!ready||!selected||writing)return;const id=selected,serial=++detailSerial,generation=epoch,current=root;detailing=true;
    try{const data=await read({kind:'detail',subscriptionId:id,offset:String(historyOffset)});if(generation!==epoch||serial!==detailSerial||current!==root||id!==selected||!root?.isConnected)return;lastDetail=data;renderDetail();}
    catch(error){if(generation===epoch&&serial===detailSerial)fail(error);}finally{if(generation===epoch&&serial===detailSerial)detailing=false;}
  }
  function showConfirmation(state){if(!ready||writing||!lastDetail)return;detailSerial++;detailing=false;const sub=lastDetail.subscription;confirmation={id:sub.id,revision:sub.revision,state,mode:sub.mode};operation.reset();const panel=root.querySelector('#event-action-confirm');panel.replaceChildren();panel.hidden=false;
    const text=state==='ARCHIVE'?'只归档本人这个已取消／已过期定义及其终态记录，每类最多 256 条；有剩余需另行确认。到期项先停止旧工作，未完成窗口回执暂留运行区。原历史和操作去重保留，不删除数据或回收磁盘，不请求模型。':state==='ACTIVE'?`恢复只监听后续新事件，不重放旧工作、不延长期限或增加预算。${sub.mode==='AGENT_WAKE'?'未来命中可能调用模型并计费。':sub.mode==='SCRIPT'?'未来命中会执行已批准脚本。':sub.mode==='STATE_PUSH'?'未来命中会向已订阅窗口发送刷新提示。':''}`:state==='PAUSED'?'暂停将阻止未执行工作；已经产生的副作用不承诺回滚。':'取消此订阅，保留历史；已发生的副作用不回滚，不能再恢复。';el('p',text,panel);
    const label=el('label',null,panel),check=el('input',null,label);check.type='checkbox';el('span','我确认对这个订阅执行上述操作',label);const notice=el('p','',panel,'muted');const commit=button('确认',panel,()=>write(check,notice));button('放弃',panel,()=>{confirmation=null;operation.reset();panel.hidden=true;});commit.dataset.confirmEvent='true';
  }
  async function write(check,notice){if(!check.checked||!confirmation||writing)return;const choice=confirmation,generation=epoch,current=root;writing=true;listSerial++;detailSerial++;listing=detailing=false;updateButtons();notice.textContent='提交中…';
    try{const args=operation.request({kind:choice.state==='ARCHIVE'?'archive':'state',subscriptionId:choice.id,expectedRevision:String(choice.revision),...(choice.state==='ARCHIVE'?{confirmed:true}:{state:choice.state,confirmed:choice.state==='ACTIVE'})},()=>crypto.randomUUID());const receipt=await send('eventManagement',args);
      if(generation!==epoch||current!==root||!root?.isConnected)return;if(receipt.code!=='APPLIED'||receipt.values?.errorCode)throw new Error(receipt.values?.errorCode||receipt.code);const result=JSON.parse(receipt.values.state),counts=result.receipt?.archive;message(`${result.duplicate?'读取同一操作原回执（没有再次执行）':'操作已提交'}；${counts?`原操作归档定义 ${counts.subscriptions}、触发 ${counts.triggers}、窗口回执 ${counts.pushDeliveries}。`:''}当前 ${label(result.currentState)} · r${result.currentRevision}。没有直接调用模型。`);confirmation=null;operation.reset();
    }catch(error){if(generation===epoch&&current===root){notice.textContent=(errors[error.message]||error.message)+' 结果未知时先读取；不会自动重发，保留当前操作 ID。';notice.className='error';}}
    finally{if(generation===epoch&&current===root){writing=false;updateButtons();if(!confirmation){await loadList();await loadDetail();}}}
  }
  function build(){
    if(!root.childElementCount){el('p','只管理本人创建的订阅，不借 OP 或其他人的 Task。读取、暂停、取消和归档不请求模型；恢复必须重新核对当前权限。',root,'muted');const status=el('p','准备读取…',root,'muted');status.id='event-management-status';status.setAttribute('aria-live','polite');el('p','等待读取本人保留统计…',root,'event-retention').id='event-retention-summary';const toolbar=el('div',null,root,'event-toolbar');
      for(const [id,title,options] of [['event-mode-filter','消费方式',[['ALL','全部方式'],...Object.entries(modes)]],['event-state-filter','定义状态',[['ALL','全部状态'],['ACTIVE','活动'],['PAUSED','暂停'],['CANCELLED','取消']]]]){const select=el('select',null,toolbar);select.id=id;select.setAttribute('aria-label',title);for(const [value,text] of options)el('option',text,select).value=value;select.onchange=()=>{listOffset=0;void loadList();};}
      button('刷新',toolbar,()=>{void loadList();if(selected&&!confirmation)void loadDetail();}).id='event-list-refresh';button('前往任务',toolbar,openTasks);const paging=el('div',null,root,'actions');button('上一页',paging,()=>{listOffset=Math.max(0,listOffset-16);void loadList();}).id='event-list-previous';el('span','',paging,'muted').id='event-list-page';button('下一页',paging,()=>{listOffset=lastList.nextOffset;void loadList();}).id='event-list-next';
      const grid=el('div',null,root,'event-management-grid');el('section',null,grid).id='event-management-list';el('section',null,grid).id='event-management-detail';el('p','记录、SCRIPT_HANDLED 和推送 READ_DELIVERED 均不能替代业务／绘制／人类已读证明。每世界定义运行区最多 128 条、每 owner/Agent 32 条；事件／回执等各表运行区最多 8192 条，各受保护表含归档最多 131072 条。归档仅释放运行区额度；总量满仍拒绝，不提供删除或无限保留。',root,'muted');
    }
  }
  function open(){const next=windowFor('runtime-events','事件与订阅');if(root!==next){root=next;epoch++;lastList=null;lastDetail=null;confirmation=null;operation.reset();}
    build();
    updateButtons();if(ready){void loadList();if(selected)void loadDetail();}else message('等待服务端可信会话。');
  }
  setInterval(()=>{if(visible()&&!listing&&!detailing&&!writing&&!confirmation){void loadList();if(selected)void loadDetail();}},5000);
  return {open,session(value){epoch++;listSerial++;detailSerial++;listing=detailing=writing=false;confirmation=null;operation.reset();ready=!!value;const key=JSON.stringify([value?.binding?.worldId,value?.binding?.viewerPlayerId]);if(scope!==key){root?.replaceChildren();lastList=lastDetail=selected=null;listOffset=historyOffset=0;}scope=key;if(root?.isConnected){build();updateButtons();if(visible()){void loadList();if(selected)void loadDetail();}}},reset(){epoch++;ready=false;confirmation=null;operation.reset();root?.querySelector('#event-action-confirm')?.replaceChildren();updateButtons();message('会话已失效，停止刷新和管理操作。',true);}};
}
