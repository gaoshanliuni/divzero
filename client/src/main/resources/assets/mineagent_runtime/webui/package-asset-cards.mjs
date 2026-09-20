export function createPackageAssetCards({windowFor,send,openTools,openCatalog}) {
  let root=null,ready=false,scope='',epoch=0,serial=0,busy=false,target='',head=null,state=null,offset=0,active=true,operation='';
  const el=(tag,text,parent,cls)=>{const n=document.createElement(tag);if(text!=null)n.textContent=text;if(cls)n.className=cls;parent.append(n);return n;};
  const button=(text,parent,run,allowed=true)=>{const n=el('button',text,parent);n.type='button';n.disabled=!ready||busy||!allowed;n.onclick=()=>{if(ready&&!busy&&allowed)run();};return n;};
  const consent=(text,parent)=>{const label=el('label',null,parent),box=el('input',null,label);box.type='checkbox';label.append(document.createTextNode(text));return box;};
  async function request(args,code='OBSERVED'){const r=await send('packageAssets',args);if(r.code!==code||r.values?.errorCode)throw new Error(r.values?.errorCode||r.code);return JSON.parse(r.values.state);}
  async function read(){if(!ready||busy||!root?.isConnected)return;const e=epoch,ticket=++serial,current=root,selected=target;let nextHead=null,nextState=null;
    try{
      if(selected){const r=await send('packageCatalog',{kind:'package',packageId:selected,headRevision:'0',headHash:''});if(r.code!=='OBSERVED'||r.values?.errorCode)throw new Error(r.values?.errorCode||r.code);if(e!==epoch||ticket!==serial)return;nextHead=JSON.parse(r.values.state);nextState=await request({kind:'inspect',packageId:selected,packageRevision:String(nextHead.revision),canonical:nextHead.canonicalSha256});}
      else nextState=await request({kind:'shelf',active:String(active),offset:String(offset)});
      if(e!==epoch||ticket!==serial||current!==root||!current.isConnected)return;head=nextHead;state=nextState;render();
    }catch(error){if(e===epoch&&ticket===serial&&current.isConnected){current.replaceChildren();el('p',error.message,current,'error');button('重新读取，不重发写入',current,()=>void read());}}
  }
  function render(){root.replaceChildren();el('h3',target?'包名称、复制与资产保存':'我的跨世界资产',root);el('p','只处理本服务器数据根中的包代码、定义和资源；不上传其它服务器，不复制位置、比分、会话、任务、实例、UI草稿或运行授权。复用后仍需独立绑定目标并批准实际生命周期。',root,'muted');
    const nav=el('div',null,root,'actions');button('只读刷新',nav,()=>void read());button('打开当前世界包目录',nav,openCatalog);button('我的跨世界资产',nav,()=>open());
    if(target){
      el('strong',head.name+' · r'+head.revision,root);el('p','源 manifest 名称：'+state.originalName+' · '+target,root,'muted');
      const naming=el('section',null,root,'event-card');el('h4','修改我的库名称',naming);el('p','仅改本人跨世界可见的库别名，不重签源包、不改网页标题或实例名，不停止正在运行的内容。留空恢复原名。',naming,'muted');const alias=el('input',null,naming);alias.maxLength=128;alias.value=state.alias.name;alias.setAttribute('aria-label','我的库名称');button('保存库名称',naming,()=>void change('RENAME',alias.value,null,state.alias.revision,true));
      const copying=el('section',null,root,'event-card');el('h4','复制为独立包',copying);el('p','新 packageId，origin=REUSED，资源字节不变，默认停用。定义 ID 保留在新 packageId 内；硬编码旧包 ID、Mod ID、命名空间等不会自动改写，需检查适配。',copying,'warning');const name=el('input',null,copying);name.maxLength=128;name.value=head.name;name.setAttribute('aria-label','副本名称');const copyConsent=consent('我确认源内容可复用，并仅复制资产，不继承旧世界状态或执行授权。',copying);button('创建当前世界的独立副本',copying,()=>void change('COPY',name.value,null,0,copyConsent.checked));
      const saving=el('section',null,root,'event-card');el('h4','保存准确版本供跨世界复用',saving);el('p','固定当前签名版本，不随源 head 自动升级；稍后从同一服务器的另一个世界明确复用，才创建当地副本。',saving,'muted');const saved=consent('允许本人在其它世界读取并复用这个准确资产版本。',saving);button('加入我的跨世界资产',saving,()=>void change('SAVE_ASSET','',null,0,saved.checked));
      if(state.derivation?.source){const source=el('section',null,root,'event-card');el('h4','真实复制 / 复用来源',source);el('pre',JSON.stringify(state.derivation,null,2),source,'event-json');}
    }else{
      const tools=el('div',null,root,'actions');button(active?'查看已撤回资产':'查看可复用资产',tools,()=>{active=!active;offset=0;void read();});el('p','所选版本共 '+state.page.total+' 条；撤回只关闭今后的复用，不删除旧副本或历史。',root,'muted');
      for(const item of state.page.items){const card=el('article',null,root,'event-card');el('strong',(item.displayName||item.sourceName)+' · '+item.version+' · r'+item.packageRevision,card);el('p',item.packageId+' · '+item.canonical+' · 原来源 '+item.origin,card,'muted');el('p','保存来源世界 '+item.sourceWorld+'；不是世界实例快照。',card,'muted');
        if(item.active){const name=el('input',null,card);name.maxLength=128;name.value=item.displayName||item.sourceName;name.setAttribute('aria-label','复用副本名称');const confirmed=consent('明确在当前世界建立新的停用副本；不搬运或执行旧状态。',card);button('在当前世界复用此版本',card,()=>void change('REUSE_ASSET',name.value,item,item.revision,confirmed.checked));}
        const toggle=consent(item.active?'撤回这个保存版本的后续复用许可。':'重新允许本人复用这个保存版本。',card);button(item.active?'撤回保存版本':'恢复保存版本',card,()=>void change(item.active?'WITHDRAW_ASSET':'RESTORE_ASSET','',item,item.revision,toggle.checked));
      }
      const pages=el('div',null,root,'actions');button('上一页',pages,()=>{offset=Math.max(0,offset-8);void read();},offset>0);button('下一页',pages,()=>{offset=state.page.nextOffset;void read();},state.page.more);
    }
  }
  async function showReceipt(){const e=epoch,current=root;try{const value=await request({kind:'receipt',operationId:operation});if(e!==epoch||current!==root||!current.isConnected)return;showResult(value.receipt);}catch(error){if(e===epoch&&current.isConnected)el('p',error.message+'；没有自动创建另一份副本。',current,'error');}}
  function showResult(receipt){root.replaceChildren();el('h3',receipt.outcome,root);el('p','操作 '+receipt.input.operation,root,'muted');if(receipt.derivation)el('pre',JSON.stringify(receipt.derivation,null,2),root,'event-json');el('p','这是包库结果，不是 Native 运行、物件创建或兼容验收。',root,'muted');if(receipt.target)button('打开此包现有操作 / 预览',root,()=>openTools(receipt.target));button('刷新当前管理页',root,()=>void read());button('当前世界包目录',root,openCatalog);}
  async function change(action,name,item,expected,confirmed){if(!ready||busy)return;if(!confirmed){el('p','请先勾选明确确认。',root,'warning');return;}const e=epoch,current=root;operation=crypto.randomUUID();serial++;busy=true;current.querySelectorAll('button,input').forEach(n=>n.disabled=true);
    const args={kind:'change',operationId:operation,action,name,packageId:item?item.packageId:target,packageRevision:String(item?item.packageRevision:head.revision),canonical:item?item.canonical:head.canonicalSha256,shelfId:item?item.id:'',expectedRevision:String(expected),confirmed:'true'};
    try{const result=await request(args,'APPLIED');if(e!==epoch||current!==root||!current.isConnected)return;busy=false;showResult(result);}
    catch(error){if(e===epoch&&current===root&&current.isConnected){busy=false;current.replaceChildren();el('p',error.message+' · 原操作 '+operation+'；不自动重发。',current,'error');button('读取原操作持久回执',current,()=>void showReceipt());button('只读刷新管理页',current,()=>void read());}}
    finally{if(e===epoch)busy=false;}
  }
  function open(packageId=''){root=windowFor('runtime-package-assets','内容库与跨世界资产');epoch++;serial++;target=typeof packageId==='string'?packageId:'';head=state=null;offset=0;busy=false;operation='';root.replaceChildren();el('p','读取本人资产元数据…',root,'muted');if(ready)void read();}
  function session(value){const key=JSON.stringify([value?.sessionId,value?.serverInstanceId,value?.binding?.worldId,value?.binding?.viewerPlayerId]);if(key===scope&&ready===!!value)return;scope=key;ready=!!value;epoch++;serial++;busy=false;target=operation='';head=state=null;root?.replaceChildren();if(root?.isConnected)el('p','上下文变化，请重新打开；旧响应不会恢复旧操作。',root,'muted');}
  return {open,session,reset:()=>session(null)};
}
