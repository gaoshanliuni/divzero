export class WorldActivationSubmission {
  identity='';operationId=null;
  request(input,confirmed,autoRestore,restoreAvailable,uuid){
    if(confirmed!==true||typeof autoRestore!=='boolean')throw new Error('NATIVE_CONSENT_REQUIRED');
    if(autoRestore&&restoreAvailable!==true)throw new Error('RESTORE_CONTRACT_MISSING');
    const coordinates={};for(const k of ['x','y','z']){if(String(input[k]??'').trim()===''||!Number.isFinite(Number(input[k])))throw new Error('WORLD_POSITION_INVALID');coordinates[k]=Number(input[k]);}
    const value={packageId:input.packageId,packageRevision:input.packageRevision,definitionId:input.definitionId,dimension:input.dimension,...coordinates,confirmed:true,autoRestore};
    const identity=JSON.stringify(value);if(this.identity!==identity){this.identity=identity;this.operationId=null;}this.operationId??=uuid();
    return {...value,operationId:this.operationId};
  }
}
export class WorldMoveSubmission {
  identity='';operationId=null;
  request(context,target,confirmed,uuid){
    if(confirmed!==true)throw new Error('INSTANCE_MOVE_CONSENT_REQUIRED');
    const values={};for(const key of ['x','y','z']){if(String(target[key]??'').trim()===''||!Number.isFinite(Number(target[key])))throw new Error('INSTANCE_MOVE_POSITION_INVALID');values[key]=Number(target[key]);}
    const distance=Math.max(...['x','y','z'].map(k=>Math.abs(values[k]-context.location[k])));if(!Number.isFinite(distance)||distance===0||distance>64)throw new Error('INSTANCE_MOVE_RANGE');
    const value={activationId:context.activationId,instanceId:context.instanceId,activationRevision:context.activationRevision,canonical:context.canonical,source:{...context.location},...values,confirmed:true};
    const identity=JSON.stringify(value);if(this.identity!==identity){this.identity=identity;this.operationId=null;}this.operationId??=uuid();return {...value,operationId:this.operationId};
  }
}
const activationStatus=a=>`${a.state} · ${a.instanceId} · 重启恢复 ${a.state==='DISABLED'?'已停止，不恢复':a.autoRestore?'已允许':'未允许'}`;
export function updateWorldActivationStatus(content,a){for(const row of content.querySelectorAll('[data-world-status]'))if(row.dataset.worldStatus===a.operationId)row.textContent=activationStatus(a);}
export function createWorldContentCards({windowFor,send,report,openRestore}) {
  const add=(tag,text,parent)=>{const e=document.createElement(tag);if(text!=null)e.textContent=text;parent.append(e);return e;};
  const drafts=new Map();
  let pageToken=0;
  async function manage(page=0){
    const content=windowFor('runtime-world-instances','已有世界实例');const token=++pageToken;content.replaceChildren();
    const status=add('p','读取属于你的实际世界实例…',content);status.setAttribute('role','status');
    try{const r=await send('packageAction',{action:'worldList',page});if(r.code!=='OBSERVED')throw new Error(r.values?.errorCode||r.code);if(!content.isConnected||token!==pageToken)return;
      const result=JSON.parse(r.values.page);status.textContent=`${result.count} 个实例 · ${result.page+1}/${result.pages} 页`;
      const previous=add('button','上一页实例',content);previous.disabled=result.page<=0;previous.onclick=()=>manage(result.page-1);
      const next=add('button','下一页实例',content);next.disabled=result.page+1>=result.pages;next.onclick=()=>manage(result.page+1);
      const refresh=add('button','只读刷新列表',content);refresh.onclick=()=>manage(result.page);
      for(const a of result.instances){const row=add('article',null,content);row.className='generation-job';row.dataset.managedInstance=a.instanceId;add('strong',a.packageName,row);add('p',`${a.state} · ${a.instanceId}`,row);add('p',`${a.location.dimension} · ${a.location.x}, ${a.location.y}, ${a.location.z}`,row).className='muted';
        if(a.error)add('p','原因：'+a.error,row).className='muted';if(openRestore){const restore=add('button','原实例恢复 / 诊断',row);restore.onclick=()=>openRestore(a.activationId);}
        const move=add('button','移动这个实例',row);move.dataset.worldMoveOpen=a.instanceId;move.disabled=!a.movable;move.onclick=()=>moveForm(a.activationId,a.instanceId);
        if(['ACTIVE','RESTORE_PENDING','RESTORING'].includes(a.state))stopButton({operationId:a.activationId,instanceId:a.instanceId,state:a.state},row);
      }
    }catch(e){status.textContent=e.message;report(e);}
  }
  async function moveForm(activationId,instanceId){
    const content=windowFor(`move-${instanceId}`,'只移动所选实例');if(content.childElementCount)return;
    const status=add('p','读取准确实例与位置…',content);status.setAttribute('role','status');
    status.dataset.worldMoveStatus=instanceId;
    try{const r=await send('packageAction',{action:'worldMoveInspect',activationId});if(r.code!=='OBSERVED')throw new Error(r.values?.errorCode||r.code);if(!content.isConnected)return;const a=JSON.parse(r.values.instance);
      if(a.instanceId!==instanceId||!a.movable)throw new Error('INSTANCE_MOVE_NOT_READY');
      status.textContent=`${a.packageName} · instance ${a.instanceId} · ${a.state}`;
      add('p',`原点 ${a.location.dimension}: ${a.location.x}, ${a.location.y}, ${a.location.z}；仅平移这个实例，不修改定义或同类实例。`,content).className='muted';
      add('p','保持实体 UUID、脚本监听和计时器。当前支持同维度 64 格内的受管 Mesh 与普通完整碰撞方块；含方块实体、流体或特殊形状须适配，不能丢弃数据强行移动。未知结果不会自动重放。',content).className='muted';
      const grid=add('div',null,content);grid.className='world-move-coordinates';const fields={};for(const key of ['x','y','z']){const cell=add('div',null,grid),label=add('label',`目标原点 ${key.toUpperCase()}`,cell);const input=add('input',null,cell);input.id=`world-move-${instanceId}-${key}`;label.htmlFor=input.id;input.type='number';input.step='any';input.value=a.location[key];input.dataset.worldMoveField=key;fields[key]=input;}
      const label=add('label',null,content),consent=add('input',null,label);consent.type='checkbox';consent.dataset.worldMoveConsent=instanceId;label.append(document.createTextNode('明确移动此实例的受管部件与锚点；不声称可迁移任意脚本的外部 Java 副作用'));
      const submission=new WorldMoveSubmission();let completed=false;const move=add('button','确认只移动此实例',content);move.dataset.worldMoveApply=instanceId;
      move.onclick=async()=>{move.disabled=true;status.setAttribute('role','status');status.className='';try{const values=submission.request(a,Object.fromEntries(Object.entries(fields).map(([k,v])=>[k,v.value])),consent.checked,()=>crypto.randomUUID());status.dataset.worldMoveRequest=JSON.stringify(values);const receipt=await send('packageAction',{action:'worldMove',...values});if(receipt.code!=='APPLIED')throw new Error(receipt.values?.errorCode||receipt.code);const result=JSON.parse(receipt.values.move);status.textContent=`${result.receipt.state} · 当前原点 ${result.location.x}, ${result.location.y}, ${result.location.z} · 实例 r${result.instanceRevision}${result.duplicate?' · 持久去重回执':''}`;status.className='success';completed=true;move.textContent='已移动 · 实体身份保留';}catch(e){status.textContent=e.message;status.className='error';status.setAttribute('role','alert');report(e);}finally{if(!completed)move.disabled=false;}};
      const reload=add('button','重新读取（放弃未提交位置）',content);reload.dataset.worldMoveRefresh=instanceId;reload.onclick=()=>{content.replaceChildren();moveForm(activationId,instanceId);};
    }catch(e){status.textContent=e.message;report(e);}
  }
  async function open(job,revision){
    const content=windowFor(`world-${job.packageId}-r${revision}`,'世界内容 · 明确启用');
    if(content.childElementCount)return;
    const status=add('p','读取签名包与实例状态…',content);status.setAttribute('role','status');
    try{
      const r=await send('packageAction',{action:'worldInspect',packageId:job.packageId,packageRevision:revision});
      if(r.code!=='OBSERVED')throw new Error(r.values?.errorCode||r.code);
      if(!content.isConnected)return;
      const definitions=JSON.parse(r.values.definitions),anchor=JSON.parse(r.values.anchor);
      const draft=drafts.get(job.packageId)||{definitionId:definitions[0]?.id,...anchor,operationId:null};drafts.set(job.packageId,draft);
      status.textContent=`签名包 r${revision} · ${r.values.activationMode}；生成完成不代表已经放置。`;
      add('p','明确启用后执行包内 Rhino/Java 原生代码，需要 RUN_CODE 和 MANAGE_PACKAGES。关闭此界面不停止已启用内容；停用只清理受管脚本，保留世界块与实例数据。',content).className='muted';
      const fields={};
      for(const [key,label] of [['definitionId','实例定义'],['dimension','目标维度'],['x','原点 X'],['y','原点 Y'],['z','原点 Z']]){
        add('label',label,content);const e=add(key==='definitionId'?'select':'input',null,content);fields[key]=e;e.dataset.worldField=key;
        if(key==='definitionId')for(const d of definitions)add('option',`${d.name} · ${d.id.slice(0,8)}`,e).value=d.id;
        e.value=draft[key]??'';e.onchange=()=>{draft[key]=e.value;draft.operationId=null;};
      }
      const label=add('label',null,content),consent=add('input',null,label);consent.type='checkbox';consent.dataset.worldConsent='true';label.append(document.createTextNode('明确允许本次在所填位置执行原生代码并创建实例'));
      const restoreLabel=add('label',null,content),restore=add('input',null,restoreLabel);restore.type='checkbox';restore.dataset.worldAutoRestore='true';
      restoreLabel.append(document.createTextNode('另行允许服务器重启后恢复此实例的相同签名代码；不会重放创建回调，可随时撤销。'));
      const restoreCapability=()=>{const allowed=definitions.find(d=>d.id===fields.definitionId.value)?.restoreAvailable===true;restore.disabled=!allowed;if(!allowed)restore.checked=false;};restoreCapability();fields.definitionId.addEventListener('change',restoreCapability);
      const submission=draft.submission??=new WorldActivationSubmission();
      const start=add('button','启用并创建世界实例',content);start.dataset.worldActivate=job.packageId;
      start.onclick=async()=>{start.disabled=true;try{
        const values=submission.request({packageId:job.packageId,packageRevision:revision,definitionId:fields.definitionId.value,dimension:fields.dimension.value,x:fields.x.value,y:fields.y.value,z:fields.z.value},consent.checked,restore.checked,definitions.find(d=>d.id===fields.definitionId.value)?.restoreAvailable,()=>crypto.randomUUID());
        const receipt=await send('packageAction',{action:'worldActivate',...values});
        if(receipt.code!=='APPLIED')throw new Error(receipt.values?.errorCode||receipt.code);
        const a=JSON.parse(receipt.values.activation);status.textContent=`${a.state} · instance ${a.instanceId} · 当前受管原版块 ${receipt.values.verifiedBlocks}`;
        content.dataset.worldInstance=a.instanceId;content.dataset.worldActivation=a.operationId;
        const summary=add('p',activationStatus(a),content);summary.className='muted';summary.dataset.worldStatus=a.operationId;
        if(a.state==='ACTIVE')stopButton(a,content);
        if(a.autoRestore)restoreOff(a,content);
      }catch(e){status.textContent=e.message;report(e);}finally{start.disabled=false;}};
      for(const a of JSON.parse(r.values.activations)){
        if(openRestore){const restore=add('button','原实例恢复 / 诊断 · '+a.instanceId.slice(0,8),content);restore.onclick=()=>openRestore(a.operationId);}
        const row=add('p',activationStatus(a),content);row.className='muted';row.dataset.worldStatus=a.operationId;if(['ACTIVE','RESTORE_PENDING','RESTORING'].includes(a.state))stopButton(a,content);if(a.autoRestore)restoreOff(a,content);
      }
    }catch(e){status.textContent=e.message;report(e);const retry=add('button','重试只读加载',content);retry.onclick=()=>{if(content.isConnected){content.replaceChildren();open(job,revision);}};}
  }
  function stopButton(a,content){const stop=add('button','停用此实例脚本（保留世界数据）',content);stop.dataset.worldDisable=a.operationId;stop.onclick=async()=>{stop.disabled=true;try{const r=await send('packageAction',{action:'worldDisable',activationId:a.operationId});if(r.code!=='APPLIED')throw new Error(r.values?.errorCode||r.code);updateWorldActivationStatus(content,JSON.parse(r.values.activation));stop.textContent='已停用 · 世界块与数据保留';}catch(e){report(e);stop.disabled=false;}};}
  function restoreOff(a,content){const button=add('button','撤销重启恢复（不停止当前运行）',content);button.dataset.worldRestoreOff=a.operationId;button.onclick=async()=>{button.disabled=true;try{const r=await send('packageAction',{action:'worldRestoreOff',activationId:a.operationId,activationRevision:a.revision});if(r.code!=='APPLIED')throw new Error(r.values?.errorCode||r.code);updateWorldActivationStatus(content,JSON.parse(r.values.activation));button.textContent='已撤销重启恢复';}catch(e){report(e);button.disabled=false;}};}
  return {manage,render(card,job,revision){const button=add('button','查看并启用世界内容',card);button.dataset.worldPackage=job.packageId;button.onclick=()=>open(job,revision);}};
}
