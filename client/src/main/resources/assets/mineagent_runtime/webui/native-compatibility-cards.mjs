export function createNativeCompatibility({windowFor,send}){
  let root=null,epoch=0,serial=0,scope='',ready=false,packageId='',head=null,view=null,operation=null,identity='';
  const el=(tag,text,parent,cls)=>{const n=document.createElement(tag);if(text!=null)n.textContent=text;if(cls)n.className=cls;parent.append(n);return n;};
  const button=(text,parent,fn,allowed=true)=>{const n=el('button',text,parent);n.type='button';n.disabled=!ready||!allowed;n.onclick=()=>{if(ready&&allowed)fn();};return n;};
  async function read(offset=0,fresh=false){if(!ready||!root?.isConnected)return;const generation=epoch,ticket=++serial,current=root;current.replaceChildren();el('p','读取签名声明与实际服务端环境…',current,'muted');
    try{
      if(fresh||!head){const r=await send('packageCatalog',{kind:'package',packageId,headRevision:'0',headHash:''});if(r.code!=='OBSERVED')throw new Error(r.values?.errorCode||r.code);if(generation!==epoch||ticket!==serial)return;head=JSON.parse(r.values.state);}
      const r=await send('packageCatalog',{kind:'compatibility',packageId,headRevision:String(head.revision),headHash:head.canonicalSha256,offset:String(offset)});if(r.code!=='OBSERVED'||r.values?.errorCode)throw new Error(r.values?.errorCode||r.code);if(generation!==epoch||ticket!==serial||current!==root||!current.isConnected)return;view=JSON.parse(r.values.state);render();
    }catch(error){if(generation===epoch&&ticket===serial&&current.isConnected){current.replaceChildren();el('p',error.message,current,'error');button('重新读取当前包',current,()=>void read(0,true));}}
  }
  function render(){root.replaceChildren();el('h3','原生兼容 · '+head.name,root);el('p','Package '+packageId+' · r'+view.revision+' · '+view.hash,root,'muted');
    el('p','SERVER 声明：'+view.declarationStatus+' · 当前准入：'+(view.admissionError||'环境条件满足（不是执行验收）'),root,view.admissionError?'warning':'muted');
    if(view.issues.length)el('pre',view.issues.join('\n'),root,'event-json');el('p','签名契约只读投影（requiredMods 每页 16 条，完整 manifest 可从包版本页查看）：',root,'muted');el('pre',JSON.stringify(view.contract,null,2),root,'event-json');
    el('p','服务端实际观测环境（CLIENT 原生环境尚未在此检查）：',root,'muted');el('pre',JSON.stringify(view.environment,null,2),root,'event-json');el('p','环境 fingerprint：'+view.environmentHash,root,'muted');
    const pages=el('div',null,root,'actions');button('上一页 Mod / 声明',pages,()=>void read(Math.max(0,view.offset-16)),view.offset>0);button('下一页 Mod / 声明',pages,()=>void read(view.nextOffset),view.more);button('刷新包与环境',pages,()=>void read(0,true));
    el('p','兼容声明不会授予执行权限、运行代码或证明行为正确。已签名的不匹配不能用旧包声明覆盖；非 HOT 生命周期仍需其真实消费链。',root,'muted');
    if(view.canAttest||view.canWithdraw){const box=el('section',null,root,'event-card');el('p','旧包没有签名兼容契约：本操作只记录你对当前环境的明确声明，不修改包/hash/版本，不伪装成模型声明或已通过测试。代码或环境、受管权限代数变化后需重新核对。',box,'warning');
      const label=el('label',null,box),confirmed=el('input',null,label);confirmed.type='checkbox';label.append(document.createTextNode('我已核对这个包与当前服务端环境，并明确执行下方选定的兼容元数据操作；不代替另行启用审批。'));
      button('确认旧包适用于当前环境',box,()=>void change(true,confirmed.checked),view.canAttest);button('撤回此环境声明',box,()=>void change(false,confirmed.checked),view.canWithdraw);
    }
  }
  async function change(approve,confirmed){if(!confirmed){el('p','请先明确勾选确认。',root,'warning');return;}const generation=epoch,current=root;
    const payload={packageId,packageRevision:String(view.revision),canonical:view.hash,environment:view.environmentHash,pinRevision:String(view.pinRevision),approve:String(approve),confirmed:'true'};const key=JSON.stringify(payload);if(identity!==key){identity=key;operation=crypto.randomUUID();}
    current.querySelectorAll('button,input').forEach(n=>n.disabled=true);
    try{const r=await send('nativeCompatibility',{...payload,operationId:operation});if(generation!==epoch||current!==root||!current.isConnected)return;if(r.code!=='APPLIED'||r.values?.errorCode)throw new Error(r.values?.errorCode||r.code);await read(0,true);}
    catch(error){if(generation===epoch&&current.isConnected){current.replaceChildren();el('p',error.message+'；结果未知先读取，不自动重发。',current,'error');button('读取当前状态',current,()=>void read(0,true));}}
  }
  function open(id){packageId=id;head=null;view=null;epoch++;serial++;root=windowFor('runtime-native-compatibility','原生兼容检查');if(ready)void read(0,true);else{root.replaceChildren();el('p','等待可信会话…',root,'muted');}}
  function session(value){const key=JSON.stringify([value?.sessionId,value?.serverInstanceId,value?.binding?.worldId,value?.binding?.viewerPlayerId]);if(scope===key&&ready===!!value)return;scope=key;ready=!!value;epoch++;serial++;operation=null;identity='';head=null;view=null;root?.replaceChildren();if(root?.isConnected)el('p','上下文已变化，请从当前包目录重新打开。',root,'muted');}
  return {open,session,reset:()=>session(null)};
}
