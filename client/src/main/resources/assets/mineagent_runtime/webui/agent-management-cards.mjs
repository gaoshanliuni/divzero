import {AgentEditDraft,OperationDraft,bodyLabels,modeLabel,agentError} from './agent-management-state.mjs';

export function createAgentManagement({windowFor,send,report,openModel,openSkin}){
  let root=null,roster=null,epoch=0,scope='',createFields=null,createBusy=false;const cards=new Map(),createOperation=new OperationDraft();
  const add=(tag,text,parent,cls)=>{const n=document.createElement(tag);if(text!=null)n.textContent=text;if(cls)n.className=cls;parent.append(n);return n;};
  const label=(text,field,parent)=>{const n=add('label',text,parent);n.append(field);return field;};
  const button=(text,parent,fn)=>{const b=add('button',text,parent);b.type='button';b.onclick=fn;return b;};
  function error(node,e){node.textContent=agentError(e.message||String(e));node.className='error';}
  async function request(args,node,onSuccess){
    const generation=epoch,ownerRoot=root;node.textContent='正在提交…';node.className='muted';
    try{const receipt=await send('agentManagement',args);if(generation!==epoch||ownerRoot!==root||!root?.isConnected)return;
      if(receipt.code!=='APPLIED')throw new Error(receipt.values?.errorCode||receipt.code);
      node.textContent='已保存，正在读取最新资料。';node.className='success';onSuccess?.(receipt.values);
    }catch(e){if(generation===epoch&&ownerRoot===root&&root?.isConnected)error(node,e);}
  }
  function createCard(a){
    const node=add('article',null,root.querySelector('#agent-management-list'),'agent-card');node.dataset.agentCard=a.id;
    const header=add('div',null,node,'agent-card-heading'),title=add('strong',a.name,header),badge=add('span','',header,'agent-state');
    const info=add('p','',node,'muted'),notice=add('p','',node,'muted'),form=add('fieldset',null,node),fields=add('div',null,form,'agent-fields');
    const name=label('名称',document.createElement('input'),fields);name.maxLength=64;name.setAttribute('aria-label','AI 名称');
    const mode=label('请求模式',document.createElement('select'),fields);mode.setAttribute('aria-label','请求模式');for(const [v,text] of [['CREATOR','创造'],['SURVIVAL','生存']])add('option',text,mode).value=v;
    const actions=add('div',null,form,'actions'),confirmation=add('div',null,form,'agent-confirm');confirmation.hidden=true;
    const message=add('p','',confirmation),confirmLabel=add('label','',confirmation),check=add('input',null,confirmLabel);check.type='checkbox';add('span','我确认对这个 AI 执行上述操作',confirmLabel);
    const record={node,title,badge,info,notice,form,name,mode,confirmation,message,check,draft:new AgentEditDraft(),operation:new OperationDraft(),value:a,busy:false,confirmKind:null};
    name.oninput=mode.onchange=()=>{record.draft.edit(name.value,mode.value);record.operation.reset();check.checked=false;confirmation.hidden=true;};
    async function write(kind){if(record.busy)return;record.busy=true;form.disabled=true;
      try{const args=record.draft.request(kind,a.id,()=>crypto.randomUUID(),record.operation,kind==='mode'&&check.checked);await request(args,notice,v=>record.draft.committed(v.revision,kind));}
      catch(e){error(notice,e);}finally{record.busy=false;form.disabled=!record.value.canManage;confirmation.hidden=true;check.checked=false;}
    }
    record.modelButton=button('模型',actions,()=>openModel?.(a.id));record.modelButton.dataset.agentAction='model';record.skinButton=button('皮肤',actions,()=>openSkin?.(a.id));record.skinButton.dataset.agentAction='skin';
    button('保存名称',actions,()=>write('rename')).dataset.agentAction='rename';
    button('应用模式…',actions,()=>{record.confirmKind='mode';message.textContent=`将「${record.value.name}」的实际模式设为${modeLabel(mode.value)}。现有身体动作可能中断；观战状态会被明确覆盖。`;check.checked=false;confirmation.hidden=false;}).dataset.agentAction='mode';
    button('重新读取',actions,()=>{record.draft.dirty=false;record.operation.reset();updateCard(record,record.value);notice.textContent='已重新读取当前资料。';notice.className='muted';});
    button('删除 AI…',actions,()=>{record.confirmKind='delete';message.textContent=`删除「${record.value.name}」并停止关联控制。历史任务保留，删除不可撤销。`;check.checked=false;confirmation.hidden=false;}).dataset.agentAction='delete';
    const confirm=button('确认操作',confirmation,async()=>{if(!check.checked||record.busy)return;if(record.confirmKind==='mode'){await write('mode');return;}
      record.busy=true;form.disabled=true;await request(record.operation.request({kind:'delete',agentId:a.id,expectedRevision:record.value.revision,confirmed:true},()=>crypto.randomUUID()),notice,()=>{notice.textContent='已删除。';});record.busy=false;form.disabled=!record.value.canManage;confirmation.hidden=true;check.checked=false;
    });confirm.dataset.agentAction='confirm';button('取消',confirmation,()=>{confirmation.hidden=true;check.checked=false;});
    const collab=add('details',null,form);add('summary','协作者（仅所有者可编辑）',collab);const grants=add('div',null,collab),playerSelect=add('select',null,collab);playerSelect.setAttribute('aria-label','在线协作者');
    const manual=label('仅当玩家离线、无法列出时填写 UUID',document.createElement('input'),collab);manual.maxLength=36;manual.setAttribute('aria-label','离线协作者 UUID');playerSelect.onchange=()=>{if(playerSelect.value)manual.value='';};
    const grant=button('添加协作者',collab,()=>collaborator(manual.value.trim()||playerSelect.value,true));record.collab={container:collab,grants,select:playerSelect,grant};
    async function collaborator(id,enabled){if(!id||record.busy||!record.value.canCollaborate)return;record.busy=true;form.disabled=true;
      await request(record.operation.request({kind:'collaborator',agentId:a.id,expectedRevision:record.value.revision,playerId:id,enabled},()=>crypto.randomUUID()),notice,v=>{record.draft.revision=Number(v.revision);record.draft.stale=false;});record.busy=false;form.disabled=!record.value.canManage;
    }
    const access=add('details',null,form);add('summary','响应权限（仅创建者）',access);const accessMode=add('select',null,access);for(const [value,text]of [['ASK','其他玩家请求时询问我'],['ALLOW_ALL','全部允许'],['DENY_ALL','全部拒绝'],['ALLOW_LIST','仅允许列表']])add('option',text,accessMode).value=value;
    const accessPlayers=add('select',null,access),accessList=add('div',null,access),accessActions=add('div',null,access,'actions');record.access={root:access,mode:accessMode,players:accessPlayers,list:accessList};
    const saveAccess=async(entry='',playerId='')=>{if(record.busy||!record.value.mine)return;record.busy=true;try{await request({kind:'chat_access',agentId:a.id,policyRevision:String(record.value.chatAccess?.revision||0),mode:accessMode.value,...(playerId?{playerId,entry}:{}),operationId:crypto.randomUUID()},notice);}finally{record.busy=false;}};
    button('保存响应模式',accessActions,()=>saveAccess());button('加入允许列表',accessActions,()=>accessPlayers.value&&saveAccess('allow',accessPlayers.value));button('始终拒绝此玩家',accessActions,()=>accessPlayers.value&&saveAccess('deny',accessPlayers.value));record.removeAccess=id=>saveAccess('remove',id);
    record.collaborator=collaborator;cards.set(a.id,record);return record;
  }
  function updateCard(c,a){
    if(!c.draft.update(a))return;
    if(c.value.revision!==a.revision||!a.canManage){c.confirmation.hidden=true;c.check.checked=false;}
    c.skinButton.disabled=!a.canConfigureModel;c.modelButton.disabled=!a.canConfigureModel;c.modelButton.textContent='模型：'+(a.modelLabel||'默认');
    c.access.root.hidden=!a.mine;const policySignature=JSON.stringify([a.chatAccess,roster?.players]);if(c.access.signature!==policySignature){c.access.signature=policySignature;c.access.mode.value=a.chatAccess?.mode||'ASK';c.access.players.replaceChildren();add('option','选择玩家',c.access.players).value='';for(const p of roster?.players||[])if(p.id!==a.ownerId)add('option',p.name,c.access.players).value=p.id;c.access.list.replaceChildren();for(const kind of ['allow','deny'])for(const id of a.chatAccess?.[kind]||[]){const line=add('div',null,c.access.list,'actions');add('span',(kind==='allow'?'允许：':'拒绝：')+(roster?.players?.find(p=>p.id===id)?.name||id),line);button('移除',line,()=>c.removeAccess(id));}}
    c.value=a;c.title.textContent=a.name;c.badge.textContent=bodyLabels[a.bodyState]||'未知状态';c.badge.dataset.state=a.bodyState;
    c.info.textContent=`${a.mine?'你的 AI':'其他玩家的 AI'} · 请求${modeLabel(a.requestedMode)} / 实际${modeLabel(a.effectiveMode)}${a.health==null?'':` · 生命 ${a.health} / 饱食 ${a.food}`}`;
    c.info.textContent+=` · 附加区块票：${({GRANTED:'已分配',LIMIT_REACHED:'预算不足（依赖已加载区块）',DISABLED:'管理员已禁用',PENDING:'等待应用',DEGRADED:'登记异常',INACTIVE:'身体当前不需要'})[a.ticketState]||'等待状态'}`;
    if(!c.draft.nameDirty)c.name.value=c.draft.name;if(!c.draft.modeDirty)c.mode.value=c.draft.mode;
    if(c.draft.stale)error(c.notice,new Error('STALE_AGENT_REVISION'));
    c.form.disabled=c.busy||!a.canManage;c.collab.container.hidden=!a.canCollaborate;
    const signature=JSON.stringify([a.collaborators,roster?.players]);if(c.collab.signature!==signature){c.collab.signature=signature;c.collab.grants.replaceChildren();
      for(const id of a.collaborators||[]){const line=add('div',null,c.collab.grants,'actions');add('span',roster?.players?.find(p=>p.id===id)?.name||id,line);button('移除',line,()=>c.collaborator(id,false));}
      const previous=c.collab.select.value;c.collab.select.replaceChildren();add('option','选择在线玩家',c.collab.select).value='';for(const p of roster?.players||[])add('option',p.name,c.collab.select).value=p.id;c.collab.select.value=previous;
    }
  }
  function render(){if(!root?.isConnected||!root.querySelector('#agent-management-status'))return;const list=root.querySelector('#agent-management-list'),status=root.querySelector('#agent-management-status');
    if(!roster){status.textContent='正在读取 AI 与权限…';return;}
    status.textContent=`${roster.total??roster.agents.length} 个 AI · 不限创建数量`;const paging=root.querySelector('#agent-management-paging')||add('div',null,status.parentNode,'actions');paging.id='agent-management-paging';paging.replaceChildren();const change=offset=>request({kind:'page',offset,operationId:crypto.randomUUID()},status);const previous=button('上一页',paging,()=>change(Math.max(0,(roster.offset||0)-16)));previous.disabled=!(roster.offset>0);const next=button('下一页',paging,()=>change(roster.nextOffset));next.disabled=!(roster.nextOffset>=0);createFields.disabled=createBusy||!roster.canCreate||roster.agents.length>=roster.maximum;
    if(roster.agents.length>=roster.maximum)status.textContent+=' · 已达新建上限，现有 AI 保留；管理员可在“配置与权限 → 运行资源”调整。';
    for(const [id,c] of cards)if(!roster.agents.some(a=>a.id===id)){c.node.remove();cards.delete(id);}
    let empty=list.querySelector('.agent-empty');if(!roster.agents.length){if(!empty)add('p','还没有 AI。创建后可开始对话或通用任务。',list,'agent-empty muted');}else empty?.remove();
    let index=0;for(const a of [...roster.agents].sort((a,b)=>Number(b.mine)-Number(a.mine))){const c=cards.get(a.id)||createCard(a);updateCard(c,a);if(list.children[index]!==c.node)list.insertBefore(c.node,list.children[index]||null);index++;}
  }
  function open(){const next=windowFor('runtime-agent-management','AI 管理');if(next!==root){epoch++;root=next;cards.clear();}
    if(!root.childElementCount){
      add('p','管理持久 AI。加载中、等待复活和观战不等于可以执行动作；请求模式与当前身体模式分别显示。',root,'muted');add('p','正在读取…',root,'muted').id='agent-management-status';
      const createPanel=add('details',null,root,'agent-create');createPanel.id='agent-create-panel';createPanel.open=!roster?.agents?.some(a=>a.mine);add('summary','创建新 AI',createPanel);
      const create=add('form',null,createPanel);createFields=add('fieldset',null,create);const fields=add('div',null,createFields,'agent-fields');
      const name=label('名称（1–32 字）',document.createElement('input'),fields);name.id='agent-create-name';name.maxLength=64;name.autocomplete='off';
      const mode=label('初始模式',document.createElement('select'),fields);mode.id='agent-create-mode';add('option','创造',mode).value='CREATOR';add('option','生存',mode).value='SURVIVAL';
      const submit=add('button','创建 AI',createFields);submit.id='agent-create-submit';submit.type='submit';const notice=add('p','',create,'muted');notice.setAttribute('role','status');
      name.oninput=mode.onchange=()=>createOperation.reset();
      create.onsubmit=async event=>{event.preventDefault();if(createFields.disabled)return;const nameValue=name.value.trim();if(!nameValue||[...nameValue].length>32||/[\u0000-\u001f\u007f]/.test(nameValue)){error(notice,new Error('AGENT_NAME_INVALID'));return;}
        createBusy=true;createFields.disabled=true;await request(createOperation.request({kind:'create',name:nameValue,mode:mode.value},()=>crypto.randomUUID()),notice,v=>{notice.textContent=`AI 已创建 · ${bodyLabels[v.bodyState]||v.bodyState}`;name.value='';createOperation.reset();createPanel.open=false;});createBusy=false;render();
      };
      add('div',null,root).id='agent-management-list';
    }render();
  }
  return {open,update(value){if(!Array.isArray(value?.agents))return;roster=value;render();},session(value){epoch++;const key=JSON.stringify([value?.binding?.worldId,value?.binding?.viewerPlayerId]);if(scope&&scope!==key){root?.replaceChildren();cards.clear();roster=null;createOperation.reset();}scope=key;for(const c of cards.values()){c.check.checked=false;c.confirmation.hidden=true;}},reset(){epoch++;roster=null;for(const c of cards.values())c.form.disabled=true;}};
}
