import {t as __uiT,tf as __uiF} from './i18n.mjs';
import {NativeApiSelection} from './native-api-selection.mjs';

export function createNativeApiCards({windowFor,send}) {
  let root=null,body=null,status=null,ready=false,scope='',epoch=0,serial=0,querying=false,polling=false,refreshing=false,pendingOperation='';
  let snapshot='',module='',className='',classToken='',methodName='',methodDescriptor='',search='',offset=0,memberOffset=0,methodOffset=0,parentOffset=0,textOffset=0,page='modules',parentMembersPage='members',lastState=null;
  const selection=new NativeApiSelection(16),overlayLabels=new Map();let selectionListener=()=>{};
  const selectionChanged=()=>selectionListener(selection.wire());
  const el=(tag,text,parent,cls)=>{const n=document.createElement(tag);if(text!=null)n.textContent=text;if(cls)n.className=cls;parent.append(n);return n;};
  const button=(text,parent,run,allowed=true)=>{const n=el('button',text,parent);n.type='button';n.disabled=!ready||!allowed;n.onclick=()=>{if(ready&&allowed)run();};return n;};
  const visible=()=>ready&&root?.isConnected&&document.body.dataset.workspaceVisible!=='false'&&root.getClientRects().length>0;
  const hash=value=>typeof value==='string'&&/^[a-f0-9]{64}$/.test(value);
  const livePage=value=>['live_state','loaded','live_members','live_method_body'].includes(value);
  async function request(args){const r=await send('nativeApi',args);if(r.code!=='OBSERVED'||r.values?.errorCode)throw new Error(r.values?.errorCode||r.code);return JSON.parse(r.values.state);}
  function resetSelection(next=''){selection.reset(next);overlayLabels.clear();selectionChanged();}
  function acceptSnapshot(next){if(!hash(next)||next===snapshot)return;snapshot=next;module=className=methodName=methodDescriptor=search='';offset=memberOffset=methodOffset=textOffset=0;resetSelection(snapshot);}

  async function state(){if(!ready||polling||!root?.isConnected)return;const generation=epoch;polling=true;
    try{
      const value=await request({kind:'state'});if(generation!==epoch||!root?.isConnected)return;lastState=value;const e=value.environment;
      status.textContent=e.status+' · '+e.modules+' modules / '+e.classes+' classes · '+e.physicalSide+' · '+(e.error||'');
      if(value.refresh?.operation)status.textContent+=__uiT(" · 本人刷新 ")+value.refresh.phase+' '+(value.refresh.error||'');
      const own=value.refresh?.operation===pendingOperation?value.refresh:null;refreshing=!!pendingOperation||['QUEUED','CAPTURING'].includes(value.refresh?.phase)||e.status==='CAPTURING';
      if(pendingOperation&&own&&['READY','FAILED'].includes(own.phase)){pendingOperation='';refreshing=e.status==='CAPTURING';if(own.phase==='FAILED')return;if(own.snapshot!==e.snapshot){status.textContent+=__uiT(" · 环境快照已变化，请明确重读当前状态。");return;}}
      if(pendingOperation)return;
      if(e.status==='READY'&&hash(e.snapshot)&&e.snapshot!==snapshot){acceptSnapshot(e.snapshot);if(!livePage(page)){page='modules';serial++;querying=false;}if(visible())void query();}
    }catch(error){if(generation===epoch&&status)status.textContent=error.message;}
    finally{if(generation===epoch)polling=false;}
  }
  async function refresh(){if(!ready||refreshing||!root?.isConnected)return;const context=scope,operation=crypto.randomUUID();pendingOperation=operation;refreshing=true;status.textContent=__uiT("请求捕获 FML game layer 的原始 class 资源…");
    try{await request({kind:'refresh',operationId:operation});if(context!==scope||pendingOperation!==operation)return;await state();}
    catch(error){if(context!==scope||pendingOperation!==operation)return;status.textContent=error.message+__uiT(" · 未自动重发捕获；可只读刷新状态。");pendingOperation='';refreshing=false;}
  }
  function renderSelected(parent){
    const section=el('section',null,parent,'event-card');el('strong',__uiT("Coder Native 选择 · ")+selection.size()+' / 16',section);
    el('p',__uiT("raw class 仅发送声明元数据；live/transformed overlay 发送服务器冻结的准确 class bytes、provenance，以及明确选择的方法实现。全部选择绑定当前 snapshot、viewer、world 与 process epoch。"),section,'muted');
    for(const item of selection.items){const row=el('div',null,section,'actions');el('span','raw · '+item.module+' · '+item.class,row);button(__uiT("移除"),row,()=>{selection.remove(item.module,item.class);selectionChanged();if(body?.isConnected)void query();});}
    for(const id of selection.overlays){const row=el('div',null,section,'actions');el('span','overlay · '+(overlayLabels.get(id)||id),row);button(__uiT("移除"),row,()=>{selection.removeOverlay(id);overlayLabels.delete(id);selectionChanged();if(body?.isConnected)void query();});}
    button(__uiT("清空选择"),section,()=>{selection.clear();overlayLabels.clear();selectionChanged();if(body?.isConnected)void query();},selection.size()>0);
  }
  function evidence(result,parent){
    el('p',(result.provenance||'UNKNOWN')+' · process '+(result.processEpoch||'unknown')+' · loader '+(result.loader||'unknown'),parent,'warning');
    el('p','class SHA '+(result.classHash||'unknown')+' · raw '+(result.rawComparison||'NOT_COMPARED')+(result.rawClassHash?' / '+result.rawClassHash:''),parent,'muted');
    if(result.audit)el('p','audit '+result.audit,parent,'muted');
  }
  function overlayButton(result,parent){
    if(result.selectionId){
      const chosen=selection.hasOverlay(result.selectionId),label=(result.provenance||'live')+' · '+result.class+(result.method?'#'+result.method+' '+result.descriptor:'');
      button(chosen?__uiT("移出 Coder overlay"):__uiT("选入 Coder overlay"),parent,()=>{try{if(hash(result.snapshot)&&selection.snapshot!==result.snapshot){snapshot=result.snapshot;resetSelection(snapshot);}if(chosen){selection.removeOverlay(result.selectionId);overlayLabels.delete(result.selectionId);}else{selection.addOverlay(result.selectionId);overlayLabels.set(result.selectionId,label);}selectionChanged();void query();}catch(error){status.textContent=error.message;}},hash(result.snapshot));
    }else el('p',__uiT("当前结果只读，未签发 Coder overlay")+(result.selectionUnavailable?'：'+result.selectionUnavailable:hash(result.snapshot)?'。':__uiT("（raw snapshot 尚未 READY）。")),parent,'muted');
  }
  function textPages(result,parent){const pages=el('div',null,parent,'actions');button(__uiT("上一段长文本"),pages,()=>{textOffset=Math.max(0,textOffset-4096);void query();},textOffset>0);button(__uiT("下一段长文本"),pages,()=>{textOffset=result.nextTextOffset;void query();},result.textMore);}
  function methodButtons(result,parent,target){const methods=el('section',null,parent,'event-card');el('strong',__uiT("本页方法实现 · 按需读取"),methods);el('p',__uiT("使用准确名称与 JVM descriptor；只读取已展示 selector，不初始化目标类。"),methods,'muted');for(const method of result.methods||[])button(method.name+' '+method.descriptor,methods,()=>{methodName=method.name;methodDescriptor=method.descriptor;memberOffset=offset;offset=textOffset=0;parentMembersPage=target;page=target==='live_members'?'live_method_body':target==='transformed_members'?'transformed_method_body':'method_body';void query();});}
  function navRaw(parent){button(__uiT("返回模块"),parent,()=>{page='modules';module=className=methodName=methodDescriptor=search='';offset=textOffset=0;void query();});}
  function args(){
    const value={kind:page,offset:String(offset)};
    if(page==='modules'){value.snapshot=snapshot;return value;}
    if(['classes','members'].includes(page)){value.snapshot=snapshot;value.module=module;value.search=search;if(page==='members'){value.class=className;value.textOffset=String(textOffset);}return value;}
    if(page==='method_body')return {...value,snapshot,module,class:className,method:methodName,descriptor:methodDescriptor,textOffset:String(textOffset)};
    if(page==='source'){value.snapshot=snapshot;value.module=module;value.class=className;if(methodName){value.method=methodName;value.descriptor=methodDescriptor;}value.textOffset=String(textOffset);return value;}
    if(page==='live_state')return {kind:page};
    if(page==='loaded')return {kind:page,search,offset:String(offset)};
    if(page==='live_members')return {kind:page,classToken,search,offset:String(offset),textOffset:String(textOffset)};
    if(page==='live_method_body')return {kind:page,classToken,method:methodName,descriptor:methodDescriptor,offset:String(offset),textOffset:String(textOffset)};
    if(page==='transformed_members')return {kind:page,snapshot,module,class:className,search,offset:String(offset),textOffset:String(textOffset)};
    return {kind:page,snapshot,module,class:className,method:methodName,descriptor:methodDescriptor,offset:String(offset),textOffset:String(textOffset)};
  }

  async function query(){
    if(!ready||!body?.isConnected)return;if(!livePage(page)&&!snapshot){body.replaceChildren();el('p',__uiT("raw snapshot 尚未 READY；可刷新 raw 快照，或先检查 live/FML capability。"),body,'muted');return;}
    const generation=epoch,ticket=++serial,current=body;querying=true;current.replaceChildren();el('p',livePage(page)?__uiT("读取当前 JVM/FML 观测，不执行目标方法…"):__uiT("读取固定 raw snapshot，不初始化或执行目标类…"),current,'muted');
    try{
      const result=await request(args());if(generation!==epoch||ticket!==serial||!current.isConnected)return;if(hash(result.snapshot)&&result.snapshot!==snapshot)acceptSnapshot(result.snapshot);current.replaceChildren();el('p',hash(result.snapshot)?'snapshot '+result.snapshot:__uiT("raw snapshot 未就绪"),current,'muted');renderSelected(current);
      if(page==='modules'){
        el('p',result.namespace+' / '+result.physicalSide+' · '+result.mappingStatus,current,'warning');
        for(const m of result.modules){const card=el('article',null,current,'event-card');el('strong',m.name+' · '+m.version,card);el('p',m.classes+' classes · '+m.size+' bytes · '+m.sha256+(m.sourceSha256?' · sources '+m.sourceFiles+' files / '+m.sourceKind:' · no attached sources'),card,'muted');button(__uiT("浏览类与符号"),card,()=>{module=m.name;className=search='';offset=textOffset=0;page='classes';void query();});}
      }else if(page==='classes'){
        navRaw(current);el('p','module '+module,current,'muted');const controls=el('div',null,current,'event-toolbar'),input=el('input',null,controls);input.type='search';input.value=search;input.maxLength=128;input.setAttribute('aria-label',__uiT("类名包含文本"));button(__uiT("按名称查找"),controls,()=>{search=input.value;offset=textOffset=0;void query();});for(const name of result.classes)button(name,current,()=>{className=name;methodName=methodDescriptor=search='';offset=textOffset=0;page='members';void query();});
      }else if(page==='members'){
        navRaw(current);button(__uiT("返回类列表"),current,()=>{page='classes';className=methodName=methodDescriptor=search='';offset=memberOffset=textOffset=0;void query();});el('p','module '+module+' · '+className,current,'muted');el('p','class SHA '+result.classHash+' · '+result.mappingStatus,current,'muted');const chosen=selection.has(module,className);button(chosen?__uiT("移出 Coder raw class"):__uiT("选入 Coder raw class"),current,()=>{try{if(chosen)selection.remove(module,className);else selection.add(module,className);selectionChanged();void query();}catch(error){status.textContent=error.message;}});button(__uiT("读取 FML transformed 成员"),current,()=>{parentMembersPage='members';parentOffset=offset;offset=textOffset=0;page='transformed_members';void query();});if(result.sourceAvailable)button(__uiT("读取附加类源码"),current,()=>{methodName=methodDescriptor='';memberOffset=offset;offset=textOffset=0;page='source';void query();});methodButtons(result,current,'members');el('pre',result.text,current,'event-json');textPages(result,current);
      }else if(page==='method_body'){
        button(__uiT("返回 raw 成员"),current,()=>{page='members';methodName=methodDescriptor='';offset=memberOffset;textOffset=0;void query();});el('p',className+' · '+methodName+' '+methodDescriptor,current,'muted');el('p',result.implementationKind+' · '+result.totalInstructions+' instructions · '+result.mappingStatus,current,'warning');button(__uiT("读取此 selector 的 FML transformed 实现"),current,()=>{parentMembersPage='method_body';parentOffset=offset;offset=textOffset=0;page='transformed_method_body';void query();});if(result.sourceAvailable)button(__uiT("读取该方法附加源码"),current,()=>{methodOffset=offset;offset=textOffset=0;page='source';void query();});el('pre',result.text,current,'event-json');textPages(result,current);
      }else if(page==='source'){
        button(methodName?__uiT("返回方法实现"):__uiT("返回成员"),current,()=>{page=methodName?'method_body':'members';offset=methodName?methodOffset:memberOffset;textOffset=0;void query();});el('p',result.sourcePath+' · '+result.mappingConfidence+' · runtime '+result.class+(result.method?'#'+result.method+result.descriptor:''),current,'warning');el('p','source '+result.sourceHash+' · archive '+result.sourceArchiveHash+' · lines '+result.pageStartLine+'-'+result.pageEndLine,current,'muted');el('pre',result.text,current,'event-json');textPages(result,current);
      }else if(page==='live_state'){
        const value=result.state||{};el('p','Instrumentation '+(value.instrumentationAvailable?'available':'unavailable')+' · retransform '+(value.retransformSupported?'yes':'no')+' · FML loader '+(value.transformingLoaderAvailable?'yes':'no'),current,'warning');el('p','process '+(value.processEpoch||'unknown')+' · transform access '+(value.transformAccessAvailable?'available':'unavailable')+' · '+(value.error||''),current,'muted');button(__uiT("搜索 JVM 已加载类"),current,()=>{page='loaded';search='';offset=0;void query();});button(__uiT("返回 raw 模块"),current,()=>{page='modules';offset=0;void query();},!!snapshot);
      }else if(page==='loaded'){
        button(__uiT("返回 live capability"),current,()=>{page='live_state';search='';offset=0;void query();});const controls=el('div',null,current,'event-toolbar'),input=el('input',null,controls);input.type='search';input.value=search;input.maxLength=128;input.setAttribute('aria-label',__uiT("已加载类名包含文本"));button(__uiT("查找已加载类"),controls,()=>{search=input.value;offset=0;void query();});el('p','process '+result.processEpoch+' · '+result.total+' matches',current,'muted');for(const item of result.classes||[]){const card=el('article',null,current,'event-card');el('strong',item.className,card);el('p',(item.moduleName||'unnamed')+' · '+item.loaderKind+' · '+(item.namedModule?'named':'unnamed')+' · code source '+(item.codeSourceHash||'unknown'),card,'muted');button(item.modifiable?__uiT("读取 JVM live 成员"):__uiT("该类不可 retransform"),card,()=>{classToken=item.token;className=item.className;module=item.moduleName||'';search='';offset=textOffset=0;page='live_members';void query();},item.modifiable);}
      }else if(page==='live_members'||page==='transformed_members'){
        button(page==='live_members'?__uiT("返回已加载类"):__uiT("返回 raw 成员"),current,()=>{const transformed=page==='transformed_members';page=transformed?'members':'loaded';search='';offset=transformed?parentOffset:0;textOffset=0;void query();});el('p',result.class,current,'muted');evidence(result,current);overlayButton(result,current);const controls=el('div',null,current,'event-toolbar'),input=el('input',null,controls);input.type='search';input.value=search;input.maxLength=128;input.setAttribute('aria-label',__uiT("live 成员名包含文本"));button(__uiT("按名称查找"),controls,()=>{search=input.value;offset=textOffset=0;void query();});methodButtons(result,current,page);el('pre',result.text,current,'event-json');textPages(result,current);
      }else{
        const live=page==='live_method_body';button(live?__uiT("返回 JVM live 成员"):parentMembersPage==='method_body'?__uiT("返回 raw 方法实现"):__uiT("返回 FML transformed 成员"),current,()=>{page=live?'live_members':parentMembersPage==='method_body'?'method_body':'transformed_members';offset=parentMembersPage==='method_body'?parentOffset:memberOffset;textOffset=0;void query();});el('p',result.class+' · '+result.method+' '+result.descriptor,current,'muted');evidence(result,current);el('p',result.implementationKind+' · '+result.totalInstructions+' instructions',current,'warning');overlayButton(result,current);el('pre',result.text,current,'event-json');textPages(result,current);
      }
      const pages=el('div',null,current,'actions'),pageSize=page==='source'?128:['method_body','live_method_body','transformed_method_body'].includes(page)?64:16;button(__uiT("上一组"),pages,()=>{offset=Math.max(0,offset-pageSize);textOffset=0;void query();},offset>0);button(__uiT("下一组"),pages,()=>{offset=result.nextOffset;textOffset=0;void query();},result.more);
    }catch(error){if(generation===epoch&&ticket===serial&&current.isConnected){current.replaceChildren();el('p',error.message,current,'error');button(__uiT("重新观察当前环境"),current,()=>void state());button(__uiT("重读当前页"),current,()=>void query());}}
    finally{if(generation===epoch&&ticket===serial)querying=false;}
  }
  function open(){root=windowFor('runtime-native-api',__uiT("Native API / 编译来源"));epoch++;serial++;polling=querying=false;root.replaceChildren();el('h3',__uiT("Native 模块、JVM live 与 FML transformed 符号"),root);status=el('p',__uiT("读取捕获状态…"),root,'muted');el('p',__uiT("raw snapshot、FML predefine transformed bytes 与 JVM retransform live definition 会明确区分。live/transformed 结果只有在服务器签发 selection ID 后才能选入 Coder；不可用时保持只读，绝不以 raw 数据冒充。"),root,'warning');const actions=el('div',null,root,'actions');button(__uiT("刷新 raw Native 快照"),actions,()=>void refresh());button(__uiT("只读刷新 raw 状态"),actions,()=>void state());button(__uiT("检查 live / FML capability"),actions,()=>{page='live_state';offset=textOffset=0;void query();});body=el('section',null,root);if(snapshot)void query();else el('p',__uiT("尚无 raw snapshot；可刷新快照或检查 live capability。"),body,'muted');if(ready)void state();}
  function session(value){const key=JSON.stringify([value?.sessionId,value?.serverInstanceId,value?.binding?.worldId,value?.binding?.viewerPlayerId]);if(key===scope&&ready===!!value)return;scope=key;ready=!!value;epoch++;serial++;polling=querying=refreshing=false;pendingOperation=snapshot=module=className=classToken=methodName=methodDescriptor=search='';resetSelection('');offset=memberOffset=methodOffset=parentOffset=textOffset=0;page='modules';lastState=null;root?.replaceChildren();if(root?.isConnected)el('p',__uiT("上下文已变化，请重新打开 Native API。"),root,'muted');}
  setInterval(()=>{if(visible()&&!polling&&(refreshing||lastState?.environment?.status==='CAPTURING'))void state();},2000);
  return {open,session,selection:()=>selection.wire(),onSelectionChanged:listener=>{selectionListener=typeof listener==='function'?listener:()=>{};},reset:()=>session(null)};
}
