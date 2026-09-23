export function createResourcePackCards({windowFor,send}) {
  let root=null,epoch=0,serial=0,ready=false,scope='',packageId='',head=null,view=null,offset=0,reading=false,writing=false,message='';
  const el=(tag,text,parent,cls)=>{const node=document.createElement(tag);if(text!=null)node.textContent=text;if(cls)node.className=cls;parent.append(node);return node;};
  const button=(text,parent,run,allowed=true)=>{const b=el('button',text,parent);b.type='button';b.disabled=!ready||!allowed;b.onclick=()=>{if(ready&&!writing&&allowed)run();};return b;};
  const visible=()=>ready&&root?.isConnected&&document.body.dataset.workspaceVisible!=='false'&&root.getClientRects().length>0;
  async function read(){if(!ready||writing||!root?.isConnected)return;const generation=epoch,ticket=++serial,current=root;reading=true;
    try{if(packageId){const result=await send('packageCatalog',{kind:'package',packageId,headRevision:'0',headHash:''});if(result.code!=='OBSERVED'||result.values?.errorCode)throw new Error(result.values?.errorCode||result.code);if(generation!==epoch||ticket!==serial)return;head=JSON.parse(result.values.state);}
      const result=await send('resourcePack',{kind:'read',offset:String(offset)});if(result.code)throw new Error(result.code);if(generation!==epoch||ticket!==serial||current!==root||!current.isConnected)return;view=result;render();
    }catch(error){if(generation===epoch&&ticket===serial&&current===root&&current.isConnected){current.replaceChildren();el('p',error.message,current,'error');button('只读重试',current,()=>void read());}}
    finally{if(generation===epoch&&ticket===serial)reading=false;}
  }
  function render(){root.replaceChildren();el('h3','本机资源包 · 独立确认',root);el('p','当前客户端 '+view.client.minecraft+' / NeoForge '+view.client.loaderVersion+' / Java '+view.client.javaFeature,root,'muted');
    el('p','服务端批准与下载都不等于本机加载许可。启用会修改本机全局资源选择，影响菜单、其它世界及共用此游戏目录的账号；纹理、字体、声音、shader 等由 Minecraft/Mod 解析。这里不运行包的 Runtime Java/Rhino 入口。',root,'warning');
    el('p','重载可能耗时。Vanilla 失败恢复可能清空其它已选资源包；原选择留在本地操作记录，不自动重新应用。关闭窗口或断线不能回滚已经开始的全局重载。',root,'warning');if(message)el('p',message,root,'muted');
    const tools=el('div',null,root,'actions');button('只读刷新本机状态',tools,()=>void read());if(head?.resourcePackAvailable){el('p','所选服务器包：'+head.name,root,'muted');button('下载并验签（不启用）',tools,()=>void download(),!view.busy);}if(view.download?.packageId){el('p','下载 '+view.download.received+' / '+view.download.total+' bytes',root,'muted');button('取消下载',tools,()=>void cancelDownload());}
    if(view.downloadOutcome)el('p','最近下载结果：'+view.downloadOutcome,root,'muted');
    if(view.persistingResult)el('p','正在收束本地结果，未重复重载。',root,'warning');
    for(const asset of view.items){const card=el('article',null,root,'event-card');el('strong',asset.name,card);el('p',asset.state+' · 当前 ResourceManager '+(asset.loadedNow?'已装入':'未装入'),card,'muted');el('p','来源 fingerprint '+asset.fingerprint+' · '+asset.canonical,card,'muted');if(asset.error)el('p',asset.error,card,'warning');
      if(asset.job?.operation){el('p',asset.job.phase+' · '+asset.job.code,card,'muted');const details=el('details',null,card);el('summary','操作前/后选择（各显示前 8 条）',details);el('pre',JSON.stringify({beforeCount:asset.job.beforeCount,before:asset.job.before,afterCount:asset.job.afterCount,after:asset.job.after},null,2),details,'event-json');}
      const label=el('label',null,card,'event-confirm'),confirm=el('input',null,label);confirm.type='checkbox';label.append(document.createTextNode('明确允许本次本机全局资源变更；停用不等于回滚其它包和已发生效果。'));
      button('本机启用并重载',card,()=>void change(asset,'ENABLE',confirm.checked),asset.canEnable);button('本机停用同来源包并重载',card,()=>void change(asset,'DISABLE',confirm.checked),asset.canDisable);
    }
    const pages=el('div',null,root,'actions');button('上一页本机记录',pages,()=>{offset=Math.max(0,offset-8);void read();},offset>0);button('下一页本机记录',pages,()=>{offset=view.nextOffset;void read();},view.more);el('p','资源选中和字节匹配不证明图像、音效或 shader 的最终效果。来源信任/客户端环境变化会阻止下次打开；已加载资源需明确停用。服务器离线时可从主菜单管理本机已缓存包。',root,'muted');
  }
  async function perform(payload){const generation=epoch,current=root;writing=true;serial++;reading=false;current.querySelectorAll('button,input').forEach(n=>n.disabled=true);
    try{const result=await send('resourcePack',payload);if(generation!==epoch||root!==current||!current.isConnected)return;if(!['ACCEPTED','DOWNLOADED_NOT_APPROVED','RESOURCE_PACK_DOWNLOAD_CANCELLED'].includes(result.code))throw new Error(result.code||'RESOURCE_PACK_FAILED');message=result.code==='DOWNLOADED_NOT_APPROVED'?'下载验签完成，尚未批准或启用。':'已受理；请以当前本机结果为准。';writing=false;await read();}
    catch(error){if(generation===epoch&&root===current&&current.isConnected){writing=false;current.replaceChildren();el('p',error.message+'；先读取当前状态，不自动重发。',current,'error');button('读取当前本机状态',current,()=>void read());}}
    finally{if(generation===epoch)writing=false;}
  }
  async function download(){await perform({kind:'download',packageId,packageRevision:String(head.revision),canonical:head.canonicalSha256});}
  async function cancelDownload(){await perform({kind:'cancelDownload'});}
  async function change(asset,action,confirmed){if(!confirmed){el('p','请先勾选本机全局变更确认。',root,'warning');return;}await perform({kind:'change',operationId:crypto.randomUUID(),filename:asset.filename,action,revision:String(asset.revision),selection:view.selection,environment:view.environment,confirmed:'true'});}
  function open(id=''){epoch++;serial++;packageId=id;head=view=null;offset=0;writing=reading=false;message='';root=windowFor('runtime-resource-packs','本机资源包');root.replaceChildren();el('p','只读查询本机资源…',root,'muted');if(ready)void read();}
  function session(value){const key=JSON.stringify([value?.sessionId,value?.serverInstanceId,value?.binding?.worldId,value?.binding?.viewerPlayerId]);if(scope===key&&ready===!!value)return;scope=key;ready=!!value;epoch++;serial++;head=view=null;writing=reading=false;root?.replaceChildren();if(root?.isConnected)el('p','上下文已变化，请重新打开本机资源管理。',root,'muted');}
  setInterval(()=>{if(visible()&&!reading&&!writing&&view?.busy)void read();},2000);
  return {open,session,reset:()=>session(null)};
}
