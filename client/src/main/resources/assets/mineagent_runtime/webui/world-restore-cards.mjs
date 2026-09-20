export function createWorldRestore({windowFor,send,openCompatibility}) {
  let root=null,epoch=0,serial=0,scope='',ready=false,activationId='',view=null,reading=false,writing=false,submitted='';
  const el=(tag,text,parent,cls)=>{const n=document.createElement(tag);if(text!=null)n.textContent=text;if(cls)n.className=cls;parent.append(n);return n;};
  const visible=()=>ready&&root?.isConnected&&document.body.dataset.workspaceVisible!=='false'&&root.getClientRects().length>0;
  const button=(text,parent,run,allowed=true)=>{const n=el('button',text,parent);n.type='button';n.disabled=!ready||!allowed;n.onclick=()=>{if(ready&&!writing&&allowed)run();};return n;};
  async function read(){
    if(!ready||!root?.isConnected||writing)return;const generation=epoch,ticket=++serial,current=root;reading=true;
    try{const response=await send('worldRestore',{kind:'read',activationId});if(response.code!=='OBSERVED'||response.values?.errorCode)throw new Error(response.values?.errorCode||response.code);
      if(generation!==epoch||ticket!==serial||root!==current||!current.isConnected)return;view=JSON.parse(response.values.state);render();
    }catch(error){if(generation===epoch&&ticket===serial&&root===current&&current.isConnected){current.replaceChildren();el('p',error.message,current,'error');button('重新读取当前实例',current,()=>void read());}}
    finally{if(generation===epoch&&ticket===serial)reading=false;}
  }
  function render(){
    root.replaceChildren();el('h3','恢复原实例 · '+view.packageName,root);el('p','activation '+view.activationId+' · instance '+view.instanceId,root,'muted');
    const status=el('p','当前 '+view.state+' · '+(view.error||'无错误记录'),root);status.setAttribute('role','status');
    el('p','包 r'+view.packageRevision+' · activation r'+view.activationRevision+' · 实例 r'+view.instanceRevision+' · '+view.canonical,root,'muted');
    el('pre',JSON.stringify(view.location,null,2),root,'event-json');el('p','中断阶段：'+view.restoreBlock+'；原自动恢复许可：'+(view.autoRestore?'已允许':'未允许'),root,'muted');
    if(submitted)el('p',submitted,root,'muted');
    el('p','只恢复这一实例的相同签名代码，保留 UUID、状态和已确认移动位置；执行 instance.restore，不重新执行 instance.create，不请求模型。恢复 callback 可能产生原生副作用，不承诺任意 Java 的回滚或强制终止。',root,'warning');
    const actions=el('div',null,root,'actions');button('查看此包兼容声明',actions,()=>openCompatibility(view.packageId));button('只读刷新',actions,()=>void read());
    if(view.canResume){
      el('p',view.chunkLoaded?'当前锚点区块已加载；提交后仍重新核验。':'锚点区块未加载：可明确排队，原区块自然加载后才执行；不强加载、不创建新实例。',root,'muted');
      const section=el('section',null,root,'event-confirm'),label=el('label',null,section),check=el('input',null,label);check.type='checkbox';
      label.append(document.createTextNode('我已核对当前实例、状态、包与环境，明确单独授权恢复原实例；不是仅记录兼容声明。'));
      button('确认恢复这个原实例',section,()=>void resume(check.checked));
    }else{
      el('p',view.state==='ACTIVE'?'当前脚本已处于 ACTIVE；这不是业务结果已验收。':['RESTORE_PENDING','RESTORING'].includes(view.state)?'恢复已排队/开始，不能重复提交；可见窗口仅轮询状态。':'当前不能恢复：'+view.blocked,root,'muted');
      if(!view.eligible&&view.state==='INTERRUPTED')el('p','旧记录没有可证明的中断阶段，或原自动恢复许可已撤销。未知执行结果不会被重新包装成可恢复；不会新建实例冒充恢复。',root,'warning');
    }
    if(['ACTIVE','RESTORE_PENDING','RESTORING'].includes(view.state))button('取消待恢复 / 停用受管脚本（保留世界数据）',root,()=>void stop());
  }
  async function mutation(channel,payload){
    const generation=epoch,current=root;serial++;reading=false;writing=true;current.querySelectorAll('button,input').forEach(n=>n.disabled=true);
    try{const result=await send(channel,payload);if(generation!==epoch||root!==current||!current.isConnected)return;
      if(!['ACCEPTED','APPLIED'].includes(result.code)||result.values?.errorCode)throw new Error(result.values?.errorCode||result.code);
      submitted=channel==='worldRestore'?'已保留明确恢复请求 '+payload.operationId+'；这是排队回执，不是已执行证明。':'停用操作已返回，下面另行读取当前状态。';
      writing=false;await read();
    }catch(error){if(generation===epoch&&root===current&&current.isConnected){writing=false;current.replaceChildren();el('p',error.message+'；先读取当前状态，不自动重发。',current,'error');button('读取当前状态',current,()=>void read());}}
    finally{if(generation===epoch)writing=false;}
  }
  async function resume(confirmed){if(!confirmed){el('p','请先明确勾选恢复授权。',root,'warning');return;}
    const payload={kind:'resume',activationId,operationId:crypto.randomUUID(),confirmed:'true'};for(const key of ['instanceId','canonical','activationRevision','packageRevision','instanceRevision','environment'])payload[key]=String(view[key]);await mutation('worldRestore',payload);
  }
  async function stop(){await mutation('packageAction',{action:'worldDisable',activationId,operationId:crypto.randomUUID()});}
  function open(id){epoch++;serial++;activationId=id;view=null;submitted='';reading=writing=false;root=windowFor('runtime-world-restore','原实例恢复与诊断');root.replaceChildren();el('p','只读核对原实例…',root,'muted');if(ready)void read();}
  function session(value){const key=JSON.stringify([value?.sessionId,value?.serverInstanceId,value?.binding?.worldId,value?.binding?.viewerPlayerId]);if(key===scope&&ready===!!value)return;scope=key;ready=!!value;epoch++;serial++;reading=writing=false;view=null;submitted='';root?.replaceChildren();if(root?.isConnected)el('p','上下文已变化，请从当前实例列表重新打开。',root,'muted');}
  setInterval(()=>{if(visible()&&!reading&&!writing&&view&&['RESTORE_PENDING','RESTORING'].includes(view.state))void read();},2500);
  return {open,session,reset:()=>session(null)};
}
