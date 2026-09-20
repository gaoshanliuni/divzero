// Coder responses are private candidates. No automatic adoption, publishing, execution or request replay.
export function attachStudioCoder({container,send,getContext,available,openDraft,notify}) {
  let disposed=false,busy=false,offset=0,selected=null,sequence=0,detailSequence=0,lastRendered='',opened=false;
  const node=(tag,text,parent,cls)=>{const n=document.createElement(tag);if(text!=null)n.textContent=text;if(cls)n.className=cls;parent.append(n);return n;};
  const panel=node('details',null,container,'studio-coder-panel');node('summary','Coder · 生成 / 修改 / 有界自动修复',panel);
  const body=node('section',null,panel);
  node('p','输入明确需求，每次请求最多 1–3 次 Provider 调用。只有返回结果被确定拒绝时才按预算修复；超时/未知传输不重发。候选不会自动运行，也不会覆盖当前编辑。Java 候选会编译验证但不会 start。',body,'warning');
  const contextLabel=node('p','',body,'muted');
  node('p','Java 依赖携带真实 JAR API；Rhino 依赖在单独确认后发送完整 JS 源码（含私有实现，不含运行时内存/配置）。宿主保留声明，不生成依赖副本。',body,'muted');
  const modeLabel=node('label',null,body),workspace=node('input',null,modeLabel);workspace.type='checkbox';workspace.checked=!!getContext().workspace||getContext().fileCount>1;modeLabel.append(document.createTextNode('多文件工作区：返回完整文件集（包括保留、增删改），入口固定为上方路径。'));
  const label=node('label','生成或修改需求',body),prompt=node('textarea',null,label,'event-json');prompt.maxLength=8192;prompt.rows=4;
  const row=node('div',null,body,'event-toolbar'),budgetLabel=node('label','本请求最多模型调用次数',row),budget=node('select',null,budgetLabel);
  for(const value of [1,2,3]){const option=node('option',String(value)+(value===1?'（不自动修复）':'（仅确定失败时自动修复）'),budget);option.value=String(value);}
  const diagnosticLabel=node('label','附带原执行诊断',row),diagnostic=node('select',null,diagnosticLabel);node('option','不附带执行诊断',diagnostic).value='';
  const consentLabel=node('label',null,body),consent=node('input',null,consentLabel);consent.type='checkbox';
  workspace.onchange=()=>{consent.checked=false;};
  consentLabel.append(document.createTextNode('明确同意本请求的模型调用次数与可能费用；当前 Provider 设置用于 CODING，不自动运行返回源码。'));
  const shareLabel=node('label',null,body),share=node('input',null,shareLabel);share.type='checkbox';shareLabel.append(document.createTextNode('明确同意向当前 Provider 发送本请求整个 Rhino 依赖图的完整 JS 源码（包括私有实现）；与费用确认分开。'));
  const needsSources=c=>c.dependencyCount>0&&!/\.java$/i.test(c.path);
  const nativeLabel=node('label',null,body),shareNative=node('input',null,nativeLabel);shareNative.type='checkbox';nativeLabel.append(document.createTextNode('明确同意向当前 Provider 发送 Native API 选择：raw class 的声明元数据，以及服务器签发的 live/transformed overlay 中准确 class bytes、provenance 与明确选择的方法实现。'));
  const needsNative=c=>(c.nativeApi?.items?.length||0)+(c.nativeApi?.overlays?.length||0)>0;
  const jobNativeCount=job=>(job.nativeTypeCount||0)+(job.nativeOverlayCount||0);
  const controls=node('div',null,body,'actions'),status=node('p','尚未提交模型请求。',body,'muted');status.setAttribute('aria-live','polite');status.style.whiteSpace='pre-wrap';
  const lookupLabel=node('label','原请求 ID（可只读恢复观察）',body),lookup=node('input',null,lookupLabel);lookup.maxLength=36;
  const listArea=node('section',null,body),detail=node('section',null,body);
  const valid=()=>!disposed&&panel.isConnected&&available();
  const show=text=>{if(valid())status.textContent=text;};
  const button=(title,parent,fn,allowed=true)=>{
    const n=node('button',title,parent);n.type='button';n.dataset.coderAllowed=String(allowed);n.dataset.studioAllowed=String(allowed);n.disabled=!valid()||busy||!allowed;
    n.onclick=()=>{if(valid()&&!busy&&!n.disabled&&n.dataset.coderAllowed!=='false')fn();};return n;
  };
  async function request(args,write=false,operation=crypto.randomUUID()){
    const r=await send('javaStudio',write?{kind:'write',operationId:operation,confirmed:'true',...args}:args);
    if(r.code!==(write?'APPLIED':'OBSERVED')||r.values?.errorCode)throw new Error(r.values?.errorCode||r.code);
    return JSON.parse(r.values.state);
  }
  function refreshState(){
    if(disposed)return;
    body.querySelectorAll('button').forEach(n=>{n.disabled=!valid()||busy||n.dataset.coderAllowed==='false';n.dataset.studioAllowed=String(!n.disabled);});
    body.querySelectorAll('input,select,textarea').forEach(n=>n.disabled=!valid()||busy);
    const context=getContext();share.disabled=!valid()||busy||!needsSources(context);if(!needsSources(context))share.checked=false;shareNative.disabled=!valid()||busy||!needsNative(context);if(!needsNative(context))shareNative.checked=false;if(context.fileCount>1){workspace.checked=true;workspace.disabled=true;}contextLabel.textContent=context.baseDraft?'基于已保存稿 '+context.baseDraft+' @ r'+context.baseRevision+' · '+context.path+' · '+context.fileCount+' 文件 · '+(context.dependencyCount||0)+' 个依赖 · Native raw '+(context.nativeApi?.items?.length||0)+' / overlay '+(context.nativeApi?.overlays?.length||0)+' · Agent '+context.agentId:'新生成 · 使用上方源码路径与 Agent；源码输入需保持空白。';
  }
  function diagnostics(){
    const previous=diagnostic.value;diagnostic.replaceChildren();node('option','不附带执行诊断',diagnostic).value='';
    for(const p of getContext().publications||[]){const option=node('option',p.language+' · '+p.state+' · '+p.id,diagnostic);option.value=p.language+'|'+p.id;}
    if(Array.from(diagnostic.options).some(v=>v.value===previous))diagnostic.value=previous;
  }
  async function submit(){
    if(!valid()||busy)return;
    const c=getContext();if(needsSources(c)&&!share.checked){show('请单独确认向 Provider 发送整个 Rhino 依赖图的完整 JS 源码。');return;}if(needsNative(c)&&!shareNative.checked){show('请单独确认向 Provider 发送选中 Native raw / overlay 上下文。');return;}if(!consent.checked){show('请先确认模型调用预算与费用。');return;}
    if(c.dirty||!c.baseDraft&&c.source.trim()){show('请先将已有本地源码创建/保存为草稿，再要求修改；新生成必须从空白源码开始。');return;}
    if(!c.agentId||!prompt.value.trim()){show('请选择真实 Agent 并填写明确需求。');return;}
    const [kind='',publication='']=diagnostic.value.split('|'),id=crypto.randomUUID(),ticket=++sequence;
    const args={action:'coderSubmit',path:c.path,prompt:prompt.value,agentId:c.agentId,baseDraft:c.baseDraft,baseRevision:String(c.baseRevision),maxAttempts:budget.value,diagnosticKind:kind,publicationId:publication,workspace:String(workspace.checked||c.fileCount>1),shareDependencySources:String(needsSources(c)&&share.checked),nativeSnapshot:needsNative(c)?c.nativeApi.snapshot:'',nativeClasses:needsNative(c)?JSON.stringify(c.nativeApi.items):'[]',nativeLiveSelections:needsNative(c)?JSON.stringify(c.nativeApi.overlays||[]):'[]',shareNativeContext:String(needsNative(c)&&shareNative.checked)};
    lookup.value=id;consent.checked=share.checked=shareNative.checked=false;busy=true;refreshState();show('正在持久受理请求 '+id+'；未知结果不会自动重发。');
    try{const job=await request(args,true,id);if(!valid()||ticket!==sequence)return;selected=job;lastRendered='';render();show('已受理 '+job.id+'。候选仅保存在此任务；请观察原请求。');}
    catch(e){if(valid()&&ticket===sequence)show(e.message+'；原请求 ID 已保留，请只读查询，不自动重发。');}
    finally{if(!disposed){busy=false;refreshState();}}
  }
  async function list(){
    if(!valid()||busy)return;const ticket=++sequence;busy=true;refreshState();
    try{
      const value=await request({kind:'coderList',offset:String(offset)});if(!valid()||ticket!==sequence)return;
      listArea.replaceChildren();node('h4','本人 Coder 历史',listArea);
      for(const job of value.jobs){const card=node('article',null,listArea,'event-card');node('strong',job.path+' · '+job.state,card);node('p',job.id+' · '+job.attempts.length+' / '+job.maxAttempts+' 次已登记尝试',card,'muted');button('只读观察 / 候选与采用',card,()=>void observe(job.id));}
      if(!value.jobs.length)node('p','此页没有历史请求。',listArea,'muted');
      const pages=node('div',null,listArea,'actions');button('上一页',pages,()=>{offset=Math.max(0,offset-4);void list();},offset>0);button('下一页',pages,()=>{offset=value.nextOffset;void list();},value.more);
    }catch(e){show(e.message);}
    finally{if(!disposed){busy=false;refreshState();}}
  }
  async function observe(id=selected?.id){
    if(!id||busy||!valid())return;const ticket=++sequence;busy=true;refreshState();
    try{const value=await request({kind:'coderGet',jobId:id});if(!valid()||ticket!==sequence)return;selected=value;lookup.value=id;render();}
    catch(e){show(e.message);}
    finally{if(!disposed){busy=false;refreshState();}}
  }
  async function readPart(job,part,attempt,offset,area){
    const ticket=++detailSequence;
    try{
      const value=await request({kind:'coderText',jobId:job.id,revision:String(job.revision),part,attempt:String(attempt),offset:String(offset)});
      if(!valid()||ticket!==detailSequence||!area.isConnected)return;area.replaceChildren();node('p',part+' · SHA '+value.hash,area,'muted');node('pre',value.text,area,'event-json');
      button('上一段',area,()=>void readPart(job,part,attempt,Math.max(0,offset-1024),area),offset>0);button('下一段',area,()=>void readPart(job,part,attempt,value.nextOffset,area),value.more);
    }catch(e){if(valid()&&area.isConnected)show(e.message+'；请重新观察原请求以取得当前 revision。');}
  }
  async function mutate(action,job,hash=''){
    if(!valid()||busy)return;busy=true;refreshState();const ticket=++sequence;
    try{
      const args={action,jobId:job.id,revision:String(job.revision)};if(action==='coderAdopt')args.sourceHash=hash;
      const result=await request(args,true);if(!valid()||ticket!==sequence)return;
      show(result.draftId?'已采用为新草稿 '+result.draftId+'；未发布或运行，当前本地编辑未被覆盖。':JSON.stringify(result));
      busy=false;await observe(job.id);
    }catch(e){if(valid()&&ticket===sequence)show(e.message+'；只读核查原记录，不自动重复请求或采用。');}
    finally{if(!disposed){busy=false;refreshState();}}
  }
  async function files(job,ordinal,offset,area){
    const ticket=++detailSequence;
    try{
      const value=await request({kind:'coderFiles',jobId:job.id,revision:String(job.revision),attempt:String(ordinal),offset:String(offset)});
      if(!valid()||ticket!==detailSequence||!area.isConnected)return;area.replaceChildren();node('p','入口 '+value.entry+' · 完整候选 SHA '+value.candidateHash,area,'muted');
      for(const file of value.files){const card=node('article',null,area,'event-card');node('strong',file.change+' · '+file.path,card);node('p',file.bytes+' bytes · '+file.hash,card,'muted');const text=node('section',null,card);button(file.change==='REMOVED'?'读取被删除的基线文件':'读取此文件',card,()=>void fileText(job,file,0,text));}
      button('前面文件',area,()=>void files(job,ordinal,Math.max(0,offset-8),area),offset>0);button('后面文件',area,()=>void files(job,ordinal,value.nextOffset,area),value.more);
    }catch(e){show(e.message+'；请重读原请求，不以部分文件代替完整候选。');}
  }
  async function fileText(job,file,offset,area){
    const ticket=++detailSequence;
    try{
      const value=await request({kind:'coderFile',jobId:job.id,revision:String(job.revision),attempt:String(file.sourceAttempt),path:file.path,hash:file.hash,offset:String(offset)});
      if(!valid()||ticket!==detailSequence||!area.isConnected)return;if(value.hash!==file.hash)throw new Error('STUDIO_CODER_FILE_CHANGED');area.replaceChildren();node('pre',value.text,area,'event-json');
      button('前一段',area,()=>void fileText(job,file,Math.max(0,offset-1024),area),offset>0);button('后一段',area,()=>void fileText(job,file,value.nextOffset,area),value.more);
    }catch(e){show(e.message);}
  }
  async function repair(job,requirements,maximum,confirmation,shareSources,nativeConsent){
    if(!valid()||busy||!confirmation.checked)return;if(!requirements.value.trim()){show('请填写本次新增修复要求；原需求会保留。');return;}
    if(job.dependencyContextKind==='RHINO_SOURCES'&&!shareSources?.checked){show('本次新修复也需要明确确认发送完整依赖源码。');return;}if(jobNativeCount(job)>0&&!nativeConsent?.checked){show('本次新修复也需要明确确认发送冻结的 Native raw / overlay 上下文。');return;}
    const sharing=job.dependencyContextKind==='RHINO_SOURCES'&&shareSources.checked,nativeSharing=jobNativeCount(job)>0&&nativeConsent.checked;const id=crypto.randomUUID(),ticket=++sequence;lookup.value=id;confirmation.checked=false;if(shareSources)shareSources.checked=false;if(nativeConsent)nativeConsent.checked=false;busy=true;refreshState();
    try{const result=await request({action:'coderRepair',jobId:job.id,revision:String(job.revision),prompt:requirements.value,maxAttempts:maximum.value,shareDependencySources:String(sharing),shareNativeContext:String(nativeSharing)},true,id);if(!valid()||ticket!==sequence)return;selected=result;lastRendered='';render();show('已创建新的修复请求；原失败记录与 Task 预算关联保留。');}
    catch(e){if(valid()&&ticket===sequence)show(e.message+'；本次请求 ID 已保留，不自动再发。');}
    finally{if(!disposed){busy=false;refreshState();}}
  }
  function render(){
    if(!valid()||!selected)return;const job=selected,signature=JSON.stringify(job);if(signature===lastRendered)return;lastRendered=signature;detailSequence++;detail.replaceChildren();
    node('h4',job.path+' · '+job.state+' · r'+job.revision,detail);
    node('p',job.id+' · Task '+job.taskId+' · Package '+job.packageId,detail,'muted');
    node('p','Native raw '+(job.nativeTypeCount||0)+' / overlay '+(job.nativeOverlayCount||0)+' · '+(job.nativeSelectionHash||'无')+'；固定依赖 '+(job.dependencyCount||0)+' · graph '+(job.dependencyHash||'无')+'；采用保留声明，运行仍需单独确认。',detail,'muted');
    if(job.repairOf)node('p','修复来源 '+job.repairOf+'；不是重放原请求。',detail,'muted');
    node('p',job.error||'状态只代表请求/候选/采用流程，不代表实际世界效果。',detail,'warning');
    const textArea=node('section',null,detail),actions=node('div',null,detail,'actions');
    button('原需求',actions,()=>void readPart(job,'request',0,0,textArea));button(job.workspace?'基线主文件':'原始源码',actions,()=>void readPart(job,'base',0,0,textArea));button('附带执行诊断',actions,()=>void readPart(job,'diagnostics',0,0,textArea));
    if(job.workspace)button('完整基线文件',actions,()=>void files(job,0,0,textArea));
    button('冻结 Native raw / overlay / snapshot',actions,()=>void readPart(job,'nativeSelection',0,0,textArea),jobNativeCount(job)>0);
    button('冻结依赖声明 / 来源图',actions,()=>void readPart(job,'dependencies',0,0,textArea));
    button('完整元数据（分段）',actions,()=>void readPart(job,'metadata',0,0,textArea));
    for(const a of job.attempts){
      const card=node('article',null,detail,'event-card');node('strong','尝试 '+a.ordinal+' · '+a.state,card);
      node('p',a.provider+' · requested '+a.requestedModel+' · response '+a.responseModel,card,'muted');
      node('p','request '+a.id+' · raw '+a.rawHash+' · candidate '+a.sourceHash,card,'muted');
      node('p',(a.fileCount||0)+' 个候选文件 · 完整候选 SHA '+(a.candidateHash||a.sourceHash),card,'muted');
      if(a.artifact)node('p','Java compile-only artifact '+a.artifact+' · Native snapshot '+a.nativeClasspath,card,'muted');
      const area=node('section',null,card),row=node('div',null,card,'actions');
      button('原始返回（分段）',row,()=>void readPart(job,'raw',a.ordinal,0,area),!!a.rawHash);
      button('候选源码（分段）',row,()=>void readPart(job,'source',a.ordinal,0,area),!!a.sourceHash);
      button('验证诊断',row,()=>void readPart(job,'validation',a.ordinal,0,area));
      button('请求依赖 API（派发前保存）',row,()=>void readPart(job,'dependencyApi',a.ordinal,0,area),!!a.dependencyApiHash);
      button('选中 Native 声明上下文（派发前保存）',row,()=>void readPart(job,'nativeContext',a.ordinal,0,area),!!a.nativeContextHash);
      button('完整依赖源码上下文（派发前保存）',row,()=>void readPart(job,'dependencySources',a.ordinal,0,area),!!a.dependencySourcesHash);
      if(job.workspace)button('完整文件 / 增删改',row,()=>void files(job,a.ordinal,0,area),!!a.sourceHash);
      if(a===job.attempts.at(-1)&&a.state==='ACCEPTED'&&['READY','ADOPTING'].includes(job.state)){
        const label=node('label',null,card),confirm=node('input',null,label);confirm.type='checkbox';
        label.append(document.createTextNode(job.workspace?'已审阅完整文件集、增删改及依赖声明，确认采用为新 CodeDraft；不覆盖原稿、不自动发布/运行。':'已审阅此候选与依赖声明，确认采用为新的 CodeDraft；不覆盖原稿、不自动发布或运行。'));
        button(job.state==='ADOPTING'?'收束原采用（不再调用模型）':'采用此候选为新草稿',card,()=>{if(confirm.checked)void mutate('coderAdopt',job,a.candidateHash||a.sourceHash);});
      }
    }
    if(['PENDING','GENERATING','VALIDATING','READY'].includes(job.state)){
      const label=node('label',null,detail),confirm=node('input',null,label);confirm.type='checkbox';label.append(document.createTextNode('取消该请求后续工作；已经发送的 Provider 请求可能仍计费，原结果继续保留。'));
      button('取消后续处理',detail,()=>{if(confirm.checked)void mutate('coderCancel',job);});
    }
    if(job.adoptedDraft)button('打开采用的草稿（放弃当前未保存编辑）',detail,()=>openDraft(job.adoptedDraft));
    if(job.state==='FAILED'&&job.attempts.at(-1)?.state==='REJECTED'&&job.attempts.at(-1).rawHash){
      const area=node('section',null,detail,'event-card');node('h4','基于确定失败新建修复请求',area);
      node('p','这是新的计费请求，继承原 Task 预算。仅可修复确定拒绝的返回；超时/未知传输不会重放。原需求与新增要求合计最多 8192 字符。',area,'warning');
      const requirements=node('textarea',null,area,'event-json');requirements.maxLength=8192;requirements.rows=3;requirements.setAttribute('aria-label','新增修复要求');
      const maximum=node('select',null,area);maximum.setAttribute('aria-label','新修复请求最多调用次数');for(const value of [1,2,3])node('option',String(value)+' 次',maximum).value=String(value);
      const label=node('label',null,area),confirmation=node('input',null,label);confirmation.type='checkbox';label.append(document.createTextNode('明确同意新请求的次数预算和可能费用；不会自动采用或执行。'));
      let sourceConsent=null;if(job.dependencyContextKind==='RHINO_SOURCES'){const sl=node('label',null,area);sourceConsent=node('input',null,sl);sourceConsent.type='checkbox';sl.append(document.createTextNode('明确同意为本次新修复向 Provider 再次发送完整依赖 JS 源码，包含私有实现。'));}let nativeConsent=null;if(jobNativeCount(job)>0){const nl=node('label',null,area);nativeConsent=node('input',null,nl);nativeConsent.type='checkbox';nl.append(document.createTextNode('明确同意为本次新修复再次发送原请求冻结的 Native raw / overlay 上下文；不会重新解析已过期 selection ID。'));}
      button('确认新建修复请求',area,()=>void repair(job,requirements,maximum,confirmation,sourceConsent,nativeConsent));
    }
    button('只读刷新原请求',detail,()=>void observe(job.id));refreshState();
  }
  button('确认新建 Coder 请求',controls,()=>void submit());
  button('刷新可选执行诊断',controls,diagnostics);button('本人历史',controls,()=>void list());
  button('按原请求 ID 查询',body,()=>void observe(lookup.value.trim()));
  const toggle=()=>{if(panel.open){diagnostics();refreshState();if(!opened){opened=true;void list();}}};panel.addEventListener('toggle',toggle);
  const poll=setInterval(()=>{if(panel.open&&selected&&['PENDING','GENERATING','VALIDATING'].includes(selected.state))void observe();},3000);
  refreshState();
  return {refreshState,openHistory:()=>{panel.open=true;opened=true;diagnostics();void list();},dispose:()=>{disposed=true;sequence++;detailSequence++;clearInterval(poll);panel.removeEventListener('toggle',toggle);}};
}
