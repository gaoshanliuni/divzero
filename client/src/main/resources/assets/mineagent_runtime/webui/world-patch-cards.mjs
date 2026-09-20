import {UiPatchDraft} from './ui-patch-state.mjs';
export function worldPatchApplyRequest(job,confirmed){
  if(confirmed!==true)throw new Error('WORLD_PATCH_CONFIRMATION_REQUIRED');
  if(job.state!=='READY')throw new Error('WORLD_PATCH_NOT_READY');
  if(!/^[a-f0-9]{64}$/.test(job.candidateHash??''))throw new Error('WORLD_PATCH_HASH_REQUIRED');
  return {action:'worldPatchApply',operationId:job.operationId,confirmed:true,canonicalSha256:job.candidateHash};
}
export function createWorldPatchCards({windowFor,send,report,persist}){
  const draft=new UiPatchDraft();let disposed=false;const roots=new Set();
  const retain=n=>{for(const old of roots)if(!old.isConnected)roots.delete(old);roots.add(n);return n;};
  const add=(tag,text,parent)=>{const n=document.createElement(tag);if(text!=null)n.textContent=text;parent.append(n);return n;};
  const call=async args=>{if(disposed)throw new Error('WORLD_PATCH_CONTEXT_CHANGED');const r=await send('packageAction',args);if(disposed)throw new Error('WORLD_PATCH_CONTEXT_CHANGED');if(!['ACCEPTED','APPLIED','OBSERVED'].includes(r.code))throw new Error(r.values?.errorCode||r.code);return r.values;};
  function open(job,revision){
    draft.choose(job.packageId,revision,job.agentId);const content=retain(windowFor('runtime-world-patch','修改原生代码与资源'));content.replaceChildren();
    add('p',`基于 r${revision}。生成独立签名候选，保留旧版和未列出资源；不修改 UI、权限或定义身份，不自动执行。启动扩展发布后还须另行构建与停机替换；不会热停当前 Mod。`,content).className='muted';
    const label=add('label','修复或修改要求',content);label.htmlFor='world-patch-prompt';const input=add('textarea',null,content);input.id='world-patch-prompt';input.maxLength=8192;input.value=draft.prompt;input.oninput=()=>{draft.edit(input.value);persist();};
    const submit=add('button','生成同包改版候选',content);submit.id='world-patch-submit';
    submit.onclick=async()=>{submit.disabled=true;try{draft.edit(input.value);const args=draft.request(()=>crypto.randomUUID());persist();const r=await call({action:'worldPatchSubmit',...args});add('p',`${r.state} · Task ${r.taskId}`,content);}catch(e){report(e);}finally{submit.disabled=false;}};
  }
  async function review(job){
    const content=retain(windowFor(`world-review-${job.operationId}`,'原生改版 · 源码审查'));content.replaceChildren();let offset=0,path='',sourceOffset=0,meta=null,epoch=0;
    const status=add('p','读取候选…',content);status.setAttribute('role','status');const hashes=add('pre','',content);
    const nav=add('div',null,content);nav.className='actions';const previous=add('button','上一页文件',nav),next=add('button','下一页文件',nav);const files=add('select',null,content);files.setAttribute('aria-label','世界候选资源');
    const label=add('label',null,content),before=add('input',null,label);before.type='checkbox';label.append(document.createTextNode('显示原版文件'));
    const sourceNav=add('div',null,content);sourceNav.className='actions';const prevSource=add('button','上一段源码',sourceNav),nextSource=add('button','下一段源码',sourceNav);const source=add('pre','',content);source.dataset.worldPatchSource='true';
    const consentLabel=add('label',null,content),consent=add('input',null,consentLabel);consent.type='checkbox';consent.dataset.worldPatchConsent='true';consentLabel.append(document.createTextNode('已审查此 SHA 候选，允许发布新库版本；旧世界脚本按原规则失效，BOOT Mod 不在此热停或替换。保留世界数据，不自动运行新代码。'));
    const apply=add('button','应用已审查的世界版本',content);apply.dataset.worldPatchApply=job.operationId;apply.disabled=true;
    async function read(){const ticket=++epoch;apply.disabled=true;try{
      const r=await call({action:'worldPatchInspect',operationId:job.operationId,offset,path,sourceOffset,before:before.checked});if(ticket!==epoch||!content.isConnected)return;
      meta=JSON.parse(r.summary);status.textContent=`${meta.state} · 原版 r${meta.baseRevision} → 候选 r${meta.candidateRevision}`;hashes.textContent=`原 SHA: ${meta.baseHash}\n候选 SHA: ${meta.candidateHash||'尚未生成'}`;
      files.replaceChildren();add('option','选择资源查看实际源码',files).value='';for(const f of meta.files)add('option',`${f.changed?'已修改':'保留'} · ${f.path}`,files).value=f.path;files.value=path;
      previous.disabled=offset===0;next.disabled=!meta.more;prevSource.disabled=sourceOffset===0;nextSource.disabled=sourceOffset+8192>=Number(r.sourceLength);source.textContent=path?`SHA ${r.sourceSha256||'已删除'} · ${sourceOffset}/${r.sourceLength}\n${r.source}`:'请选择文件。二进制资源仅显示 SHA，Native 执行仍须另行确认。';apply.disabled=meta.state!=='READY';
    }catch(e){status.textContent=e.message;report(e);}}
    files.onchange=()=>{path=files.value;sourceOffset=0;read();};before.onchange=()=>{sourceOffset=0;read();};previous.onclick=()=>{offset=Math.max(0,offset-16);path='';sourceOffset=0;read();};next.onclick=()=>{offset+=16;path='';sourceOffset=0;read();};prevSource.onclick=()=>{sourceOffset=Math.max(0,sourceOffset-8192);read();};nextSource.onclick=()=>{sourceOffset+=8192;read();};
    apply.onclick=async()=>{apply.disabled=true;try{const r=await call(worldPatchApplyRequest(meta,consent.checked));status.textContent=`${r.state} · 当前 r${r.headRevision}；${meta.activationMode==='BOOT_EXTENSION'?'仅发布源码，当前 Mod 未替换；请返回启动扩展管理构建。':'尚未创建新世界实例。'}`;consent.checked=false;await read();}catch(e){status.textContent=e.message;report(e);apply.disabled=meta?.state!=='READY';}};
    add('button','刷新审查状态',content).onclick=()=>{consent.checked=false;read();};await read();
  }
  function history(parent,jobs){if(!jobs.length)return;add('h3','原生代码/资源改版历史',parent);
    for(const job of jobs){const card=add('article',null,parent);card.className='generation-job world-patch-job';card.dataset.operationId=job.operationId;card.dataset.state=job.state;card.dataset.packageId=job.packageId;
      add('strong',`${job.state} · 世界包 r${job.baseRevision} → r${job.headRevision||job.baseRevision+1}`,card);if(job.errorCode)add('p',job.errorCode,card).className='error';
      const inspect=add('button','审查世界候选源码',card);inspect.dataset.worldPatchReview=job.operationId;inspect.onclick=()=>review(job).catch(report);
      if(['PENDING','READY'].includes(job.state)){const cancel=add('button','取消世界改版',card);cancel.onclick=()=>call({action:'worldPatchCancel',operationId:job.operationId}).catch(report);}
      if(job.state==='APPLIED'){const label=add('label',null,card),confirm=add('input',null,label);confirm.type='checkbox';label.append(document.createTextNode('仅回退包库中的原签名源码；不改变已安装 BOOT 文件、不回滚世界数据，不自动运行'));
        const undo=add('button','回退包库版本',card);undo.onclick=async()=>{undo.disabled=true;try{if(!confirm.checked)throw new Error('WORLD_PATCH_CONFIRMATION_REQUIRED');await call({action:'worldPatchRollback',operationId:job.operationId,confirmed:true,canonicalSha256:job.baseHash});}catch(e){report(e);}finally{undo.disabled=false;}};
      }
    }
  }
  return {dispose(){disposed=true;for(const root of roots)if(root.isConnected){root.replaceChildren();add('p','上下文已变化，请从当前包重新打开。',root);}roots.clear();},render(card,job,revision){const button=add('button','修改原生代码与资源',card);button.dataset.worldPatchPackage=job.packageId;button.onclick=()=>open(job,revision);},history,snapshot:()=>draft.snapshot(),restore:value=>draft.restore(value)};
}
