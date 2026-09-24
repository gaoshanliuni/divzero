import {t as __uiT,tf as __uiF} from './i18n.mjs';
import { GenerationDraft, BindingDrafts, GenerationRepairDrafts } from './generation-state.mjs';
import { UiPatchDraft,uiPatchRebuildRequest } from './ui-patch-state.mjs';
import { createWorldBoardCards } from './world-board-cards.mjs';
import { createWorldContentCards } from './world-content-cards.mjs';
import { createWorldPatchCards } from './world-patch-cards.mjs';
export function worldUiEditable(job,head){return job?.state==='PUBLISHED'&&job.purpose==='WORLD_CONTENT'&&head?.uiAvailable===true;}
export function createGenerationCards({ windowFor, send, report, persist, openRestore, openDataPack, openResourcePack, openClientScript }) {
  let contextEpoch=0,contextKey='',ownerWorldKey='',focused=null,focusSerial=0;
  const ownedWindows=new Set(),originalWindowFor=windowFor,originalSend=send;
  windowFor=(...args)=>{for(const prior of ownedWindows)if(!prior.isConnected)ownedWindows.delete(prior);const content=originalWindowFor(...args);ownedWindows.add(content);return content;};
  send=async(...args)=>{const epoch=contextEpoch;const value=await originalSend(...args);if(epoch!==contextEpoch)throw new Error('GENERATION_CONTEXT_CHANGED');return value;};
  let worldBoards=createWorldBoardCards({send,report});
  let worldContents=createWorldContentCards({windowFor,send,report,openRestore});
  let worldPatches=createWorldPatchCards({windowFor,send,report,persist});let worldPatchJobs=[];
  const containerDrafts=new Map();
  const rebuildDrafts=new Map();
  function agentContainer(job,revision){
    const content=windowFor('runtime-container-agent',__uiT("Agent 自身容器委派"));content.replaceChildren();
    node('p',__uiT("由选定 Agent 的真实身体、背包、原生距离与规则执行；你的客户端仅承载页面，不借用你的库存或 OP。"),content).className='muted';
    const draft=containerDrafts.get(job.packageId)||{agentId:job.agentId,goal:'',group:'player',itemId:'minecraft:cobblestone',name:'',count:'12'};containerDrafts.set(job.packageId,draft);
    const fields={};
    for(const [key,label] of [['agentId',__uiT("执行 Agent")],['goal',__uiT("任务")],['group',__uiT("结果范围")],['itemId',__uiT("物品 ID")],['name',__uiT("精确物品显示名（可留空）")],['count',__uiT("期望总数")]]){
      node('label',label,content);const input=node(key==='agentId'||key==='group'?'select':key==='goal'?'textarea':'input',null,content);input.dataset.containerAgentField=key;fields[key]=input;
      if(key==='agentId')for(const a of agents)node('option',a.name,input).value=a.id;
      if(key==='group')for(const [value,title] of [['player',__uiT("Agent 背包")],['container',__uiT("目标容器")]])node('option',title,input).value=value;
      input.value=draft[key];input.oninput=input.onchange=()=>draft[key]=input.value;
    }
    const label=node('label',null,content),consent=node('input',null,label);consent.type='checkbox';consent.dataset.containerAgentConsent='true';label.append(document.createTextNode(__uiT("明确允许本次 Agent 容器任务；期望条件不构成任意世界权限。")));
    const start=node('button',__uiT("启动 Agent 自身容器任务"),content);start.dataset.containerAgentStart=job.packageId;
    start.onclick=async()=>{start.disabled=true;try{if(!consent.checked||!draft.goal.trim())throw new Error('CONTAINER_CONSENT_AND_GOAL_REQUIRED');const count=Number(draft.count);if(!Number.isInteger(count)||count<1||count>8192)throw new Error('CONTAINER_EXPECTED_COUNT');
      const expected=JSON.stringify({group:draft.group,slot:-1,itemId:draft.itemId,name:draft.name,count,carriedCount:0});const r=await send('packageAction',{action:'containerAgent',packageId:job.packageId,packageRevision:revision,agentId:draft.agentId,goal:draft.goal,expected,confirmed:true});if(r.code!=='ACCEPTED')throw new Error(r.values?.errorCode||r.code);
      node('p',__uiT("已提交实际页面加载，任务必须经过 Native Menu 验证后才算完成。"),content);
    }catch(e){report(e);}finally{start.disabled=false;}};
  }
  let draft = new GenerationDraft();
  let repairDrafts=new GenerationRepairDrafts();let repairForm=null;
  let taskDraft = new GenerationDraft();
  let agents = [], jobs = [], sources = [], views = [], patchJobs = []; const heads = new Map(), patchTargets = new Map(); let patchDraft = new UiPatchDraft();
  let paging={page:0,pages:1,count:0};
  let bindings = new BindingDrafts();
  const node = (tag, text, parent) => { const n = document.createElement(tag); if (text != null) n.textContent = text; parent?.append(n); return n; };
  function jobFor(operation){const live=jobs.find(j=>j.operationId===operation),historic=focused?.category==='GENERATION'&&focused.job.operationId===operation?focused.job:null;return live&&(!historic||live.jobRevision>=historic.jobRevision)?live:historic||live;}
  function headFor(id){const live=heads.get(id),historical=focused?.head?.packageId===id?focused.head:null;return live&&(!historical||live.revision>=historical.revision)?live:historical||live;}
  async function openHistorical(category,operation){const epoch=contextEpoch,serial=++focusSerial;try{const r=await send('packageCatalog',{kind:'operation',category,operation});if(epoch!==contextEpoch||serial!==focusSerial)return;if(r.code!=='OBSERVED'||r.values?.errorCode)throw new Error(r.values?.errorCode||r.code);focused=JSON.parse(r.values.state);open();renderJobs();}catch(error){if(epoch===contextEpoch)report(error);}}
  function refreshRepair(){if(!repairForm)return;const current=jobFor(repairForm.source);const matched=repairDrafts.matches(current);
    if(repairForm.taskId&&repairForm.receipt){const result=jobs.find(j=>j.taskId===repairForm.taskId);if(result)repairForm.receipt.textContent=__uiT("修复 Task ")+result.taskId+" · "+result.state+" · "+(result.errorCode||__uiT("仅发布未启用；请审查候选内容。"));}
    repairForm.start.disabled=repairForm.pending||!matched||!repairForm.consent.checked||!repairForm.prompt.value.trim();
    repairForm.notice.textContent=matched?__uiT("这是新的模型请求，可能再次计费；不会重放原 operation，也不会批准原生代码。"):__uiT("原失败记录已变化或当前无权修复；未派发新请求。");
  }
  function openRepair(job){try{
    const value=repairDrafts.begin(job),content=windowFor('runtime-generation-repair',__uiT("修复失败生成 · 新请求"));content.replaceChildren();
    node('p',__uiF("原操作 {0} · {1} · 记录 r{2}",job.operationId,job.purpose,job.jobRevision),content).className='muted';
    node('p',__uiF("原始输出 SHA-256: {0}",job.rawOutputSha256),content).className='repair-source-hash';
    node('p',__uiT("原失败产物保留不变；修复将创建新的 Task 和 Package，严格校验后仅签名入库。未知结果不自动重试。"),content).className='muted';
    const label=node('label',__uiT("本次明确修复要求"),content);label.htmlFor='generation-repair-prompt';
    const prompt=node('textarea',null,content);prompt.id='generation-repair-prompt';prompt.maxLength=8192;prompt.value=value.prompt;prompt.placeholder=__uiT("指出要修复的行为与应保留的目标；不要填写 API Key");
    const consentLabel=node('label',null,content),consent=node('input',null,consentLabel);consent.type='checkbox';consent.id='generation-repair-consent';consentLabel.append(document.createTextNode(__uiT("我明确提交这一次新的模型修复请求，并知悉可能再次计费。")));
    const notice=node('p',null,content);notice.className='muted';notice.setAttribute('role','status');
    const start=node('button',__uiT("提交本次修复请求"),content);start.id='generation-repair-submit';
    const form={source:job.operationId,prompt,consent,notice,start,pending:false};repairForm=form;
    prompt.oninput=()=>{repairDrafts.edit(job.operationId,prompt.value);consent.checked=false;persist();refreshRepair();};consent.onchange=refreshRepair;
    start.onclick=async()=>{form.pending=true;refreshRepair();try{
      const current=jobFor(job.operationId);const request=repairDrafts.request(current,consent.checked,()=>crypto.randomUUID());persist();
      const receipt=await send('packageAction',{action:'repairGeneration',...request});if(repairForm!==form||!start.isConnected)return;if(!['APPLIED','ACCEPTED'].includes(receipt.code))throw new Error(receipt.values?.errorCode||receipt.code);
      const status=node('p',__uiF("修复 Task {0} · {1}；请查看生成列表终态，受理不等于完成。",receipt.values.taskId,receipt.values.state),content);status.id='generation-repair-receipt';status.dataset.taskId=receipt.values.taskId;form.taskId=receipt.values.taskId;form.receipt=status;
    }catch(error){if(repairForm===form&&start.isConnected)report(error);}finally{form.pending=false;consent.checked=false;if(repairForm===form)refreshRepair();}};refreshRepair();persist();
  }catch(error){report(error);}}
  function syncDraft() {
    const agent = document.querySelector('#generation-agent'), prompt = document.querySelector('#generation-prompt');
    if (agent && prompt) draft.edit(agent.value, prompt.value,document.querySelector('#generation-purpose')?.value??'UI_PACKAGE');
  }
  function fillAgents() {
    const select = document.querySelector('#generation-agent'); if (!select) return;
    const previous = select.value || draft.agentId; select.replaceChildren();
    for (const agent of agents) { const option = node('option', agent.name, select); option.value = agent.id; }
    if (agents.some(a => a.id === previous)) select.value = previous;
  }
  function renderJobs() {
    const list = document.querySelector('#generation-jobs'); if (!list) return;
    list.replaceChildren();
    const navigation=node('div',null,list);navigation.className='actions';
    const previous=node('button',__uiT("上一页绑定视图"),navigation);previous.disabled=paging.page<=0;previous.onclick=()=>send('scoreViewPage',{page:paging.page-1}).catch(report);
    node('span',__uiF("绑定视图 {0}/{1} · {2} 个",paging.page+1,paging.pages,paging.count),navigation);
    const next=node('button',__uiT("下一页绑定视图"),navigation);next.disabled=paging.page+1>=paging.pages;next.onclick=()=>send('scoreViewPage',{page:paging.page+1}).catch(report);
    if(focused){node('p',__uiT("当前定位历史操作快照；不自动重放。Native 仍会复核当前版本、权限和确认。"),list).className='muted';node('button',__uiT("刷新此操作"),list).onclick=()=>openHistorical(focused.category,focused.job.operationId);node('button',__uiT("返回近期操作"),list).onclick=()=>{focused=null;renderJobs();};}
    for (const job of focused?(focused.category==='GENERATION'?[jobFor(focused.job.operationId)]:[]):jobs) {
      const currentRevision=headFor(job.packageId)?.revision??job.packageRevision;
      const card = node('article', null, list); card.className = 'generation-job'; card.dataset.operationId = job.operationId; card.dataset.agentId = job.agentId; card.dataset.packageId=job.packageId;card.dataset.packageRevision=currentRevision;
      node('strong', `${job.state} · ${job.packageId.slice(0, 8)}`, card);
      node('p', `Task: ${job.taskId}`, card).className = 'muted';
      if (job.errorCode) node('p', job.errorCode, card);
      if(job.repairOf)node('p',__uiF("修复来源 operation: {0}",job.repairOf),card).className='muted';
      if(job.state==='FAILED'&&job.repairable===true){const repair=node('button',__uiT("修复保留的失败产物…"),card);repair.dataset.repairGeneration=job.operationId;repair.onclick=()=>openRepair(job);}
      if (job.state === 'GENERATING') node('button', __uiT("取消生成"), card).onclick = async event => {
        const button = event.currentTarget; button.disabled = true;
        try { const r = await send('packageAction', { action: 'cancel', operationId: job.operationId }); if (!['APPLIED', 'ACCEPTED'].includes(r.code)) throw new Error(r.values?.errorCode || r.code); }
        catch (e) { report(e); } finally { button.disabled = false; }
      };
      if(job.state==='PUBLISHED'&&(headFor(job.packageId)?.clientScriptAvailable||headFor(job.packageId)?.clientJavaAvailable)&&openClientScript)node('button',__uiT("本机 CLIENT 原生代码下载 / 执行"),card).onclick=()=>openClientScript(job.packageId);
      if(job.state==='PUBLISHED'&&headFor(job.packageId)?.resourcePackAvailable&&openResourcePack){node('button',__uiT("本机资源包下载 / 启用"),card).onclick=()=>openResourcePack(job.packageId);continue;}
      if(job.state==='PUBLISHED'&&headFor(job.packageId)?.dataPackAvailable&&openDataPack){node('button',__uiT("数据包安装 / 停用"),card).onclick=()=>openDataPack(job.packageId);continue;}
      if(job.state==='PUBLISHED'&&headFor(job.packageId)?.bootAvailable){worldPatches.render(card,{...job,activationMode:'BOOT_EXTENSION'},currentRevision);continue;}
      if(job.state==='PUBLISHED'&&job.purpose==='WORLD_CONTENT'){worldContents.render(card,job,currentRevision);worldPatches.render(card,job,currentRevision);if(worldUiEditable(job,headFor(job.packageId))){const edit=node('button',__uiT("仅修改包内网页"),card);edit.dataset.patchPackage=job.packageId;edit.title=__uiT("仅编辑 UI 资源；新版本会撤销旧界面和绑定旧 hash 的脚本，不自动执行新版本");edit.onclick=()=>openPatch(job,currentRevision);}continue;}
      if (job.state === 'PUBLISHED') node('button', __uiT("打开独立预览"), card).onclick = async event => {
        const button = event.currentTarget; button.disabled = true;
        try { const r = await send('packageAction', { action: 'preview', packageId: job.packageId, packageRevision: currentRevision });
          if (r.code !== 'ACCEPTED') throw new Error(r.values?.errorCode || r.code);
          node('p', __uiT("已校验并提交页面加载；预览不产生世界写入。"), card);
          const detail = node('details', null, card); node('summary', __uiT("下载与校验回执"), detail);
          node('pre', JSON.stringify(r.values, null, 2), detail).className = 'preview-receipt';
        } catch (e) { report(e); } finally { button.disabled = false; }
      };
      if (job.state === 'PUBLISHED') {
        if(headFor(job.packageId)?.containerAvailable){
          const agentOpen=node('button',__uiT("让 Agent 操作自身容器"),card);agentOpen.dataset.containerAgentPackage=job.packageId;agentOpen.onclick=()=>agentContainer(job,currentRevision);
          const label=node('label',null,card);const consent=node('input',null,label);consent.type='checkbox';consent.dataset.containerConsent=job.packageId;label.append(document.createTextNode(__uiT("允许此包通过原生交互打开准星容器，并读写我的槽位；隐藏/关闭时归还光标物品")));
          const open=node('button',__uiT("打开准星容器网页"),card);open.dataset.containerOpen=job.packageId;
          open.onclick=async()=>{open.disabled=true;try{if(!consent.checked)throw new Error('CONTAINER_CONSENT_REQUIRED');const r=await send('packageAction',{action:'containerOpen',packageId:job.packageId,packageRevision:currentRevision,confirmed:true});if(r.code!=='ACCEPTED')throw new Error(r.values?.errorCode||r.code);}catch(e){report(e);}finally{open.disabled=false;}};
        }
        node('p',__uiT("生成版本 r")+job.packageRevision+__uiT(" · 当前 r")+currentRevision,card).className='muted';
        const edit=node('button',__uiT("修改包网页"),card);edit.dataset.patchPackage=job.packageId;edit.onclick=()=>openPatch(job,currentRevision);
        for (const view of views.filter(v => v.packageId === job.packageId)) {
          worldBoards.render(card,job,currentRevision,view);
          if(headFor(job.packageId)?.hudAvailable===true){
            const hud=node('button',__uiF("打开只读 HUD {0}",view.id.slice(0,8)),card);hud.dataset.hudViewId=view.id;
            hud.title=__uiT("真实计分源只读展示；关闭对话后继续刷新，不获得网页写权限");
            hud.onclick=async()=>{hud.disabled=true;try{
              const r=await send('packageAction',{action:'hud',packageId:job.packageId,packageRevision:currentRevision,targetViewId:view.id});
              if(r.code!=='ACCEPTED')throw new Error(r.values?.errorCode||r.code);
            }catch(e){report(e);}finally{hud.disabled=false;}};
          }
          const open = node('button', __uiF("打开绑定视图 {0}",view.id.slice(0, 8)), card);
          open.dataset.sourceId=view.sourceId;open.dataset.targetViewId=view.id;
          open.onclick = async () => {
            open.disabled = true;
            try { const r=await send('packageAction',{action:'open',packageId:job.packageId,packageRevision:currentRevision,targetViewId:view.id});
              if(r.code!=='ACCEPTED')throw new Error(r.values?.errorCode||r.code);
            } catch(e){report(e);} finally{open.disabled=false;}
          };
          const restore=node('button',__uiF("恢复本地草稿 {0}",view.id.slice(0,8)),card);
          restore.dataset.restoreViewId=view.id;
          restore.onclick=async()=>{restore.disabled=true;try{const r=await send('takeoverUi',{action:'resume',packageId:job.packageId,packageRevision:currentRevision,targetViewId:view.id});if(r.code!=='ACCEPTED')throw new Error(r.values?.errorCode||r.code);}catch(e){report(e);}finally{restore.disabled=false;}};
        }
        if (sources.length) {
          const label=node('label',__uiT("绑定已有计分目标（只创建视图，不修改分数）"),card);
          const source=node('select',null,label);source.setAttribute('aria-label',__uiT("已有计分目标"));
          node('option',__uiT("请选择已有目标"),source).value='';
          for(const s of sources){const option=node('option',s.reference,source);option.value=s.id;}
          source.value=sources.some(s=>s.id===bindings.source(job.packageId))?bindings.source(job.packageId):'';
          source.onchange=()=>{bindings.select(job.packageId,source.value);persist();};
          const bind=node('button',__uiT("创建独立绑定视图"),card);
          bind.onclick=async()=>{
            bind.disabled=true;
            try{
              if(!sources.some(s=>s.id===source.value))throw new Error('BINDING_SOURCE_REQUIRED');
              bindings.select(job.packageId,source.value);
              const binding=bindings.request(job.packageId,sources.find(s=>s.id===source.value).reference,()=>crypto.randomUUID());persist();
              const r=await send('packageAction',{action:'bind',packageId:job.packageId,packageRevision:currentRevision,
                ...binding});
              if(r.code!=='APPLIED')throw new Error(r.values?.errorCode||r.code);
            }catch(e){report(e);}finally{bind.disabled=false;}
          };
        }
      }
    }
    renderPatches(list,focused?(focused.category==="UI_PATCH"?[focused.job]:[]):patchJobs);
    worldPatches.history(list,focused?(focused.category==="WORLD_PATCH"?[focused.job]:[]):worldPatchJobs);
  }
  function openPatch(job,revision){
    patchDraft.choose(job.packageId,revision,job.agentId);
    const content=windowFor('runtime-ui-patch',__uiT("修改现有包网页"));content.replaceChildren();
    node('p',__uiF("基于 r{0} 的独立候选，不立即覆盖当前页面；取消不重放模型。",revision),content).className='muted';
    const label=node('label',__uiT("修改要求"),content);label.htmlFor='ui-patch-prompt';
    const input=node('textarea',null,content);input.id='ui-patch-prompt';input.maxLength=8192;input.value=patchDraft.prompt;
    input.oninput=()=>{patchDraft.edit(input.value);persist();};
    const submit=node('button',__uiT("生成改版候选"),content);submit.id='ui-patch-submit';
    submit.onclick=async()=>{submit.disabled=true;try{patchDraft.edit(input.value);const args=patchDraft.request(()=>crypto.randomUUID());persist();const r=await send('packageAction',{action:'patchSubmit',...args});if(r.code!=='ACCEPTED')throw new Error(r.values?.errorCode||r.code);node('p',__uiF("服务端 {0} · {1}",r.values.state,r.values.taskId),content);}catch(e){report(e);}finally{submit.disabled=false;}};
  }
  function renderPatches(parent,items=patchJobs){
    if(!items.length)return;node('h3',__uiT("网页改版历史"),parent);
    for(const job of items){
      const card=node('article',null,parent);card.className='generation-job patch-job';card.dataset.operationId=job.operationId;card.dataset.packageId=job.packageId;card.dataset.state=job.state;
      node('strong',`${job.state} · r${job.baseRevision} → r${job.headRevision||job.candidateRevision}`,card);node('p',job.prompt,card);
      if(job.errorCode)node('p',job.errorCode,card).className='muted';
      if(job.rebuilt)node('p',__uiT("已从本地接收输出重建 · 未再调用模型 · 原 FAILED 记录保留"),card).className='muted';
      if(job.state==='FAILED'&&job.rebuildAllowed){
        const draft=rebuildDrafts.get(job.operationId)||{hash:job.rawOutputSha256||'',confirmed:false,busy:false,status:''};rebuildDrafts.set(job.operationId,draft);
        const details=node('details',null,card);details.open=draft.open===true;details.ontoggle=()=>draft.open=details.open;node('summary',__uiT("Operator：从已接收输出重建（不调用模型）"),details);
        node('p',__uiT("仅当确认此内容库 SHA 属于本失败任务时使用。原始归属缺失会明确记录为 Operator 人工确认；不会覆盖原失败，也不会自动应用候选。"),details).className='muted';
        const input=node('input',null,details);input.setAttribute('aria-label',__uiT("已接收输出 SHA-256"));input.maxLength=64;input.value=draft.hash;input.dataset.patchRebuildSha=job.operationId;
        const label=node('label',null,details),confirm=node('input',null,label);confirm.type='checkbox';confirm.checked=draft.confirmed;confirm.dataset.patchRebuildConsent=job.operationId;label.append(document.createTextNode(__uiT("我已核对该 SHA 的来源，明确允许只读解析并重建此 UI 候选")));
        input.oninput=()=>{draft.hash=input.value.trim();draft.confirmed=false;confirm.checked=false;};confirm.onchange=()=>draft.confirmed=confirm.checked;
        const status=node('p',draft.status,details);status.setAttribute('role','status');
        const rebuild=node('button',__uiT("重建已接收的 UI 输出"),details);rebuild.dataset.patchRebuild=job.operationId;rebuild.disabled=draft.busy;
        rebuild.onclick=async()=>{rebuild.disabled=true;draft.busy=true;try{const request=uiPatchRebuildRequest(job,draft.hash,draft.confirmed);const r=await send('packageAction',request);if(r.code!=='APPLIED')throw new Error(r.values?.errorCode||r.code);draft.status=__uiF("{0} · 本地重建，无 Provider 调用",r.values.state);status.textContent=draft.status;}catch(e){draft.status=e.message;status.textContent=e.message;report(e);}finally{draft.busy=false;rebuild.disabled=false;}};
      }
      const command=(label,action)=>{const button=node('button',label,card);button.dataset.patchAction=action;button.onclick=async()=>{button.disabled=true;try{const r=await send('packageAction',{action,operationId:job.operationId,packageId:job.packageId,packageRevision:job.state==='APPLIED'?job.headRevision:job.baseRevision});if(!['APPLIED','ACCEPTED'].includes(r.code))throw new Error(r.values?.errorCode||r.code);}catch(e){report(e);}finally{button.disabled=false;}};};
      if(job.state==='READY'){
        const available=views.filter(v=>v.packageId===job.packageId);const target=node('select',null,card);target.setAttribute('aria-label',__uiT("候选预览绑定视图"));node('option',__uiT("不绑定业务（纯预览）"),target).value='';
        for(const v of available)node('option',v.id.slice(0,8),target).value=v.id;
        target.value=patchTargets.get(job.operationId)??available[0]?.id??'';target.onchange=()=>patchTargets.set(job.operationId,target.value);
        const preview=node('button',__uiT("只读预览候选"),card);preview.dataset.patchAction='patchPreview';preview.onclick=async()=>{preview.disabled=true;try{const r=await send('packageAction',{action:'patchPreview',operationId:job.operationId,packageId:job.packageId,packageRevision:job.candidateRevision,targetViewId:target.value});if(r.code!=='ACCEPTED')throw new Error(r.values?.errorCode||r.code);}catch(e){report(e);}finally{preview.disabled=false;}};
        command(__uiT("应用并切换窗口（草稿只读恢复）"),'patchApplySwap');
        command(__uiT("仅应用包（旧页面保留草稿）"),'patchApply');
      }
      if(['PENDING','READY'].includes(job.state))command(__uiT("取消改版"),'patchCancel');
      if(job.state==='APPLIED'){command(__uiT("回退并切换窗口（不回退业务数据）"),'patchUndoSwap');command(__uiT("仅回退包"),'patchRollback');}
    }
  }
  function open() {
    const content = windowFor('runtime-generation', __uiT("内容与网页生成任务"));
    if (content.querySelector('#generation-jobs')) { renderJobs(); return; } content.replaceChildren();
    const managed=node('button',__uiT("管理已有世界实例"),content);managed.id='world-instances-open';managed.onclick=()=>worldContents.manage();
    node('p', __uiT("使用服务器配置的 Provider 生成独立 RuntimePackage，消耗模型额度。产物签名入库但不启用玩法。"), content).className = 'muted';
    const label = node('label', __uiT("执行 Agent"), content); label.htmlFor = 'generation-agent';
    const agent = node('select', null, content); agent.id = 'generation-agent'; fillAgents();
    node('label',__uiT("生成范围"),content);const purpose=node('select',null,content);purpose.id='generation-purpose';node('option',__uiT("独立网页包"),purpose).value='UI_PACKAGE';node('option',__uiT("独立世界内容（原生代码需另行启用）"),purpose).value='WORLD_CONTENT';purpose.value=draft.purpose;
    const promptLabel = node('label', __uiT("内容或网页需求"), content); promptLabel.htmlFor = 'generation-prompt';
    const prompt = node('textarea', null, content); prompt.id = 'generation-prompt'; prompt.maxLength = 8192; prompt.value = draft.prompt;
    prompt.placeholder = __uiT("描述独立窗口、表单、交互和布局；不要填写 API Key");
    agent.onchange = prompt.oninput = purpose.onchange = () => { syncDraft(); persist(); };
    const start = node('button', __uiT("生成所选范围的包"), content); start.id = 'generation-submit';
    start.onclick = async () => {
      start.disabled = true;
      try {
        syncDraft(); const request = draft.request(() => crypto.randomUUID()); persist();
        const receipt = await send('packageAction', { action: 'generate', ...request });
        if (!['APPLIED', 'ACCEPTED'].includes(receipt.code)) throw new Error(receipt.values?.errorCode || receipt.code);
        node('p', __uiF("服务端任务 {0}：{1}。关闭窗口不会取消任务。",receipt.values.taskId,receipt.values.state), content);
      } catch (e) { report(e); } finally { start.disabled = false; }
    };
    const reset = node('button', __uiT("作为新任务提交"), content); reset.onclick = () => { draft.operationId = null; persist(); start.click(); };
    const plan=node('button',__uiT("由 Agent 规划网页任务"),content);plan.id='ui-task-submit';
    plan.onclick=async()=>{plan.disabled=true;try{
      if([...prompt.value].length>256)throw new Error(__uiT("网页任务入口最多 256 字；详细直接生成仍可使用原按钮。"));
      if(purpose.value!=='UI_PACKAGE')throw new Error(__uiT("世界内容请使用直接生成入口；通用 Agent 世界包工具尚未接通。"));
      taskDraft.edit(agent.value,prompt.value);const request=taskDraft.request(()=>crypto.randomUUID());persist();
      const receipt=await send('packageAction',{action:'taskStart',...request});if(receipt.code!=='ACCEPTED')throw new Error(receipt.values?.errorCode||receipt.code);
      const result=node('p',__uiF("Agent 任务 {0} · {1}；工具必须等待实际产物，改版候选仍需明确应用。",receipt.values.taskId,receipt.values.state),content);result.id='ui-task-receipt';result.dataset.taskId=receipt.values.taskId;
    }catch(error){report(error);}finally{plan.disabled=false;}};
    const newPlan=node('button',__uiT("新建 Agent 网页任务"),content);newPlan.onclick=()=>{taskDraft.operationId=null;persist();plan.click();};
    node('p', __uiT("重试保留 operationId，不重复创建；“作为新任务提交”会明确创建新任务并调用模型。取消不撤回已产生的模型费用。"), content).className = 'muted';
    node('div', null, content).id = 'generation-jobs'; renderJobs();
  }
  async function openPackageTools(packageId){
    const epoch=contextEpoch;try{
      const receipt=await send('packageCatalog',{kind:'package',packageId,headRevision:'0',headHash:''});if(epoch!==contextEpoch)return;
      if(receipt.code!=='OBSERVED'||receipt.values?.errorCode)throw new Error(receipt.values?.errorCode||receipt.code);
      const head=JSON.parse(receipt.values.state),content=windowFor('runtime-package-tools',__uiT("当前自有包操作"));content.replaceChildren();
      node('strong',head.name+' · r'+head.revision,content);node('p',__uiT("来源 ")+head.origin+__uiT("；这里只使用当前真实包，不创建伪造生成任务。Native 会再次检查版本与权限。"),content).className='muted';
      const label=node('label',__uiT("执行 Agent（权限以服务端复核为准）"),content),agent=node('select',null,label);node('option',__uiT("选择 Agent"),agent).value='';for(const a of agents)node('option',a.name,agent).value=a.id;
      const body=node('section',null,content);let selected='';
      function render(){body.replaceChildren();const descriptor={packageId:head.packageId,agentId:selected,packageRevision:head.revision};
        if(head.uiAvailable){const preview=node('button',__uiT("打开当前独立预览"),body);preview.onclick=async()=>{preview.disabled=true;try{const r=await send('packageAction',{action:'preview',packageId:head.packageId,packageRevision:head.revision});if(r.code!=='ACCEPTED')throw new Error(r.values?.errorCode||r.code);}catch(e){report(e);}finally{preview.disabled=false;}};
          const edit=node('button',__uiT("修改包内网页…"),body);edit.disabled=!selected;edit.onclick=()=>openPatch(descriptor,head.revision);}
        if(head.resourcePackAvailable&&openResourcePack)node('button',__uiT("本机资源包下载 / 启用"),body).onclick=()=>openResourcePack(packageId);
        if((head.clientScriptAvailable||head.clientJavaAvailable)&&openClientScript)node('button',__uiT("本机 CLIENT 原生代码下载 / 执行"),body).onclick=()=>openClientScript(packageId);
        if(head.dataPackAvailable&&openDataPack)node('button',__uiT("数据包安装 / 停用"),body).onclick=()=>openDataPack(packageId);
        if(head.bootAvailable){descriptor.activationMode='BOOT_EXTENSION';if(selected)worldPatches.render(body,descriptor,head.revision);else node('p',__uiT("选择 Agent 后可提出启动扩展同包改版；发布不会替换正在运行的 Mod。"),body).className='muted';}
        if(head.worldAvailable){if(selected){worldContents.render(body,descriptor,head.revision);worldPatches.render(body,descriptor,head.revision);}else node('p',__uiT("选择 Agent 后可打开原世界内容审批与改版入口。"),body).className='muted';}
        if(head.containerAvailable){const agentButton=node('button',__uiT("Agent 自身容器操作…"),body);agentButton.disabled=!selected;agentButton.onclick=()=>agentContainer(descriptor,head.revision);
          const line=node('label',null,body),consent=node('input',null,line);consent.type='checkbox';line.append(document.createTextNode(__uiT("允许当前包通过原生容器页面读写我的槽位；不是代理其它玩家的库存。")));
          const playerButton=node('button',__uiT("打开准星容器网页"),body);playerButton.onclick=async()=>{if(!consent.checked)return;playerButton.disabled=true;try{const r=await send('packageAction',{action:'containerOpen',packageId:head.packageId,packageRevision:head.revision,confirmed:true});if(r.code!=='ACCEPTED')throw new Error(r.values?.errorCode||r.code);}catch(e){report(e);}finally{consent.checked=false;playerButton.disabled=false;}};}
        for(const view of views.filter(v=>v.packageId===head.packageId)){worldBoards.render(body,descriptor,head.revision,view);
          for(const [title,action,available] of [[__uiT("打开绑定视图"),'open',head.uiAvailable],[__uiT("打开只读 HUD"),'hud',head.hudAvailable]])if(available){const b=node('button',title+' '+view.id.slice(0,8),body);b.onclick=async()=>{b.disabled=true;try{const r=await send('packageAction',{action,packageId:head.packageId,packageRevision:head.revision,targetViewId:view.id});if(r.code!=='ACCEPTED')throw new Error(r.values?.errorCode||r.code);}catch(e){report(e);}finally{b.disabled=false;}};}
        }
        if(sources.length&&head.uiAvailable){const line=node('label',__uiT("绑定已有计分目标"),body),source=node('select',null,line);node('option',__uiT("选择已有目标"),source).value='';for(const s of sources)node('option',s.reference,source).value=s.id;
          const bind=node('button',__uiT("创建独立绑定视图"),body);bind.onclick=async()=>{bind.disabled=true;try{const chosen=sources.find(s=>s.id===source.value);if(!chosen)throw new Error('BINDING_SOURCE_REQUIRED');bindings.select(head.packageId,chosen.id);const request=bindings.request(head.packageId,chosen.reference,()=>crypto.randomUUID());persist();const r=await send('packageAction',{action:'bind',packageId:head.packageId,packageRevision:head.revision,...request});if(r.code!=='APPLIED')throw new Error(r.values?.errorCode||r.code);}catch(e){report(e);}finally{bind.disabled=false;}};}
        node('p',__uiT("绑定视图使用当前分页数据；可在内容生成页翻页，再刷新此面板。不会把未返回的视图当作不存在。"),body).className='muted';
      }
      agent.onchange=()=>{selected=agent.value;render();};render();node('button',__uiT("重读包版本与当前绑定数据"),content).onclick=()=>openPackageTools(packageId);
    }catch(error){if(epoch===contextEpoch)report(error);}
  }
  function session(value){
    const key=JSON.stringify([value?.binding?.worldId,value?.binding?.viewerPlayerId,value?.sessionId,value?.serverInstanceId]);if(key===contextKey)return;contextKey=key;contextEpoch++;focusSerial++;focused=null;for(const value of rebuildDrafts.values())value.confirmed=false;
    if(repairForm){repairForm.start.disabled=true;repairForm.consent.checked=false;repairForm=null;}
    if(value){const identity=JSON.stringify([value.binding?.worldId,value.binding?.viewerPlayerId]);
      if(ownerWorldKey&&ownerWorldKey!==identity){
        jobs=[];patchJobs=[];worldPatchJobs=[];agents=[];sources=[];views=[];paging={page:0,pages:1,count:0};heads.clear();patchTargets.clear();containerDrafts.clear();rebuildDrafts.clear();
        draft=new GenerationDraft();taskDraft=new GenerationDraft();repairDrafts=new GenerationRepairDrafts();patchDraft=new UiPatchDraft();bindings=new BindingDrafts();
        worldBoards=createWorldBoardCards({send,report});worldContents=createWorldContentCards({windowFor,send,report,openRestore});worldPatches.dispose();worldPatches=createWorldPatchCards({windowFor,send,report,persist});
      }ownerWorldKey=identity;
    }
    for(const content of ownedWindows){content.replaceChildren();if(content.isConnected)node('p',__uiT("连接上下文已变化，请从当前目录或历史重新打开操作。"),content).className='muted';else ownedWindows.delete(content);}
    // Never call windowFor/open here: a Session renewal must not reopen or focus a hidden window.
    // Same-owner/world reconnect preserves operation IDs and text drafts; no automatic resubmission.
  }
  return { open, openHistorical, openPackageTools, session, agents(value) { agents = value; fillAgents(); }, update(value) { jobs = value; renderJobs();refreshRepair(); },
    heads(value){heads.clear();value.forEach(p=>heads.set(p.packageId,p));renderJobs();}, patches(value){patchJobs=value;renderJobs();},worldPatches(value){worldPatchJobs=value;renderJobs();},
    sources(value){sources=value;renderJobs();}, views(value){views=value;renderJobs();}, paging(value){paging=value;renderJobs();}, snapshot: () => ({...draft.snapshot(),bindings:bindings.snapshot(),uiPatchDraft:patchDraft.snapshot(),worldPatchDraft:worldPatches.snapshot(),taskDraft:taskDraft.snapshot(),repairDrafts:repairDrafts.snapshot()}),
    restore(value) { draft.restore(value);taskDraft.restore(value?.taskDraft);repairDrafts.restore(value?.repairDrafts); bindings.restore(value?.bindings); patchDraft.restore(value?.uiPatchDraft);worldPatches.restore(value?.worldPatchDraft); const input = document.querySelector('#generation-prompt'); if (!draft.edited) { if(input)input.value=draft.prompt; const scope=document.querySelector('#generation-purpose');if(scope)scope.value=draft.purpose; } fillAgents();renderJobs(); } };
}
