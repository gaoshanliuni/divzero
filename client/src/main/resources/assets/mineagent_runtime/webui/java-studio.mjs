import {t as __uiT,tf as __uiF} from './i18n.mjs';
// The historical module/channel name is retained for compatible trusted-shell routing.
import {attachStudioEditor} from './studio-editor.mjs';
import {attachStudioCoder} from './studio-coder.mjs';
import {attachStudioWorkspace} from './studio-workspace.mjs';
export function createJavaStudio({windowFor,send,openCatalog,openNativeApi,openClientScripts,nativeApiContext}) {
  let root=null,ready=false,scope='',epoch=0,serial=0,busy=false,dirty=false,polling=false;
  let offset=0,runsOffset=0,agents=[],draft=null,latest=null,source='',packageId='',packageRevision=0,packageCanonical='',packageName='',path='server/studio.js',targetSide='SERVER',baseRevision=0;
  let editor=null,status=null,notice=null,runs=null,agentSelect=null,nameInput=null,pathInput=null,renderedRuns='';
  let editorTools=null,coderTools=null,workspaceTools=null,baseText='',baseWorkspaceHash='',historyPane=null,historySerial=0;
  function releaseEditor(){editorTools?.dispose();coderTools?.dispose();workspaceTools?.dispose();editorTools=coderTools=workspaceTools=null;historySerial++;historyPane=null;}
  const el=(tag,text,parent,cls)=>{const n=document.createElement(tag);if(text!=null)n.textContent=text;if(cls)n.className=cls;parent.append(n);return n;};
  const button=(text,parent,run,allowed=true)=>{
    const n=el('button',text,parent);n.type='button';n.dataset.studioAllowed=String(allowed);n.disabled=!ready||busy||!allowed;
    n.onclick=()=>{if(ready&&!busy&&!n.disabled&&n.dataset.studioAllowed!=='false')run();};return n;
  };
  const visible=()=>ready&&root?.isConnected&&root.getClientRects().length>0&&document.body.dataset.workspaceVisible!=='false';
  const current=(e,ticket=serial)=>e===epoch&&ticket===serial&&ready&&root?.isConnected;
  const isJava=()=>/\.java$/i.test(pathInput?.value||path);
  const isClient=()=>targetSide==='CLIENT';
  const report=message=>{if(notice?.isConnected)notice.textContent=message;};
  async function request(args,write=false){
    const r=await send('javaStudio',write?{kind:'write',operationId:crypto.randomUUID(),confirmed:'true',...args}:args);
    if(r.code!==(write?'APPLIED':'OBSERVED')||r.values?.errorCode)throw new Error(r.values?.errorCode||r.code);
    return JSON.parse(r.values.state);
  }
  function lock(){
    for(const n of root?.querySelectorAll('[data-studio-action="newFromPackage"]')||[])n.dataset.studioAllowed=String(packageRevision>0);
    for(const n of root?.querySelectorAll('[data-client-download]')||[])n.dataset.studioAllowed=String(isClient()&&packageRevision>0&&!!packageCanonical);
    root?.querySelectorAll('input,select,textarea').forEach(n=>n.disabled=!ready||busy);
    root?.querySelectorAll('button').forEach(n=>n.disabled=!ready||busy||n.dataset.studioAllowed==='false');
    if(pathInput)pathInput.disabled=!!draft||busy||!ready;
    if(editor)editor.readOnly=!!draft&&latest?.status==='PUBLISHED';
    for(const b of root?.querySelectorAll('[data-write-action]')||[]){
      const action=b.dataset.writeAction;
      b.disabled=!ready||busy||b.dataset.studioAllowed==='false'
        ||dirty&&action!=='save'||latest?.revision!==baseRevision&&action!=='save'
        ||latest?.status!=='DRAFT'&&action!=='publishSource';
    }
    editorTools?.refreshState();
    coderTools?.refreshState();
    workspaceTools?.refreshState();
  }
  function newDraft(extension,target='SERVER'){
    draft=latest=null;source='';packageId='';packageRevision=0;packageCanonical='';packageName='';targetSide=target;path=target==='CLIENT'?(extension==='java'?'client/dev/mineagent/studio/ClientExtension.java':'client/main.'+extension):(extension==='java'?'server/extension.java':'server/studio.'+extension);
    baseRevision=runsOffset=0;dirty=false;renderEditor();
  }
  async function list(){
    const e=epoch,ticket=++serial;draft=latest=null;busy=true;lock();
    try{
      const value=await request({kind:'list',offset:String(offset)});if(!current(e,ticket))return;
      releaseEditor();root.replaceChildren();editor=status=notice=runs=null;busy=false;
      el('h3','Code Studio · SERVER / CLIENT · Java / Rhino',root);
      el('p',__uiT("读取本人真实旧/新草稿。保存、发布源码、SERVER执行或CLIENT本机下载/执行分别操作；不自动调用模型或重放未知执行。"),root,'muted');
      const actions=el('div',null,root,'actions');
      for(const type of ['js','mjs','java'])button(__uiT("新建 SERVER ")+type,actions,()=>newDraft(type,'SERVER'));
      for(const type of ['js','mjs','java'])button(__uiT("新建 CLIENT ")+type,actions,()=>newDraft(type,'CLIENT'));
      button(__uiT("Coder 请求与候选历史"),actions,()=>{newDraft('js');coderTools?.openHistory();});
      button(__uiT("包目录"),actions,openCatalog);button(__uiT("Native API / 编译来源"),actions,openNativeApi);
      if(!value.drafts.length)el('p',__uiT("暂无可读取草稿；请选择真实 Agent 并填写源码创建，不运行占位示例。"),root,'muted');
      for(const d of value.drafts){
        const card=el('article',null,root,'event-card');el('strong',d.targetSide+' · '+d.path+' · '+d.status+' · r'+d.revision,card);
        el('p',__uiT("草稿 ")+d.id+' · Package '+d.packageId,card,'muted');button(__uiT("读取 / 编辑 / 发布"),card,()=>void loadDraft(d.id));
      }
      const pages=el('div',null,root,'actions');
      button(__uiT("上一页"),pages,()=>{offset=Math.max(0,offset-8);void list();},offset>0);
      button(__uiT("下一页"),pages,()=>{offset=value.nextOffset;void list();},value.more);
    }catch(error){if(current(e,ticket)){releaseEditor();root.replaceChildren();el('p',error.message,root,'error');button(__uiT("重读列表"),root,()=>void list());}}
    finally{if(current(e,ticket)){busy=false;lock();}}
  }
  async function loadDraft(id){
    const e=epoch,ticket=++serial;busy=true;lock();
    try{
      const info=await request({kind:'get',draftId:id,revision:'0',offset:'0'});if(!current(e,ticket))return;
      let text='',position=0;
      while(true){
        const chunk=await request({kind:'source',draftId:id,revision:String(info.revision),offset:String(position)});
        if(!current(e,ticket))return;
        if(chunk.hash!==info.sourceHash)throw new Error('STUDIO_SOURCE_CHANGED');
        text+=chunk.text;if(text.length>16000)throw new Error('STUDIO_SOURCE_LIMIT');
        if(!chunk.more)break;if(chunk.nextOffset<=position)throw new Error('STUDIO_SOURCE_PAGE');position=chunk.nextOffset;
      }
      draft={id};latest=info;source=text;baseRevision=info.revision;path=info.path;targetSide=info.targetSide||(/client\//.test(info.path)?'CLIENT':'SERVER');packageId=info.packageId;
      packageRevision=info.packageRevision;packageCanonical=info.packageCanonical||'';packageName=info.packageName;baseWorkspaceHash=info.workspaceHash||info.sourceHash;dirty=false;runsOffset=0;busy=false;renderEditor();
    }catch(error){if(current(e,ticket)){busy=false;el('p',error.message,root,'error');}}
    finally{if(current(e,ticket)){busy=false;lock();}}
  }
  function renderEditor(){
    releaseEditor();root.replaceChildren();renderedRuns='';el('h3',draft?__uiT("Code Studio · 编辑与生命周期"):__uiT("Code Studio · 新草稿"),root);
    el('p',isClient()?__uiT("CLIENT源码发布后不会在服务器执行；Rhino使用client host，Java主类实现ClientRuntimeExtension。发布、下载、本机确认与运行分开；本机原生代码可访问文件/网络/剪贴板/账号可见数据。"):__uiT("SERVER Java使用RuntimeExtension；Rhino使用host/on/schedule/track。可调用真实Java，不是强制沙箱。"),root,'warning');
    const actions=el('div',null,root,'actions');
    button(__uiT("草稿列表（放弃未保存编辑）"),actions,()=>{dirty=false;void list();});
    button(__uiT("包目录"),actions,openCatalog);button(__uiT("查看 Native API"),actions,openNativeApi);
    const labels=el('div',null,root,'event-toolbar');
    const pathLabel=el('label',__uiT("源码路径"),labels);pathInput=el('input',null,pathLabel);pathInput.value=path;pathInput.maxLength=128;pathInput.disabled=!!draft;
    const nameLabel=el('label',__uiT("统一包名称"),labels);nameInput=el('input',null,nameLabel);nameInput.value=packageName;nameInput.maxLength=128;nameInput.placeholder=__uiT("发布源码时使用");
    const agentLabel=el('label',__uiT("新任务归属 Agent"),labels);agentSelect=el('select',null,agentLabel);
    el('option',__uiT("请选择可管理的真实 Agent"),agentSelect).value='';for(const a of agents)el('option',a.name,agentSelect).value=a.id;
    editor=el('textarea',null,root,'event-json');editor.dataset.studioSource='true';editor.value=source;editor.maxLength=16000;editor.rows=18;editor.spellcheck=false;editor.setAttribute('aria-label',__uiT("Java 或 Rhino 源码"));
    baseText=editor.value;
    editor.oninput=()=>{source=editor.value;dirty=editor.value!==baseText;report(__uiT("源码已编辑；先前检查不适用于此版本。"));renderStatus();};
    status=el('p','',root,'muted');notice=el('p','',root,'warning');notice.setAttribute('aria-live','polite');notice.style.whiteSpace='pre-wrap';
    editorTools=attachStudioEditor({container:root,textarea:editor,language:()=>/\.java$/i.test(pathInput.value)?'JAVA':'RHINO',canEdit:()=>ready&&!busy&&(!draft||latest?.status==='DRAFT'),notify:report});
    if(isClient())el('p',__uiT("CLIENT Coder 上下文尚未接入本机依赖与 CLIENT 编译来源；本批仅允许手工编辑，避免把 SERVER Coder 结果误标为 CLIENT。"),root,'muted');
    else coderTools=attachStudioCoder({container:root,send,available:()=>visible()&&!busy,openDraft:id=>void loadDraft(id),notify:report,
      getContext:()=>({baseDraft:draft?.id||'',baseRevision:draft?baseRevision:0,path:pathInput.value,agentId:draft?(latest?.agentId||''):agentSelect.value,source:editor.value,dirty:!!draft&&(dirty||latest?.revision!==baseRevision||workspaceTools?.hasUnsaved()),fileCount:latest?.fileCount||1,nativeApi:nativeApiContext?.()||{snapshot:'',items:[],overlays:[]},dependencyCount:latest?.dependencyCount||0,workspace:latest?.workspace||false,publications:latest?.publications||[]})});
    workspaceTools=attachStudioWorkspace({container:root,send,available:()=>visible()&&!busy,notify:report,
      getContext:()=>({id:draft?.id||'',revision:baseRevision,status:latest?.status||'',dirty:dirty||!!latest&&latest.revision!==baseRevision,targetSide}),
      onChanged:async result=>{if(draft?.id!==result.draftId)return;if(dirty){report(__uiT("工作区已保存，期间新的主文件编辑保留；请明确重载后再发布/运行。"));await observe();return;}const desiredName=nameInput.value,changedName=desiredName!==packageName;await loadDraft(result.draftId);if(changedName&&nameInput?.isConnected)nameInput.value=desiredName;}});
    pathInput.addEventListener('input',()=>editorTools?.schedulePreview());
    const writing=el('div',null,root,'actions');
    button(__uiT("检查当前 Rhino 源码（不执行）"),writing,()=>void checkSource());
    if(!draft)button(__uiT("创建持久草稿与任务"),writing,()=>void act('create'));
    else{
      button(__uiT("保存草稿"),writing,()=>void act('save'),latest.status==='DRAFT').dataset.writeAction='save';
      button(__uiT("发布为统一 RuntimePackage 源码"),writing,()=>void act('publishSource')).dataset.writeAction='publishSource';
      button(__uiT("读取统一包另建新稿（放弃本地编辑）"),writing,()=>void openPackage(packageId,targetSide,isJava()?'JAVA':'RHINO'),packageRevision>0).dataset.studioAction='newFromPackage';
      if(isClient()){const download=button(__uiT("下载已发布 CLIENT 源码（不执行）"),root,()=>void downloadClient(),packageRevision>0&&!!packageCanonical);download.dataset.clientDownload='true';}
      else{const label=el('label',null,root),consent=el('input',null,label);consent.type='checkbox';
        label.append(document.createTextNode(isJava()?__uiT("明确编译并调用 Java start；可能产生真实世界或外部副作用。"):__uiT("明确执行 Rhino 顶层并允许后续 tick/schedule；关闭界面或退出不会代替停止。")));
        button(isJava()?__uiT("编译并启动此保存版本"):__uiT("执行此保存的 Rhino 版本"),root,()=>{if(!consent.checked){report(__uiT("请先确认 Native 执行。"));return;}void act('run');},latest.status==='DRAFT').dataset.writeAction='run';}
      button(__uiT("重载服务器草稿（放弃本地编辑）"),root,()=>void loadDraft(draft.id));
      button(__uiT("查看保留旧稿（只读）"),root,()=>void history(0));
    }
    el('p',isClient()?__uiT("发布源码不等于下载或运行；下载完成仍须在本机管理器再次确认。依赖也不会自动下载、批准或启动。"):__uiT("发布源码不等于运行。更新源包会使旧执行在后续授权检查中失效；停止只清理受管句柄，不承诺回滚副作用。"),root,'muted');
    historyPane=el('section',null,root);runs=el('section',null,root);renderStatus();
  }
  async function history(offset){
    if(!draft||busy||!visible())return;
    const e=epoch,ticket=serial,read=++historySerial,id=draft.id,revision=latest.revision,area=historyPane;
    area.replaceChildren();el('p',__uiT("读取原 CodeDraft 保存的历史文本，不执行旧稿…"),area,'muted');
    try{
      const info=await request({kind:'draftHistory',draftId:id,revision:String(revision),offset:String(offset)});
      if(!current(e,ticket)||read!==historySerial||!area.isConnected)return;
      area.replaceChildren();el('h3',__uiT("保留旧稿 · ")+info.total+__uiT(" 项"),area);
      el('p',__uiT("原记录只保留最近最多 20 次保存前文本；序号不是历史 revision 或时间，不补造已丢失历史。采用只改本地编辑，仍需明确保存。"),area,'muted');
      if(!info.history.length)el('p',__uiT("没有保留旧稿。"),area,'muted');
      for(const item of info.history){const card=el('article',null,area,'event-card');el('strong',__uiT("保留序号 ")+(item.index+1)+' · '+item.characters+__uiT(" 字符"),card);el('p',item.hash,card,'muted');button(__uiT("读取全文 / 与当前编辑对照"),card,()=>void historySource(info,item,card));}
      const pages=el('div',null,area,'actions');button(__uiT("更早的保留稿"),pages,()=>void history(Math.max(0,offset-8)),offset>0);button(__uiT("后面的保留稿"),pages,()=>void history(info.nextOffset),info.more);
    }catch(error){if(current(e,ticket)&&read===historySerial&&area.isConnected){area.replaceChildren();el('p',error.message+__uiT("；草稿可能已更新，请重新读取。"),area,'error');}}
  }
  async function historySource(info,item,card){
    if(busy||!visible())return;
    const e=epoch,ticket=serial,read=historySerial,stamp=editor.value,selected=Symbol();card._historyRead=selected;
    const currentRead=()=>current(e,ticket)&&read===historySerial&&card.isConnected&&card._historyRead===selected;
    try{
      let text='',offset=0;
      while(true){
        const chunk=await request({kind:'historySource',draftId:info.draftId,revision:String(info.revision),entryIndex:String(item.index),hash:item.hash,offset:String(offset)});
        if(!currentRead())return;if(chunk.hash!==item.hash)throw new Error('STUDIO_HISTORY_CHANGED');text+=chunk.text;
        if(text.length>16000)throw new Error('STUDIO_HISTORY_SOURCE_LIMIT');if(!chunk.more)break;if(chunk.nextOffset<=offset)throw new Error('STUDIO_HISTORY_PAGE');offset=chunk.nextOffset;
      }
      card.replaceChildren();el('strong',__uiT("保留序号 ")+(item.index+1)+' · SHA '+item.hash,card);
      el('p',__uiT("两侧原文对照，不自动合并；当前编辑快照仅用于本次比较。")+(text===stamp?__uiT("两份文本相同。"):''),card,'muted');
      const grid=el('div',null,card,'studio-source-comparison');const old=el('section',null,grid),local=el('section',null,grid);
      el('h4',__uiT("保留旧稿"),old);el('pre',text,old,'event-json');el('h4',__uiT("读取时的本地编辑"),local);el('pre',stamp,local,'event-json');
      if(latest?.status==='DRAFT'){
        const label=el('label',null,card),confirm=el('input',null,label);confirm.type='checkbox';label.append(document.createTextNode(__uiT("确认用此旧稿替换当前本地编辑（可在本窗口撤销，不自动保存或运行）。")));
        button(__uiT("载入本地编辑"),card,()=>{
          if(!confirm.checked)return;
          if(!currentRead()||draft?.id!==info.draftId||baseRevision!==info.revision||latest?.revision!==info.revision||latest.status!=='DRAFT'||editor.value!==stamp){report(__uiT("草稿或本地编辑已变化，请重新读取旧稿并确认。"));return;}
          if(editorTools?.replaceDocument(text))report(__uiT("旧稿已载入本地；尚未保存、发布或执行。"));
        });
      }else el('p',__uiT("已发布草稿只读。需要修改时先从统一包另建编辑稿。"),card,'muted');
    }catch(error){if(currentRead())el('p',error.message,card,'error');}
  }
  function renderStatus(){
    if(!status?.isConnected)return;lock();
    if(!latest){
      status.textContent=packageId?__uiT("基于 Package ")+packageId+' @ r'+packageRevision+__uiT("，尚未创建新草稿。"):__uiT("尚未创建草稿；不会自动生成或执行样例。");return;
    }
    status.textContent=__uiT("草稿 ")+latest.id+' · '+(latest.targetSide||targetSide)+' · '+latest.status+' r'+latest.revision+__uiT(" · 包库 r")+latest.packageRevision
      +' · '+(latest.fileCount||1)+__uiT(" 个源码文件 · ")+(latest.dependencyCount||0)+__uiT(" 直接依赖")
      +(latest.nativePublicationContext?__uiT(" · 发布将复用冻结 Native raw ")+(latest.nativeTypeCount||0)+' / overlay '+(latest.nativeOverlayCount||0):latest.nativeContextInvalidated?__uiT(" · 源码已改，原 Native context 失效；发布将重新按 raw 编译"):'')
      +(dirty?__uiT(" · 未保存的本地编辑保留"):latest.revision!==baseRevision?__uiT(" · 服务器版本已变化，请明确重载"):'')
      +__uiT("；library enabled 不等于当前执行状态。");
    const fingerprint=JSON.stringify([latest.publications,latest.legacyActive,latest.legacyLoaded,latest.legacyCanStop,latest.morePublications,runsOffset]);
    if(renderedRuns===fingerprint)return;renderedRuns=fingerprint;runs.replaceChildren();
    if(latest.legacyActive){
      const card=el('article',null,runs,'event-card');el('strong',__uiT("旧 RuntimePackageManager · 当前句柄 ")+latest.legacyLoaded,card);
      el('p',__uiT("不是新的 Studio publication。必须先停止准确旧脚本，才能运行同包的新源码；不自动迁移运行状态。"),card,'warning');
      if(latest.legacyCanStop){
        const label=el('label',null,card),confirm=el('input',null,label);confirm.type='checkbox';
        label.append(document.createTextNode(__uiT("确认停止与此旧稿 Task / 源码 / 包版本完全匹配的脚本，并停用对应旧发布记录。")));
        button(__uiT("停止匹配的旧脚本"),card,()=>{if(confirm.checked)void stopLegacy();});
      }else el('p',__uiT("此草稿与当前旧运行来源不匹配；请读取原草稿，不以新源码代替停止目标。"),card,'muted');
    }
    for(const r of latest.publications){
      const card=el('article',null,runs,'event-card'),rhino=r.language==='RHINO';
      el('strong',(rhino?'Rhino':'Java')+' · '+r.state+__uiT(" · 当前句柄 ")+r.loaded+(r.suspended?__uiT(" · 已停止 callback 分派"):''),card);
      el('p',r.id+' · '+r.error+(r.uncertain?__uiT(" · 存在未知影响"):'')+(r.metadataPending?__uiT(" · 回执待收束，不重新执行"):''),card,'muted');
      el('p',__uiT("源 Package SHA ")+(r.packageHash||__uiT("旧记录无统一包 SHA"))+(rhino?' · source '+r.sourceHash:' · artifact '+r.artifact+' · classpath '+r.nativeClasspath),card,'muted');
      el('p',__uiT("冻结依赖 ")+(r.dependencyCount||0)+' · graph '+(r.dependencyHash||__uiT("无"))+__uiT(" · 被 ")+(r.consumerCount||0)+__uiT(" 个 publication 引用（须先停止消费者）"),card,'muted');
      if(r.legacyStop)el('p',__uiT("旧脚本停止回执，不是新脚本执行。"),card,'muted');
      if(rhino&&r.source)el('p',r.source+':'+r.line+':'+r.column,card,'error');
      const details=el('section',null,card);button(rhino?__uiT("读取脚本故障位置"):__uiT("读取保留的编译诊断"),card,()=>void diagnostics(r,0,details));
      const stoppable=rhino?['PUBLISHED','OUTCOME_UNKNOWN','STARTING','START_RETURNED','STOPPING']:['PUBLISHED','OUTCOME_UNKNOWN'];
      if(!r.legacyStop&&stoppable.includes(r.state)){
        const label=el('label',null,card),confirm=el('input',null,label);confirm.type='checkbox';
        label.append(document.createTextNode(__uiT("仅停止此 publication，不宣称世界或外部副作用已回滚。")));
        button(__uiT("停止原执行"),card,()=>{if(confirm.checked)void stop(r);});
      }
    }
    if(!latest.publications.length)el('p',__uiT("此页没有执行记录；保存或源码发布不会自动创建执行。"),runs,'muted');
    const pages=el('div',null,runs,'actions');
    button(__uiT("较新执行记录"),pages,()=>void publicationPage(Math.max(0,runsOffset-8)),runsOffset>0);
    button(__uiT("较早执行记录"),pages,()=>void publicationPage(latest.nextPublicationOffset),latest.morePublications);
  }
  async function diagnostics(run,offset,area){
    const e=epoch,ticket=serial;
    try{
      const value=await request({kind:run.language==='RHINO'?'scriptDiag':'diagnostics',publicationId:run.id,publicationRevision:String(run.revision),offset:String(offset)});
      if(!current(e,ticket)||!area.isConnected)return;area.replaceChildren();
      el('pre',value.text||__uiT("没有保留的诊断；状态与未知影响以原执行记录为准。"),area,'event-json');
      button(__uiT("上一段"),area,()=>void diagnostics(run,Math.max(0,offset-4096),area),offset>0);
      button(__uiT("下一段"),area,()=>void diagnostics(run,value.nextOffset,area),value.more);
    }catch(error){if(current(e,ticket)&&area.isConnected)el('p',error.message,area,'error');}
  }
  async function checkSource(){
    if(busy||!visible())return;
    if(!/\.(m?js)$/i.test(pathInput.value)){report(__uiT("此检查只适用于 Rhino JS/mjs；Java 使用明确的编译入口。"));return;}
    const e=epoch,ticket=serial,text=editor.value,sourcePath=pathInput.value;
    try{
      const result=await request({kind:'scriptCheck',source:text});
      if(!current(e,ticket)||editor.value!==text||pathInput.value!==sourcePath)return;
      report(result.accepted?__uiT("Rhino preflight 接受；未执行，不证明 Native API 或实际效果正确。")
        :result.diagnostics.map(d=>sourcePath+':'+d.line+' · '+d.code+' · '+d.message).join('\n')+(result.more?__uiT("\n仅显示前 8 条诊断。"):''));
    }catch(error){if(current(e,ticket)&&editor.value===text)report(error.message);}
  }
  async function publicationPage(position){if(polling||busy)return;runsOffset=position;await observe();}
  async function observe(){
    if(!draft||polling||busy||!visible())return;
    const e=epoch,ticket=serial,id=draft.id,position=runsOffset;polling=true;
    try{
      const info=await request({kind:'get',draftId:id,revision:'0',offset:String(position)});
      if(!current(e,ticket)||draft?.id!==id||position!==runsOffset)return;
      // Publication may increment draft revision without changing source. Never attach new source to old local text.
      if(!dirty&&latest?.sourceHash===info.sourceHash&&latest?.path===info.path&&baseRevision===latest.revision&&baseWorkspaceHash===(info.workspaceHash||info.sourceHash))baseRevision=info.revision;
      latest=info;targetSide=info.targetSide||targetSide;packageRevision=info.packageRevision;packageCanonical=info.packageCanonical||packageCanonical;renderStatus();
    }catch(error){if(current(e,ticket))report(error.message);}
    finally{if(e===epoch)polling=false;}
  }
  async function downloadClient(){
    if(!isClient()||busy||dirty||!packageId||!packageRevision||!packageCanonical)return;const e=epoch,ticket=serial;busy=true;lock();
    try{const result=await send('clientScript',{kind:isJava()?'downloadJava':'download',packageId,packageRevision:String(packageRevision),canonical:packageCanonical});if(!current(e,ticket))return;if(result.code!=='ACCEPTED'&&!String(result.code).endsWith('DOWNLOADED_NOT_APPROVED'))throw new Error(result.code||'CLIENT_CODE_DOWNLOAD_FAILED');report(__uiT("已受理本机下载；不会编译或执行。请在本机代码管理器读取状态并另行确认启动。"));openClientScripts?.(packageId);}
    catch(error){if(current(e,ticket))report(error.message+__uiT("；不自动重发。"));}
    finally{if(e===epoch){busy=false;lock();}}
  }
  async function act(action){
    if(busy||!visible())return;
    if(workspaceTools?.hasUnsaved()){report(__uiT("工作区文件有未保存编辑，请先保存或明确放弃，避免只使用旧文件集。"));return;}
    if((action==='run'||action==='publishSource')&&dirty){report(__uiT("请先保存源码，再发布或运行。"));return;}
    const e=epoch,ticket=serial;busy=true;
    const args=action==='create'?{action,path:pathInput.value,source:editor.value,agentId:agentSelect.value,packageId,packageRevision:String(packageRevision),targetSide}
      :{action,draftId:draft.id,revision:String(baseRevision)};
    if(action==='save')args.source=editor.value;
    if(action==='publishSource'){args.packageRevision=String(packageRevision);args.name=nameInput.value||latest.packageName;}
    lock();
    try{
      const result=await request(args,true);if(!current(e,ticket))return;busy=false;
      if(action==='create'){await loadDraft(result.draftId);return;}
      if(action==='save'){dirty=false;await loadDraft(draft.id);return;}
      report(JSON.stringify(result));await observe();
    }catch(error){if(current(e,ticket))report(error.message+__uiT("；请只读核查原记录，不自动重发。"));}
    finally{if(e===epoch){busy=false;lock();}}
  }
  async function stop(run){
    if(busy||!visible())return;const e=epoch,ticket=serial;busy=true;lock();
    try{
      const info=await request({kind:'get',draftId:run.draft,revision:'0',offset:'0'});
      if(!current(e,ticket)||!visible())return;
      await request({action:'stop',draftId:run.draft,revision:String(info.revision),publicationId:run.id,publicationRevision:String(run.revision)},true);
      if(current(e,ticket)){busy=false;await observe();}
    }catch(error){if(current(e,ticket))report(error.message+__uiT("；停止结果以原账本为准，不自动重复 cleanup。"));}
    finally{if(e===epoch){busy=false;lock();}}
  }
  async function stopLegacy(){
    if(busy||!visible()||!draft||!latest?.legacyCanStop)return;
    const e=epoch,ticket=serial,id=draft.id;busy=true;lock();
    try{
      const info=await request({kind:'get',draftId:id,revision:'0',offset:'0'});
      if(!current(e,ticket)||draft?.id!==id||!visible())return;
      if(!info.legacyCanStop)throw new Error('STUDIO_SCRIPT_LEGACY_SOURCE_CHANGED');
      const result=await request({action:'stopLegacyScript',draftId:id,revision:String(info.revision)},true);
      if(current(e,ticket)){busy=false;report(JSON.stringify(result));await observe();}
    }catch(error){if(current(e,ticket))report(error.message+__uiT("；旧执行不自动重放或替换。"));}
    finally{if(e===epoch){busy=false;lock();}}
  }
  async function openPackage(id,target='SERVER',language=''){
    open(false);const e=epoch,ticket=++serial;busy=true;
    try{
      const r=await send('packageCatalog',{kind:'package',packageId:id,headRevision:'0',headHash:''});
      if(!current(e,ticket))return;if(r.code!=='OBSERVED'||r.values?.errorCode)throw new Error(r.values?.errorCode||r.code);
      const info=JSON.parse(r.values.state);let text='',position=0,sourcePath='';
      while(true){
        const part=await request({kind:'package',packageId:id,packageRevision:String(info.revision),targetSide:target,language:language||'RHINO',offset:String(position)});
        if(!current(e,ticket))return;
        if(part.hash!==info.canonicalSha256||sourcePath&&sourcePath!==part.path)throw new Error('STUDIO_SOURCE_CHANGED');
        sourcePath=part.path;text+=part.source;if(text.length>16000)throw new Error('STUDIO_SOURCE_LIMIT');
        if(!part.more)break;if(part.nextOffset<=position)throw new Error('STUDIO_SOURCE_PAGE');position=part.nextOffset;
      }
      if(!/\.(java|m?js)$/i.test(sourcePath))throw new Error('STUDIO_SOURCE_PATH');
      draft=latest=null;packageId=id;packageRevision=info.revision;packageCanonical=info.canonicalSha256;packageName=info.sourceName||info.name;
      source=text;path=sourcePath;targetSide=target;dirty=false;baseRevision=runsOffset=0;busy=false;renderEditor();
    }catch(error){if(current(e,ticket))el('p',error.message,root,'error');}
    finally{if(e===epoch){busy=false;lock();}}
  }
  function open(withList=true){
    releaseEditor();
    root=windowFor('runtime-java-studio','Code Studio · Java / Rhino');epoch++;serial++;
    busy=polling=dirty=false;draft=latest=null;offset=runsOffset=0;editor=status=notice=runs=pathInput=null;
    root.replaceChildren();if(ready&&withList)void list();
  }
  function session(value){
    const key=JSON.stringify([value?.sessionId,value?.serverInstanceId,value?.binding?.worldId,value?.binding?.viewerPlayerId]);
    if(key===scope&&ready===!!value)return;scope=key;ready=!!value;epoch++;serial++;busy=polling=false;draft=latest=null;
    releaseEditor();root?.replaceChildren();if(root?.isConnected)el('p',__uiT("上下文变化，请重新打开 Code Studio；旧回复不创建新稿或运行。"),root,'muted');
  }
  setInterval(()=>{if(visible()&&!busy&&draft)void observe();},2500);
  return {open:()=>open(),openPackage,nativeContextChanged:()=>coderTools?.refreshState(),session,reset:()=>session(null),agents:value=>{agents=value||[];}};
}
