import {t as __uiT,tf as __uiF} from './i18n.mjs';
// File edits change the existing CodeDraft revision; no independent package or execution state.
export function attachStudioWorkspace({container,send,getContext,onChanged,available,notify}){
  let disposed=false,busy=false,serial=0,version=0,offset=0,selected=null,original='',snapshot=null;
  let fileEditor=null,filePath=null,navigation='',dependencySelect=null;
  function hasUnsaved(){return !!(version===0&&fileEditor?.isConnected&&!fileEditor.readOnly&&(fileEditor.value!==original||filePath.value!==(selected?.path||'')));}
  function leave(key){if(hasUnsaved()&&navigation!==key){navigation=key;notify(__uiT("此文件有未保存编辑；再次点击相同导航才放弃。"));return false;}navigation='';return true;}
  const el=(tag,text,parent,cls)=>{const n=document.createElement(tag);if(text!=null)n.textContent=text;if(cls)n.className=cls;parent.append(n);return n;};
  const panel=el('details',null,container,'studio-editor-tools');el('summary',__uiT("工作区文件 / 入口 / 完整版本"),panel);
  const scopeText=el('p',__uiT("同一 target side 与语言，最多 64 文件、每文件 16000 字符、总源码 1 MiB。文件改动只保存草稿，不自动发布、下载或运行。"),panel,'muted');
  const info=el('p',__uiT("先创建或读取保存稿。"),panel,'muted'),commands=el('div',null,panel,'actions'),listArea=el('section',null,panel),editArea=el('section',null,panel);
  const valid=()=>!disposed&&panel.isConnected&&available();
  const writable=()=>valid()&&!busy&&version===0&&!getContext().dirty&&getContext().status==='DRAFT';
  const button=(text,parent,fn,allowed=true)=>{
    const b=el('button',text,parent);b.type='button';b.dataset.workspaceAllowed=String(allowed);b.dataset.studioAllowed=String(allowed);
    b.onclick=()=>{if(valid()&&!busy&&!b.disabled&&b.dataset.workspaceAllowed!=='false')fn();};return b;
  };
  function refreshState(){
    if(disposed)return;
    for(const b of panel.querySelectorAll('button')){b.disabled=!valid()||busy||b.dataset.workspaceAllowed==='false';b.dataset.studioAllowed=String(!b.disabled);}
    panel.querySelectorAll('input,textarea,select').forEach(n=>n.disabled=!valid()||busy);
    const c=getContext();scopeText.textContent=(c.targetSide==='CLIENT'?'CLIENT':'SERVER')+__uiT(" 同 side 同语言工作区，最多 64 文件、每文件 16000 字符、总源码 1 MiB。Rhino require 使用完整工作区路径；不假定相对导入或 ESM。文件改动只保存草稿，不自动发布、下载或运行。");info.textContent=c.id?__uiT("工作区 ")+c.id+' @ r'+c.revision+(c.dirty?__uiT(" · 主文件有未保存编辑或版本变化，请先处理"):'')+' · '+(version?__uiT("只读历史 r")+version:__uiT("当前保存稿")):__uiT("先创建或读取保存稿。");
  }
  async function request(args,write=false){
    const r=await send('javaStudio',write?{kind:'write',operationId:crypto.randomUUID(),confirmed:'true',...args}:args);
    if(r.code!==(write?'APPLIED':'OBSERVED')||r.values?.errorCode)throw new Error(r.values?.errorCode||r.code);return JSON.parse(r.values.state);
  }
  async function files(page=0,which=0){
    const c=getContext();if(!c.id||!valid()||busy)return;if(c.dirty){notify(__uiT("先保存/重新读取主文件，以固定整个工作区版本。"));return;}
    if(!leave('files:'+page+':'+which))return;
    const ticket=++serial;busy=true;refreshState();
    try{
      const data=await request({kind:'workspaceFiles',draftId:c.id,revision:String(c.revision),version:String(which),offset:String(page)});
      if(!valid()||ticket!==serial||getContext().id!==c.id)return;
      snapshot={id:c.id,revision:c.revision,hash:data.workspaceHash};version=which;offset=page;selected=null;editArea.replaceChildren();listArea.replaceChildren();
      el('p',__uiT("入口 ")+data.entry+' · workspace SHA '+data.workspaceHash,listArea,'muted');
      for(const f of data.files){const card=el('article',null,listArea,'event-card');el('strong',f.path+(f.entry?' · entry':''),card);el('p',f.bytes+' bytes · '+f.hash,card,'muted');button(__uiT("读取 / 编辑"),card,()=>void load(f));}
      const pages=el('div',null,listArea,'actions');button(__uiT("前面文件"),pages,()=>void files(Math.max(0,page-8),which),page>0);button(__uiT("后面文件"),pages,()=>void files(data.nextOffset,which),data.more);
      if(which){const label=el('label',null,listArea),confirm=el('input',null,label);confirm.type='checkbox';label.append(document.createTextNode(__uiT("明确恢复此完整文件集/入口/依赖声明为新的草稿 revision，不发布、不运行。")));
        button(__uiT("恢复完整工作区"),listArea,()=>{if(confirm.checked)void mutate('restore','',String(which),'',true);},c.status==='DRAFT');}
    }catch(e){if(valid()&&ticket===serial)notify(e.message+__uiT("；旧本地文本不被回填。"));}
    finally{if(!disposed){busy=false;refreshState();}}
  }
  async function load(file){
    if(!snapshot||!valid()||busy)return;const ticket=++serial,s={...snapshot};busy=true;refreshState();
    if(!leave('load:'+file.path)){busy=false;refreshState();return;}
    try{
      let text='',position=0;
      while(true){
        const part=await request({kind:'workspaceSource',draftId:s.id,revision:String(s.revision),version:String(version),path:file.path,hash:file.hash,offset:String(position)});
        if(!valid()||ticket!==serial||snapshot!==null&&(snapshot.id!==s.id||snapshot.revision!==s.revision))return;
        if(part.hash!==file.hash||part.workspaceHash!==s.hash)throw new Error('STUDIO_WORKSPACE_FILE_CHANGED');
        text+=part.text;if(text.length>16000)throw new Error('STUDIO_WORKSPACE_SOURCE_LIMIT');if(!part.more)break;if(part.nextOffset<=position)throw new Error('STUDIO_WORKSPACE_PAGE');position=part.nextOffset;
      }
      selected=file;original=text;busy=false;editor(text);
    }catch(e){if(valid())notify(e.message);}
    finally{if(!disposed){busy=false;refreshState();}}
  }
  function editor(text=''){
    editArea.replaceChildren();const pathLabel=el('label',__uiT("文件路径（区分大小写）"),editArea),path=el('input',null,pathLabel);path.maxLength=128;path.value=selected?.path||'';
    const source=el('textarea',null,editArea,'event-json');source.rows=12;source.maxLength=16000;source.spellcheck=false;source.value=text;source.readOnly=version>0||getContext().status!=='DRAFT';source.setAttribute('aria-label',__uiT("工作区文件源码"));
    fileEditor=source;filePath=path;source.oninput=path.oninput=()=>navigation='';
    const row=el('div',null,editArea,'actions');
    if(!version&&getContext().status==='DRAFT'){
      button(selected?__uiT("保存此文件"):__uiT("添加文件（可先保存空白）"),row,()=>{
        if(selected&&path.value!==selected.path){notify(__uiT("改名使用下方独立动作，不将保存误作复制。"));return;}
        void mutate(selected?'put':'add',path.value,'',source.value);
      });
      if(selected){
        const confirmLabel=el('label',null,editArea),confirm=el('input',null,confirmLabel);confirm.type='checkbox';confirmLabel.append(document.createTextNode(__uiT("确认结构变更（未保存的此文件编辑不会带入）。路径改动不会自动改写 require/import。")));
        button(__uiT("按路径框改名"),editArea,()=>{if(confirm.checked&&source.value===original)void mutate('rename',selected.path,path.value,'');else notify(__uiT("先保存文件编辑并确认结构变更。"));});
        button(__uiT("设为入口"),editArea,()=>{if(confirm.checked&&source.value===original)void mutate('entry',selected.path,'','');},!selected.entry);
        button(__uiT("删除此文件"),editArea,()=>{if(confirm.checked&&source.value===original)void mutate('remove',selected.path,'','');},!selected.entry);
      }
    }else el('p',__uiT("此版本只读；恢复整个版本需另行确认。"),editArea,'muted');
    refreshState();
  }
  async function mutate(change,path,target,source,restore=false){
    if(!snapshot||!valid()||busy)return;const c=getContext();
    if(c.dirty||c.id!==snapshot.id||c.revision!==snapshot.revision||c.status!=='DRAFT'||!restore&&!writable()){notify(__uiT("主文件或工作区上下文已变化，请重读。"));return;}
    const ticket=++serial;busy=true;refreshState();
    try{
      const result=await request({action:'workspaceFile',draftId:c.id,revision:String(c.revision),change,path,target,source},true);
      if(!valid()||ticket!==serial)return;fileEditor=filePath=null;notify(__uiT("工作区已保存为 r")+result.revision+__uiT("，未发布或执行。"));await onChanged(result);
    }catch(e){if(valid())notify(e.message+__uiT("；只读核查原版本，不自动重发。"));}
    finally{if(!disposed){busy=false;refreshState();}}
  }
  async function history(page=0){
    const c=getContext();if(!c.id||!valid()||busy)return;const ticket=++serial;busy=true;refreshState();
    if(!leave('history:'+page)){busy=false;refreshState();return;}
    try{
      const value=await request({kind:'workspaceHistory',draftId:c.id,revision:String(c.revision),version:'0',offset:String(page)});
      if(!valid()||ticket!==serial)return;listArea.replaceChildren();editArea.replaceChildren();selected=null;
      el('p',__uiT("仅列真实记录的完整文件集版本；不把旧单文件 history 伪装成完整工作区。"),listArea,'muted');
      for(const v of value.history)button('r'+v.revision+' · '+v.entry+' · '+v.files+__uiT(" 文件 · ")+(v.dependencyCount||0)+__uiT(" 依赖"),listArea,()=>void files(0,v.revision));
      button(__uiT("前面版本"),listArea,()=>void history(Math.max(0,page-4)),page>0);button(__uiT("后面版本"),listArea,()=>void history(value.nextOffset),value.more);
    }catch(e){if(valid())notify(e.message);}
    finally{if(!disposed){busy=false;refreshState();}}
  }
  async function dependencies(page=0,which=version,candidatePage=0){
    const c=getContext();if(!c.id||!valid()||busy)return;
    if(c.dirty){notify(__uiT("先保存或重读主文件。"));return;}if(!leave('dependencies:'+page+':'+which))return;
    const ticket=++serial;busy=true;refreshState();
    try{
      const data=await request({kind:'dependencies',draftId:c.id,revision:String(c.revision),version:String(which),offset:String(page)});
      const candidates=!which?await request({kind:'dependencyCandidates',draftId:c.id,revision:String(c.revision),offset:String(candidatePage)}):null;
      if(!valid()||ticket!==serial||getContext().id!==c.id||getContext().revision!==c.revision)return;
      version=which;listArea.replaceChildren();editArea.replaceChildren();selected=null;dependencySelect=null;
      el('h4',__uiT("跨 RuntimePackage Java / Rhino 依赖"),listArea);
      const client=data.targetSide==='CLIENT';
      el('p',client?__uiT("所选包 ID + 精确 version 会写入声明，不自动下载、批准或启动。发布后由每台客户端本机解析准确签名版本；依赖必须分别下载、确认并先启动，被引用期间须先停止消费者。"):__uiT("所选包 ID + 精确 version 会写入声明，不自动安装或启动。需本人可管理、SERVER / HOT、同语言、已 PUBLISHED 且实际 loaded。复用已有运行实例；被引用期间须先停止消费者。"),listArea,'muted');
      el('p',data.resolution+' · '+data.total+__uiT(" 直接依赖 · graph ")+(data.graphHash||__uiT("无/未解析")),listArea,'muted');
      el('p',client?(data.java?__uiT("声明只保存草稿。CLIENT Java 在本机明确确认后才解析依赖、实际 Javac 并启动；服务器不冒充本机 graph 或 classpath。"):__uiT("CLIENT Rhino 使用 callPackage(UUID, exportName, argsArray) 调用已运行依赖，跨语言只交换有界中立数据；服务器不解析本机运行图。")):(data.java?__uiT("声明只保存草稿。Java 编译核验 JAR/Native classpath；Coder 沿用真实 API，不自动重试未知请求。"):__uiT("Rhino 使用 callPackage(UUID, exportName, argsArray)，调用已运行依赖入口的导出函数，数据隔离复制。Rhino Coder 需单独确认向 Provider 发送完整依赖 JS 源码，不执行 getter 或探测函数。")),listArea,'warning');
      for(const dep of data.dependencies){
        const card=el('article',null,listArea,'event-card');el('strong',dep.packageId+' @ '+dep.version,card);
        el('p',dep.state+(dep.className?' · '+dep.className:''),card,'muted');
        if(dep.publicationId)el('p','publication '+dep.publicationId+__uiT(" · 间接依赖 ")+dep.transitiveCount+__uiT(" · 消费者 ")+dep.consumerCount+' '+(dep.consumers||[]).join(', '),card,'muted');
        if(!which&&c.status==='DRAFT'){const label=el('label',null,card),confirm=el('input',null,label);confirm.type='checkbox';label.append(document.createTextNode(__uiT("确认从草稿移除此声明（不停止已有实例）")));
          button(__uiT("移除声明"),card,()=>{if(hasUnsaved()){notify(__uiT("先处理未保存编辑。"));return;}if(confirm.checked)void dependencyChange(c,'remove',dep.packageId,'',0);});}
      }
      const pages=el('div',null,listArea,'actions');button(__uiT("前面依赖"),pages,()=>void dependencies(Math.max(0,page-4),which,candidatePage),page>0);button(__uiT("后面依赖"),pages,()=>void dependencies(data.nextOffset,which,candidatePage),data.more);
      if(!which&&data.supported&&c.status==='DRAFT'){
        const label=el('label',__uiT("选择可归属的准确依赖包与版本"),editArea),select=el('select',null,label);dependencySelect=select;const empty=el('option',__uiT("请选择当前目录中的兼容包"),select);empty.value='';
        for(const item of candidates.items){const option=el('option',item.name+' · '+item.targetSide+' '+item.language+' · '+item.version+' · '+item.packageId,select);option.value=item.packageId;option.dataset.version=item.version;option.dataset.revision=String(item.revision);}
        if(!candidates.items.length)el('p',__uiT("本页没有兼容候选；可继续翻页。不会要求复制粘贴 package UUID 或版本。"),editArea,'muted');
        const cl=el('label',null,editArea),confirm=el('input',null,cl);confirm.type='checkbox';cl.append(document.createTextNode(__uiT("确认添加/更改声明，保留完整旧版本，不发布或运行。")));
        button(__uiT("保存所选依赖声明"),editArea,()=>{const option=select.selectedOptions[0];if(confirm.checked&&option?.value)void dependencyChange(c,'put',option.value,option.dataset.version||'',Number(option.dataset.revision));else notify(__uiT("请选择目录中的准确依赖并确认。"));});
        const cp=el('div',null,editArea,'actions');button(__uiT("前面候选"),cp,()=>void dependencies(page,which,Math.max(0,candidatePage-8)),candidatePage>0);button(__uiT("后面候选"),cp,()=>void dependencies(page,which,candidates.nextOffset),candidates.more);
      }else el('p',which?__uiT("历史声明只读；完整恢复包含这些依赖。"):__uiT("当前声明只读。"),editArea,'muted');
    }catch(e){if(valid())notify(e.message+__uiT("；不自动重发。"));}finally{if(!disposed){busy=false;refreshState();}}
  }
  async function dependencyChange(c,change,id,value,packageRevision){
    if(!valid()||busy||version!==0||getContext().id!==c.id||getContext().revision!==c.revision||getContext().dirty||getContext().status!=='DRAFT')return;
    if(!/^[a-f0-9-]{36}$/i.test(id)||change==='put'&&(!/^[0-9A-Za-z_.+-]{1,64}$/.test(value)||!Number.isSafeInteger(packageRevision)||packageRevision<1)){notify(__uiT("所选依赖版本已失效，请刷新候选。"));return;}
    const ticket=++serial;busy=true;refreshState();
    try{
      const result=await request({action:'dependency',draftId:c.id,revision:String(c.revision),change,packageId:id,version:value,...(change==='put'?{packageRevision:String(packageRevision)}:{})},true);
      if(!valid()||ticket!==serial)return;dependencySelect=null;notify(__uiT("依赖声明已保存 r")+result.revision+__uiT("；未发布或运行。"));await onChanged(result);
    }catch(e){if(valid())notify(e.message+__uiT("；保留当前选择，请只读核查草稿。"));}finally{if(!disposed){busy=false;refreshState();}}
  }
  button(__uiT("当前文件 / 刷新"),commands,()=>void files());
  button(__uiT("添加文件"),commands,()=>{if(!writable()||!snapshot){notify(__uiT("先读取当前工作区并保持主文件已保存。"));return;}if(!leave('add'))return;selected=null;original='';editor();});
  button(__uiT("完整工作区历史"),commands,()=>void history());
  button(__uiT("依赖声明 / 已加载诊断"),commands,()=>void dependencies());
  const toggle=()=>{if(panel.open&&!snapshot)void files();};panel.addEventListener('toggle',toggle);refreshState();
  return {refreshState,hasUnsaved,dispose:()=>{disposed=true;serial++;panel.removeEventListener('toggle',toggle);}};
}
