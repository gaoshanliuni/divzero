import {OperationDraft} from './agent-management-state.mjs';

const statuses={OFFERED:'等待接受',OPENING:'正在准备',CLIENT_RECEIVED:'客户端已接收',RENDERED:'资产已有绘制回执',INTERACTED:'已有交互',SUSPENDED:'需重新接受',CODE_DOWNLOADING:'原生代码下载中（未执行）',CODE_DOWNLOADED:'原生代码已下载（未批准执行）',CODE_RUNNING:'接收者本机已确认运行',CODE_STOPPED:'接收者本机已停止',CODE_SUSPENDED:'连接变化，需重新接受',CODE_FAILED:'接收者本机失败，可重试或拒绝',CLOSE_REQUESTED:'等待关闭确认',CLOSED:'已关闭状态',REVOKED:'已撤回',REJECTED:'已拒绝',EXPIRED:'已过期',FAILED:'失败',UNAVAILABLE:'当时不可用'};
const errors={DELIVERY_MANAGEMENT_STALE:'记录已变化，请重新读取后确认。',DELIVERY_ARCHIVE_NOT_SETTLED:'尚有未收束窗口，不能归档；先关闭/撤回并核查回执。',DELIVERY_OPERATION_REUSED:'操作 ID 已被不同请求使用。',RETENTION_TOTAL_ROW_BUDGET:'总保留行数已满；归档不删除旧证据或释放总量。',RETENTION_PAYLOAD_BYTE_BUDGET:'保留 payload 增长预算已满；归档不回收磁盘。',DELIVERY_OPERATION_BUDGET:'管理回执运行区已满。',FORBIDDEN:'不能管理其他发起者的投递。'};
const label=s=>statuses[s]||s||'—';
const date=n=>Number(n)>0?new Date(Number(n)).toLocaleString():'尚无回执';
export function createSentContent({windowFor,send,openInbox}){
  let root=null,ready=false,scope='',epoch=0,listSerial=0,detailSerial=0,offset=0,selected=null,page=null,detail=null,choice=null,writing=false,listing=false,detailing=false;
  const op=new OperationDraft(),el=(tag,text,parent,cls)=>{const n=document.createElement(tag);if(text!=null)n.textContent=text;if(cls)n.className=cls;parent?.append(n);return n;};
  const q=s=>root.querySelector(s),button=(text,parent,fn,allowed=true)=>{const n=el('button',text,parent);n.type='button';n.dataset.sentAllowed=String(allowed);n.onclick=()=>{if(ready&&!writing)fn();};return n;};
  function controls(){if(root)root.querySelectorAll('[data-sent-allowed]').forEach(n=>{n.disabled=!ready||writing||n.dataset.sentAllowed!=='true';});}
  function status(text,bad=false){if(!root)return;const n=q('#sent-status');n.textContent=text;n.className=bad?'error':'muted';}
  function visible(){const w=root?.closest('.window');return ready&&root?.isConnected&&w&&getComputedStyle(w).display!=='none'&&document.body.dataset.workspaceVisible!=='false';}
  async function read(args){const r=await send('deliveryManagement',args);if(r.values?.errorCode||r.code!=='OBSERVED')throw new Error(r.values?.errorCode||r.code||'DELIVERY_MANAGEMENT_FAILED');return JSON.parse(r.values.state);}
  function json(title,data,parent){const box=el('details',null,parent);el('summary',title,box);el('pre',JSON.stringify(data,null,2),box,'event-json');}
  function renderList(){const list=q('#sent-list');list.replaceChildren();const usage=page?.usage;q('#sent-usage').textContent=usage?`本人已发记录：运行区 ${usage.hot} · 已归档 ${usage.archived} · 共 ${usage.total}`:'等待读取…';
    if(!page?.items.length)el('p','没有符合筛选的本人投递。此页不新建邀请、不调用模型。',list,'muted');
    for(const d of page?.items||[]){const card=el('article',null,list,'event-card');card.setAttribute('aria-current',String(selected===d.deliveryId));el('strong',`${d.title} · ${d.mode}`,card);el('p',`${label(d.status)} · ${d.archived?'已归档':'运行区'}`,card);el('p',`接收者 ${d.recipient} · data r${d.dataRevision}`,card,'muted');button('查看与管理',card,()=>{selected=d.deliveryId;choice=null;op.reset();detail=null;renderList();void loadDetail();});}
    const paging=q('#sent-paging');paging.replaceChildren();button('上一页',paging,()=>{offset=Math.max(0,offset-16);void loadList();},offset>0);el('span',page?`${page.total} 条匹配 · 当前 ${page.items.length?offset+1:0}–${offset+page.items.length}`:'',paging,'muted');button('下一页',paging,()=>{offset=page.nextOffset;void loadList();},!!page?.more);controls();
  }
  async function loadList(){if(!ready||writing)return;const generation=epoch,serial=++listSerial,current=root;listing=true;
    try{const value=await read({kind:'list',status:q('#sent-filter-status').value,archive:q('#sent-filter-archive').value,offset:String(offset)});if(generation!==epoch||serial!==listSerial||current!==root||!root?.isConnected)return;page=value;renderList();}
    catch(e){if(generation===epoch&&serial===listSerial)status(errors[e.message]||e.message,true);}finally{if(generation===epoch&&serial===listSerial)listing=false;}
  }
  function renderDetail(){const pane=q('#sent-detail');pane.replaceChildren();if(!detail||detail.delivery.deliveryId!==selected){el('p','选择一条本人投递，核查逐人状态与关闭回执。',pane,'muted');return;}const d=detail.delivery;
    el('h3',d.title,pane);el('p',`${label(d.status)} · r${d.revision} · ${d.deliveryId}`,pane,'muted');el('p',`接收者 ${d.recipient} · Agent ${d.agentId}`,pane,'muted');el('p',`batch ${d.batchId} · 截止 ${date(d.expiresAt)}`,pane,'muted');
    if(d.legacyAuthority)el('p','旧记录缺少持久权限版本，当前不再据此准入；历史仍保存，需要发送方明确的新投递。不会自动重发。',pane,'warning');if(d.error)el('p',d.error,pane,'warning');
    el('p',`资产绘制 ${date(d.paintedAt)} · 当前数据绘制匹配 ${d.dataPaintMatchesLatest?'是':'否'} · 关闭确认 ${d.closeConfirmed?'是':'否'}。这些均不是业务完成或人类已读。`,pane,'muted');
    const actions=el('div',null,pane,'actions');for(const [action,title,allowed] of [['CLOSE','关闭…',d.canClose],['REVOKE','撤回…',d.canRevoke],['ARCHIVE','归档…',d.canArchive]])button(title,actions,()=>confirm(action),allowed);button('重新读取',actions,()=>{choice=null;op.reset();void loadDetail();});const confirmation=el('section',null,pane,'event-confirm');confirmation.id='sent-confirm';confirmation.hidden=true;
    json('已保存的投递数据（只读，不执行）',detail.data,pane);json('资源与归属', {entry:detail.entry,canonicalSha256:detail.canonicalSha256,originTask:detail.originTask,packageId:d.packageId,packageRevision:d.packageRevision},pane);controls();
  }
  async function loadDetail(){if(!ready||writing||!selected)return;const id=selected,generation=epoch,serial=++detailSerial,current=root;detailing=true;
    try{const value=await read({kind:'detail',deliveryId:id});if(generation!==epoch||serial!==detailSerial||current!==root||selected!==id||!root?.isConnected)return;detail=value;renderDetail();}
    catch(e){if(generation===epoch&&serial===detailSerial)status(errors[e.message]||e.message,true);}finally{if(generation===epoch&&serial===detailSerial)detailing=false;}
  }
  function confirm(action){if(!detail)return;listSerial++;detailSerial++;listing=detailing=false;const d=detail.delivery;choice={id:d.deliveryId,revision:d.revision,action};op.reset();const panel=q('#sent-confirm');panel.replaceChildren();panel.hidden=false;
    el('p',action==='ARCHIVE'?'归档这条可清理终态记录，不删除原数据或去重回执、不回收磁盘，不把未确认关闭改成已确认。':action==='CLOSE'?'请求关闭这条投递；有 Session 时仍需 Native 关闭回执。已受理反馈和已发生副作用不回滚，不影响其他接收者。':'撤回这条投递的展示授权，并通知客户端清理；实际窗口关闭仍须看回执。已受理反馈和业务副作用不回滚。',panel);
    const line=el('label',null,panel),check=el('input',null,line);check.type='checkbox';el('span',`我确认对 r${d.revision} 的这条记录执行上述操作`,line);const result=el('p','',panel,'muted');button('确认',panel,()=>{if(check.checked)void write(result);else result.textContent='请先勾选确认。';});button('放弃',panel,()=>{choice=null;op.reset();panel.hidden=true;});controls();
  }
  async function write(result){if(!choice||!visible()||writing)return;const request=choice,generation=epoch,current=root;writing=true;listSerial++;detailSerial++;listing=detailing=false;controls();result.textContent='提交中…';
    try{const r=await send('deliveryManagement',op.request({kind:'write',deliveryId:request.id,expectedRevision:String(request.revision),action:request.action,confirmed:true},()=>crypto.randomUUID()));if(generation!==epoch||current!==root||!root?.isConnected)return;if(r.values?.errorCode||r.code!=='APPLIED')throw new Error(r.values?.errorCode||r.code);const value=JSON.parse(r.values.state);status(`${value.duplicate?'读取原操作回执，未重复执行':'操作已提交'}；当前 ${label(value.currentStatus)} · r${value.currentRevision} · 关闭确认 ${value.closeConfirmed?'是':'否'}。没有调用模型。`);choice=null;op.reset();}
    catch(e){if(generation===epoch&&result.isConnected){result.textContent=`${errors[e.message]||e.message}；未知结果先读取，不自动重发，当前操作 ID 保留。`;result.className='error';}}
    finally{if(generation===epoch&&current===root){writing=false;controls();if(!choice){await loadList();await loadDetail();}}}
  }
  function build(){if(root.childElementCount)return;el('p','只管理本人发起的内容，不借原 Task 或其他 Agent 的权限。关闭/撤回不等于回滚反馈或业务。',root,'muted');const n=el('p','等待可信会话…',root,'muted');n.id='sent-status';n.setAttribute('aria-live','polite');el('p','',root,'event-retention').id='sent-usage';
    const toolbar=el('div',null,root,'event-toolbar');for(const [id,title,options] of [['sent-filter-status','状态',[['ALL','全部状态'],...Object.entries(statuses)]],['sent-filter-archive','存储',[['ALL','全部'],['HOT','运行区'],['ARCHIVED','已归档']]]]){const line=el('label',`${title} `,toolbar),select=el('select',null,line);select.id=id;for(const [value,text] of options)el('option',text,select).value=value;select.onchange=()=>{offset=0;void loadList();};}button('刷新第一页',toolbar,()=>{offset=0;void loadList();if(selected&&!choice)void loadDetail();});button('我的收件箱',toolbar,openInbox);el('div',null,root,'actions').id='sent-paging';const grid=el('div',null,root,'event-management-grid');el('section',null,grid).id='sent-list';el('section',null,grid).id='sent-detail';el('p','各表每 world 总保留 131072 行、payload 正常增长预算 256 MiB；投递运行区 4096，普通操作/管理回执运行区各 8192。终结元数据有有限余量，均不等于磁盘硬上限或个人剩余配额。',root,'muted');renderList();renderDetail();
  }
  function open(){const next=windowFor('runtime-sent-content','本人已发内容');if(root!==next){root=next;epoch++;page=detail=null;choice=null;op.reset();writing=listing=detailing=false;}build();controls();if(ready){void loadList();if(selected)void loadDetail();}}
  setInterval(()=>{if(visible()&&!writing&&!listing&&!detailing&&!choice){void loadList();if(selected)void loadDetail();}},5000);
  return {open,session(value){epoch++;listSerial++;detailSerial++;ready=!!value;writing=listing=detailing=false;choice=null;op.reset();const key=JSON.stringify([value?.binding?.worldId,value?.binding?.viewerPlayerId]);if(scope!==key){root?.replaceChildren();page=detail=selected=null;offset=0;}scope=key;if(root?.isConnected){build();renderDetail();controls();if(visible()){void loadList();if(selected)void loadDetail();}}},reset(){epoch++;ready=false;choice=null;op.reset();if(root?.isConnected){q('#sent-confirm')?.replaceChildren();controls();status('会话已失效，停止读取与管理。',true);}}};
}
