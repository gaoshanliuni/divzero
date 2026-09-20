// Local desktop state is not a package execution permission. AI receives only the selected title and pin state.
export function createDesktopWindows({state,nodes,windowFor,send,pin,toggle,persist,agents}){
  let pane,list,target,agent,goal,custom,start,stop,note,flight=null,generation=0;
  function el(tag,text,parent){const n=document.createElement(tag);if(text!=null)n.textContent=text;parent?.append(n);return n;}
  function options(select,items){const value=select.value,signature=JSON.stringify(items);if(select.dataset.options===signature)return;select.dataset.options=signature;select.replaceChildren();for(const [id,label]of items){const o=el('option',label,select);o.value=id;}if(items.some(x=>x[0]===value))select.value=value;}
  function refresh(){
    if(!pane?.isConnected)return;
    const views=[...state.views].filter(([id])=>id!=='runtime-windows');
    options(target,views.filter(([,v])=>v.mode!=='MODAL').map(([id])=>[id,nodes.get(id)?.dataset.title||id]));
    options(agent,agents().map(a=>[a.id,a.name]));
    const signature=JSON.stringify(views.map(([id,v])=>[id,nodes.get(id)?.dataset.title,v.pinned,v.minimized,state.visible(id)]));
    if(list.dataset.state!==signature){list.dataset.state=signature;list.replaceChildren();
      if(!views.length)el('p','还没有其它窗口。先从工具栏打开对话、任务或内容。',list);
      for(const [id,v]of views){const row=el('div',null,list);row.className='window-control-row';el('strong',nodes.get(id)?.dataset.title||id,row);el('span',v.minimized?'已隐藏':v.pinned?'游戏中悬浮':'仅桌面显示',row).className='muted';const show=el('button',v.minimized?'显示':'隐藏',row);show.onclick=()=>toggle(id);if(v.mode!=='MODAL'){const b=el('button',v.pinned?'取消悬浮':'固定悬浮',row);b.setAttribute('aria-pressed',String(v.pinned));b.onclick=()=>pin(id,!v.pinned);}}
    }
    start.disabled=!!flight||!target.value||!agent.value;stop.disabled=!flight;
  }
  async function rpc(kind,data){const r=await send('desktopWindow',{kind,...data});if(!['APPLIED','ACCEPTED','OBSERVED'].includes(r.code))throw new Error(r.values?.errorCode||r.code||'WINDOW_AI_REQUEST_FAILED');if(r.values?.errorCode)throw new Error(r.values.errorCode);return r.values;}
  function cancel(){generation++;const old=flight;flight=null;if(old){rpc('cancel',{operationId:old.id}).catch(()=>{});if(note)note.textContent='已停止本次窗口调整；迟到回复不会改变窗口。';}refresh();}
  async function run(){
    if(flight)return;const view=state.views.get(target.value);if(!view||view.mode==='MODAL')return;
    const token=++generation,id=crypto.randomUUID(),viewId=target.value,revision=view.windowRevision;
    const prompt=goal.value==='custom'?custom.value.trim():goal.options[goal.selectedIndex].textContent;
    if(!prompt){note.textContent='请输入调整需求，或选择一种常用需求。';return;}
    flight={id};note.textContent='AI 正在判断悬浮方式；将调用当前配置模型，不重复请求。';refresh();
    try{
      let result=await rpc('start',{operationId:id,agentId:agent.value,title:nodes.get(viewId).dataset.title,pinned:String(view.pinned),prompt});
      while(result.state==='PLANNING'&&generation===token){await new Promise(r=>setTimeout(r,750));if(generation!==token)return;result=await rpc('read',{operationId:id});}
      if(generation!==token)return;
      if(result.state!=='READY')throw new Error(result.errorCode||result.state||'WINDOW_AI_FAILED');
      if(!state.interacting||!state.workspaceVisible||state.views.get(viewId)!==view||view.windowRevision!==revision)throw new Error('窗口状态已变化，未应用旧建议。');
      if(!['true','false'].includes(result.pinned))throw new Error('WINDOW_AI_INVALID_RESULT');
      flight=null;pin(viewId,result.pinned==='true');
      note.textContent=result.pinned==='true'?'AI 已将该窗口固定悬浮；返回游戏仍可见。':'AI 已取消该窗口悬浮；仅在桌面交互时可见。';
      try{await persist();}catch{note.textContent+=' 当前显示已改变，但偏好保存未确认。';}
    }catch(error){if(generation===token)note.textContent='未完成窗口调整：'+error.message+'；不会自动重试模型请求。';}
    finally{if(generation===token){flight=null;refresh();}}
  }
  function open(){
    pane=windowFor('runtime-windows','窗口管理');if(pane.dataset.desktopReady){refresh();return;}
    pane.dataset.desktopReady='true';el('p','每个窗口独立显示、隐藏或固定悬浮。F2 / Esc 返回游戏后，已固定且未隐藏的窗口继续显示，但不抢键盘和鼠标。',pane);
    list=el('div',null,pane);list.className='window-controls-list';
    const form=el('section',null,pane);form.className='window-agent';el('h3','让 AI 调整一个窗口',form);
    const a=el('label','AI',form);agent=el('select',null,a);agent.id='desktop-agent';
    const t=el('label','目标窗口',form);target=el('select',null,t);target.id='desktop-target';
    const g=el('label','调整需求',form);goal=el('select',null,g);goal.id='desktop-goal';options(goal,[['pin','返回游戏时也一直显示'],['unpin','只在 F2 桌面中显示'],['custom','自定义需求']]);
    const c=el('label','说明',form);custom=el('textarea',null,c);custom.maxLength=2048;custom.addEventListener('input',cancel);custom.placeholder='例如：战斗时也需要随时查看这个窗口';c.hidden=true;goal.onchange=()=>{cancel();c.hidden=goal.value!=='custom';};target.onchange=cancel;agent.onchange=cancel;
    const buttons=el('div',null,form);buttons.className='actions';start=el('button','让 AI 调整悬浮',buttons);start.id='desktop-ai-start';start.onclick=run;stop=el('button','停止',buttons);stop.onclick=cancel;
    note=el('p','只调整你选中的窗口，不读取窗口内容，不修改游戏世界。',form);note.id='desktop-ai-status';note.setAttribute('role','status');refresh();
  }
  return {open,refresh,cancel};
}
