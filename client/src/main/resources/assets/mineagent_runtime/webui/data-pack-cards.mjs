export function createDataPackCards({windowFor,send,openCompatibility}) {
  let root=null,epoch=0,serial=0,scope='',ready=false,id='',head=null,view=null,operation='',offset=0,reading=false,writing=false;
  const el=(tag,text,parent,cls)=>{const node=document.createElement(tag);if(text!=null)node.textContent=text;if(cls)node.className=cls;parent.append(node);return node;};
  const button=(text,parent,run,allowed=true)=>{const node=el('button',text,parent);node.type='button';node.disabled=!ready||!allowed;node.onclick=()=>{if(ready&&!writing&&allowed)run();};return node;};
  const visible=()=>ready&&root?.isConnected&&document.body.dataset.workspaceVisible!=='false'&&root.getClientRects().length>0;
  async function read(fresh=false){if(!ready||writing||!root?.isConnected)return;const generation=epoch,ticket=++serial,current=root;reading=true;
    try{
      if(fresh||!head){const r=await send('packageCatalog',{kind:'package',packageId:id,headRevision:'0',headHash:''});if(r.code!=='OBSERVED'||r.values?.errorCode)throw new Error(r.values?.errorCode||r.code);if(generation!==epoch||ticket!==serial)return;head=JSON.parse(r.values.state);}
      const response=await send('dataPack',{kind:'read',packageId:id,packageRevision:String(head.revision),offset:String(offset),operationId:operation});if(response.code!=='OBSERVED'||response.values?.errorCode)throw new Error(response.values?.errorCode||response.code);
      if(generation!==epoch||ticket!==serial||current!==root||!current.isConnected)return;view=JSON.parse(response.values.state);render();
    }catch(error){if(generation===epoch&&ticket===serial&&current===root&&current.isConnected){current.replaceChildren();el('p',error.message,current,'error');button('重新读取当前包',current,()=>void read(true));}}
    finally{if(generation===epoch&&ticket===serial)reading=false;}
  }
  function render(){root.replaceChildren();const reopen=view.mode==='WORLD_REOPEN';el('h3',(reopen?'世界重开计划 · ':'数据包生命周期 · ')+view.name,root);el('p',id+' · r'+view.packageRevision+' · '+view.mode,root,'muted');
    el('p','当前 ResourceManager 中的本版本：'+(view.loadedNow?'已装入':'未装入')+'；库 enabled 不等于本世界数据包生效。',root,'muted');
    if(view.error)el('p',view.error,root,'warning');if(!view.hookReady)el('p','数据包加载保护未就绪，不能安装。',root,'error');
    el('p',reopen?'暂存仅保存下一次数据包选择，不在当前世界热加载维度/世界生成。明确保存后需由你退出并重新打开世界；正常开启会构建 Registry、维度并可能执行 load 函数。不会重建旧区块或删除原世界数据。尚未生效时可撤销；已经装入后的退役/迁移不是取消计划。':'本操作会重载全服数据并保存当前世界，可能触发本包及其它选中数据包的 load 函数。安装成功后随此存档正常开启/重载，load 函数可能再次执行。不是预览；开始重载后不能把关闭窗口、超时或撤权视为已撤销。停用也需要一次全服数据重载，不删除既有方块/物品/比分等副作用。',root,'warning');
    const tools=el('div',null,root,'actions');button('只读刷新当前状态',tools,()=>void read(true));button('查看兼容声明',tools,()=>openCompatibility(id));
    const job=view.job;if(job?.input){el('p','操作 '+job.input.operation+' · '+job.input.action+' · '+job.phase+' · '+(job.code||'等待处理'),root,'muted');if(job.input.canonical!==view.canonical)el('p','以上操作属于旧包 hash，不代表当前版本已启用。',root,'warning');}
    if(view.reopenPlan?.nativeStarted)el('p','服务器已开始构建此计划；不能把撤销计划当作已加载世界定义的退役。',root,'warning');if(view.reopenPlan?.operation){el('p','重开计划 '+view.reopenPlan.operation+' · '+view.reopenPlan.state+' · '+(view.reopenPlan.code||''),root,'warning');el('p','暂存不是已生效；关闭窗口不会自动重开。CANCEL_REQUESTED 表示撤销已记录，需正常重开原世界确认，不能重复提交。若世界无法打开，主菜单可撤销未生效计划，专用服务端可按说明使用精确 operation 的启动恢复参数。',root,'muted');}if(job?.phase==='LOADED_UNVERIFIED')el('p','Native 已装入，但结果验证未通过；不能当作需求完成。',root,'warning');
    if(view.persistingResult)el('p','Native 已返回，正在收束持久结果；不重新执行重载。',root,'warning');
    const list=el('section',null,root);for(const a of view.artifacts){const card=el('article',null,list,'event-card');el('strong',a.state+' · 当前实际 '+(a.loadedNow?'装入':'未装入'),card);el('p',a.file,card,'muted');el('p','SHA-256 '+a.zipHash,card,'muted');if(a.admissionError)el('p','最近一次加载准入：'+a.admissionError,card,'warning');}
    const pages=el('div',null,root,'actions');button('上一页安装记录',pages,()=>{offset=Math.max(0,offset-8);void read();},offset>0);button('下一页安装记录',pages,()=>{offset=view.nextOffset;void read();},view.more);
    if(view.canEnable||view.canDisable||view.canStageReopen||view.canCancelReopen){const box=el('section',null,root,'event-confirm'),label=el('label',null,box),confirmed=el('input',null,label);confirmed.type='checkbox';label.append(document.createTextNode('我已核对当前包、环境、持久选择及生命周期影响，明确执行下方选定操作；暂存不是当前生效。'));
      if(!reopen)button('安装当前版本并重载',box,()=>void change('ENABLE',confirmed.checked),view.canEnable);if(reopen)button('保存待重开计划',box,()=>void change('ENABLE_AT_REOPEN',confirmed.checked),view.canStageReopen);if(view.canCancelReopen)button('撤销尚未生效计划',box,()=>void change('CANCEL_REOPEN',confirmed.checked));if(!reopen)button('停用此包的数据并重载',box,()=>void change('DISABLE',confirmed.checked),view.canDisable);
    }
    el('p','重载成功与存档选中项回读只证明加载链，不证明每条配方/函数或需求效果已验收。未知结果不自动重发；已加载数据需明确停用重载，加载准入不是逐条原生命令沙箱。维度/启动期 Registry 只在真实重开后验证；已装入世界定义的删除/退役仍需专门迁移，不能通过普通数据重载绕过。',root,'muted');
  }
  async function change(action,confirmed){if(!confirmed){el('p','请先勾选明确确认。',root,'warning');return;}const generation=epoch,current=root;serial++;reading=false;writing=true;operation=crypto.randomUUID();
    const payload={kind:'change',operationId:operation,packageId:id,packageRevision:String(view.packageRevision),canonical:view.canonical,selection:view.selection,environment:view.environment,action,confirmed:'true',reopenOperation:action==='CANCEL_REOPEN'?view.reopenPlan.operation:''};current.querySelectorAll('button,input').forEach(n=>n.disabled=true);
    try{const response=await send('dataPack',payload);if(generation!==epoch||root!==current||!current.isConnected)return;if(response.code!=='ACCEPTED'||response.values?.errorCode)throw new Error(response.values?.errorCode||response.code);writing=false;await read(true);}
    catch(error){if(generation===epoch&&root===current&&current.isConnected){writing=false;current.replaceChildren();el('p',error.message+'；先查看原操作，不自动重发。',current,'error');button('读取原操作和当前状态',current,()=>void read(true));}}
    finally{if(generation===epoch)writing=false;}
  }
  function open(packageId){epoch++;serial++;id=packageId;head=view=null;operation='';offset=0;reading=writing=false;root=windowFor('runtime-data-pack','数据包安装与重载');root.replaceChildren();el('p','读取真实数据包状态…',root,'muted');if(ready)void read(true);}
  function session(value){const key=JSON.stringify([value?.sessionId,value?.serverInstanceId,value?.binding?.worldId,value?.binding?.viewerPlayerId]);if(key===scope&&ready===!!value)return;scope=key;ready=!!value;epoch++;serial++;head=view=null;operation='';reading=writing=false;root?.replaceChildren();if(root?.isConnected)el('p','上下文变化，请从当前包目录重新打开。',root,'muted');}
  setInterval(()=>{if(visible()&&!reading&&!writing&&view&&(view.persistingResult||['PREPARING','BUILDING','DISPATCHING','SAVING_REOPEN'].includes(view.job?.phase)))void read(true);},2500);
  return {open,session,reset:()=>session(null)};
}
