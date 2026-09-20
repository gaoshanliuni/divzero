export function createBootExtensionCards({windowFor,send}) {
  let root=null,ready=false,scope='',epoch=0,serial=0,reading=false,writing=false,offset=0,planOffset=0,planView=null,target='',head=null,view=null,operation='';
  const el=(tag,text,parent,cls)=>{const n=document.createElement(tag);if(text!=null)n.textContent=text;if(cls)n.className=cls;parent.append(n);return n;};
  const button=(text,parent,run,allowed=true)=>{const n=el('button',text,parent);n.type='button';n.disabled=!ready||writing||!allowed;n.onclick=()=>{if(ready&&!writing&&allowed)run();};return n;};
  const visible=()=>ready&&root?.isConnected&&root.getClientRects().length>0&&document.body.dataset.workspaceVisible!=='false';
  async function request(args,code='OBSERVED'){const r=await send('bootExtension',args);if(r.code!==code||r.values?.errorCode)throw new Error(r.values?.errorCode||r.code);return JSON.parse(r.values.state);}
  async function read(){if(!ready||writing||!root?.isConnected)return;const e=epoch,ticket=++serial,current=root;reading=true;
    try{
      if(target){const r=await send('packageCatalog',{kind:'package',packageId:target,headRevision:'0',headHash:''});if(r.code!=='OBSERVED'||r.values?.errorCode)throw new Error(r.values?.errorCode||r.code);if(e!==epoch||ticket!==serial)return;head=JSON.parse(r.values.state);}
      const state=await request({kind:'list',offset:String(offset),buildId:''});if(e!==epoch||ticket!==serial||current!==root||!current.isConnected)return;const plans=await request({kind:'plans',offset:String(planOffset),buildId:''});if(e!==epoch||ticket!==serial||current!==root||!current.isConnected)return;view=state;planView=plans;render();
    }catch(error){if(e===epoch&&ticket===serial&&current.isConnected){current.replaceChildren();el('p',error.message,current,'error');button('只读刷新，不重发原操作',current,()=>void read());button('查看全局自有构建记录',current,()=>{target='';head=null;offset=0;void read();});}}
    finally{if(e===epoch&&ticket===serial)reading=false;}
  }
  function render(){root.replaceChildren();el('h3','启动扩展 · 服务端所在 JVM',root);el('p','实际 Loader mods 目录：'+view.modsDirectory,root,'muted');
    el('p','这不是热加载：安装会修改服务端所在机器 / JVM 的全局 mods，影响其所有世界和其他玩家；单机也影响菜单与后续世界。不是给远端玩家客户端安装。批准持续有效，每次启动都按普通 Mod 生命周期初始化；若初始化失败，不会自动移出，需停机恢复。扩展可包含任意 Native Java、AT 或 Mixin；重启可能因兼容问题失败。移出只阻止后续启动加载，不回滚已保存世界数据或当前 JVM 的注册、线程和事件。存档引用的注册项可能因此缺失，导致无法开档或内容丢失；请先迁移 / 备份，此入口不自动迁移。',root,'warning');
    el('p','先备份并停止共用这个 mods 目录的其它 JVM。坏扩展导致无法启动时，退出游戏/服务端后使用交付的 Remove-MineAgentBootExtension.ps1，仅处理准确构建和 hash。',root,'muted');
    const top=el('div',null,root,'actions');button('只读刷新',top,()=>void read());button('清除当前包选择',top,()=>{target='';head=null;void read();});if(operation)el('p','本次操作：'+operation,root,'muted');
    if(head){el('p','已经安装此包时，请在下方准确旧构建上选择替换编译；无需先卸载正在运行的版本。',root,'muted');const box=el('section',null,root,'event-card');el('strong','待构建：'+head.name+' · r'+head.revision,box);el('p','只从该签名版本读取 Java / 资源，使用实际 Native 快照，不调用模型，不安装、不运行扩展。',box,'muted');button('构建此 BOOT_EXTENSION',box,()=>void mutate({kind:'build',packageId:target,packageRevision:String(head.revision),canonical:head.canonicalSha256,environment:view.environment,replacementBuildId:''}),!view.busy&&head.bootAvailable);}
    else el('p','从「包目录 → 构建 / 安装启动扩展」选择源包。下方按本人全局构建记录管理；库 enabled 不代表 Loader 已载入。',root,'muted');
    for(const b of view.builds){const card=el('article',null,root,'event-card');el('strong',b.name+' · '+(b.modId||'尚无编译产物'),card);el('p',b.id+' · '+b.phase+' · 文件 '+b.fileState+' · '+b.loader,card,'muted');
      el('p','构建来源 '+b.nativeClasspath+' · artifact '+b.artifact,card,'muted');if(b.error)el('p',b.error,card,'error');
      if(head&&b.packageId===target&&b.canonical!==head.canonicalSha256&&['INSTALLED_PENDING_RESTART','FILE_STATE_UNKNOWN'].includes(b.phase)&&b.fileState==='HASH_MATCHED')button('以此旧构建为前驱编译当前包',card,()=>void mutate({kind:'build',packageId:target,packageRevision:String(head.revision),canonical:head.canonicalSha256,environment:view.environment,replacementBuildId:b.id}),!view.busy);
      if(b.replaces)el('p','替换前驱 '+b.replaces+' · 稳定 Loader 槽位 '+b.filename,card,'muted');
      el('p','启动依赖可引用的包 ID：'+b.packageId+' · 准确版本 '+b.version+'；不要使用构建 ID。',card,'muted');
      const details=el('section',null,card);if(b.dependencyCount)button('检查启动依赖 / 构建绑定',card,()=>void dependencyDetails(b.id,0,details));if(b.diagnostics)button('查看保留的编译诊断（分页）',card,()=>void diagnostics(b.id,0,details));
      if(b.loader==='LOADER_CONSTRUCTOR_RETURNED')el('p','Loader 原来源已核对且初始化返回；这不等于该扩展的玩法效果已验收。',card,'muted');
      if(!view.busy&&['BUILT','INSTALLED_PENDING_RESTART','FILE_STATE_UNKNOWN','REMOVED_PENDING_RESTART'].includes(b.phase)){
        const confirm=el('section',null,card,'event-confirm'),label=el('label',null,confirm),check=el('input',null,label);check.type='checkbox';label.append(document.createTextNode('我已核对全局目录、来源及重启影响，明确执行下方动作；已有备份且没有其他 JVM 共用此目录。'));
        el('p','已选择准确 modId：'+b.modId+'。身份来自当前构建记录，不要求手动输入或复制。',confirm,'muted');
        const action=b.phase==='BUILT'?(b.replaces?'STAGE_UPGRADE':'INSTALL'):'REMOVE';button(action==='STAGE_UPGRADE'?'批准并暂存停机替换计划':action==='INSTALL'?'全局安装，下一次重启加载':'全局移出，下次重启停用',confirm,()=>{
          if(!check.checked){el('p','必须勾选确认当前所选构建与 modId。',confirm,'warning');return;}
          void mutate(action==='STAGE_UPGRADE'?{kind:'stageUpgrade',buildId:b.id,expectedRevision:String(b.revision),environment:view.environment,confirmed:'true',confirmModId:b.modId}:{kind:'change',buildId:b.id,expectedRevision:String(b.revision),action,environment:view.environment,confirmed:'true',confirmModId:b.modId});
        });
      }
      if(!view.busy&&['BUILDING','CACHING','INSTALLING','REMOVING'].includes(b.phase))el('p','结果收束未知；不要重发原操作。先查看文件 / Loader 状态，必要时停机恢复。',card,'warning');
    }
    const pages=el('div',null,root,'actions');button('上一页',pages,()=>{offset=Math.max(0,offset-8);void read();},offset>0);button('下一页',pages,()=>{offset=view.nextOffset;void read();},view.more);
    el('h3','已批准的停机替换计划',root);
    const ps=value=>"'"+String(value).replaceAll("'","''")+"'";
    for(const p of planView?.plans||[]){const box=el('article',null,root,'event-card');el('strong',p.modId+' · '+p.phase,box);el('p',p.operation+' · plan SHA '+p.planHash,box,'muted');el('p','旧 '+p.previousArtifact+' → 新 '+p.nextArtifact+' · 槽位 '+p.filename,box,'muted');el('p','批准文件 '+p.approvedFile+' · 取消标记 '+p.cancelledFile+' · apply 回执 '+p.applyReceipt+' · rollback 回执 '+p.rollbackReceipt,box,'muted');if(p.error)el('p',p.error,box,'error');
      el('p','这一步尚未自动修改 Mod 文件。停止所有共用目录的 JVM 后，在源码仓库运行以下预览命令；核对后去掉 -WhatIf。-Action rollback 使用同一计划明确回退文件，不回滚世界数据。',box,'warning');
      el('pre','pwsh -NoProfile -File .\\tools\\Update-MineAgentBootExtension.ps1 -GameDirectory '+ps(view.gameDirectory)+' -ModsDirectory '+ps(view.modsDirectory)+' -OperationId '+ps(p.operation)+' -PlanHash '+ps(p.planHash)+' -ConfirmModId '+ps(p.modId)+' -JvmStopped -Action apply -WhatIf',box,'event-json');
      if(['PREPARING','WAIT_OFFLINE','PLAN_WRITE_FAILED','CANCEL_FAILED'].includes(p.phase)&&!p.cancelledFile){const label=el('label',null,box),cancel=el('input',null,label);cancel.type='checkbox';label.append(document.createTextNode('明确取消尚未执行的计划；不回退已经替换的文件。'));button('取消此计划',box,()=>{if(cancel.checked)void mutate({kind:'cancelUpgrade',upgradeId:p.operation,planHash:p.planHash,confirmed:'true'});},!view.busy);}
    }
    const pp=el('div',null,root,'actions');button('前面计划',pp,()=>{planOffset=Math.max(0,planOffset-8);void read();},planOffset>0);button('后面计划',pp,()=>{planOffset=planView.nextOffset;void read();},planView?.more);
    el('p','当前入口不会自动重启、不会重放编译或安装，也不会把关闭浮窗当成撤销已发生的文件写入。纯 BOOT 源码包已接构建 / 全局安装路径；替换使用明确停机原子交换，启动依赖必须是已批准安装的准确BOOT版本，工具不会代装。多包原子升级、跨生命周期、早期兼容和远端客户端投递仍需独立流程。',root,'muted');
  }
  const dependencyReads=new WeakMap();
  async function dependencyDetails(id,page,area){const e=epoch,ticket=(dependencyReads.get(area)||0)+1;dependencyReads.set(area,ticket);try{const data=await request({kind:'dependencies',buildId:id,offset:String(page)});if(e!==epoch||dependencyReads.get(area)!==ticket||!area.isConnected)return;area.replaceChildren();el('p',data.mode+' · 当前实际图匹配 '+data.currentMatches,area,'muted');if(data.error)el('p',data.error,area,'error');el('pre','原声明 '+JSON.stringify(data.declared,null,2)+'\n绑定/观察到的节点 '+JSON.stringify(data.nodes,null,2),area,'event-json');button('上一组依赖',area,()=>void dependencyDetails(id,Math.max(0,page-8),area),page>0);button('下一组依赖',area,()=>void dependencyDetails(id,data.nextOffset,area),data.more);}catch(error){if(e===epoch&&dependencyReads.get(area)===ticket&&area.isConnected)el('p',error.message,area,'error');}}
  async function diagnostics(id,page,area){const e=epoch;try{const data=await request({kind:'diagnostics',buildId:id,offset:String(page)});if(e!==epoch||!area.isConnected)return;area.replaceChildren();el('pre',data.text,area,'event-json');button('上一段',area,()=>void diagnostics(id,Math.max(0,page-4096),area),page>0);button('下一段',area,()=>void diagnostics(id,data.nextOffset,area),data.more);}catch(error){if(e===epoch&&area.isConnected)el('p',error.message,area,'error');}}
  async function mutate(args){if(!ready||writing)return;const e=epoch,current=root;operation=crypto.randomUUID();planOffset=0;serial++;reading=false;writing=true;current.querySelectorAll('button,input').forEach(n=>n.disabled=true);
    try{await request({...args,operationId:operation},'ACCEPTED');if(e!==epoch)return;writing=false;await read();}
    catch(error){if(e===epoch&&current.isConnected){writing=false;current.replaceChildren();el('p',error.message+' · 原操作 '+operation+' 未自动重发。',current,'error');button('只读核查当前记录',current,()=>void read());}}
    finally{if(e===epoch)writing=false;}
  }
  function open(packageId=''){root=windowFor('runtime-boot-extensions','启动扩展构建与安装');epoch++;serial++;target=typeof packageId==='string'?packageId:'';head=view=planView=null;operation='';offset=planOffset=0;reading=writing=false;root.replaceChildren();el('p','读取本人构建与全局 Loader 状态…',root,'muted');if(ready)void read();}
  function session(value){const key=JSON.stringify([value?.sessionId,value?.serverInstanceId,value?.binding?.worldId,value?.binding?.viewerPlayerId]);if(key===scope&&ready===!!value)return;scope=key;ready=!!value;epoch++;serial++;reading=writing=false;target=operation='';head=view=planView=null;root?.replaceChildren();if(root?.isConnected)el('p','上下文变化，请重新打开启动扩展管理。',root,'muted');}
  setInterval(()=>{if(visible()&&!reading&&!writing&&view&&(view.busy||view.builds.some(b=>['BUILDING','CACHING','INSTALLING','REMOVING'].includes(b.phase))))void read();},2500);
  return {open,session,reset:()=>session(null)};
}
