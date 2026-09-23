import {createSkinCards} from './skin-cards.mjs';
import {createScenePreview} from './scene-preview.mjs';
import {createBuildingFiles} from './building-files.mjs';
import {createJavaStudio} from './java-studio.mjs';
import {createPreferenceCards} from './preference-cards.mjs';
import {createPackageAssetCards} from './package-asset-cards.mjs';
import {createBootExtensionCards} from './boot-extension-cards.mjs';
import {createNativeApiCards} from './native-api-cards.mjs';
import {createResourcePackCards} from './resource-pack-cards.mjs';
import {createClientScriptCards} from './client-script-cards.mjs';
import {createDataPackCards} from './data-pack-cards.mjs';
import {createWorldRestore} from './world-restore-cards.mjs';
import {createNativeCompatibility} from './native-compatibility-cards.mjs';
import {createPackageCatalog} from './package-catalog-cards.mjs';
import {createGenerationHistory} from './generation-history-cards.mjs';
import {createEventManagement} from './event-management-cards.mjs';
import {createScheduleManagement} from './schedule-management-cards.mjs';
import {createTaskHistory} from './task-history-cards.mjs';
import {createSentContent} from './sent-content-cards.mjs';
import {createFeedbackHistory} from './feedback-history-cards.mjs';
import {createApiSettings} from './api-settings-cards.mjs';
import {createServerSettings} from './server-settings-cards.mjs';
import {createAgentModels} from './agent-model-cards.mjs';
import {createAgentManagement} from './agent-management-cards.mjs';
import { createRendererSettings } from './renderer-settings.mjs';
import { WindowState, containerBounds, taskbarAction } from './window-state.mjs';
import { createDesktopWindows } from './desktop-windows.mjs';
import { chooseInitialLayout, savedLayouts, applyLivePlacement, resolvePlacement, reflowAnchoredLayout, validateOpacity, opacityHidden } from './view-placement.mjs';
import { restoreHudLayout } from './hud-state.mjs';
import { releaseTrustedEscape } from './escape-policy.mjs';
import { createDeliveryCards } from './delivery-cards.mjs';
import { renderDeliveryDraftStatus } from './delivery-recovery.mjs';
import { createDecisionCards } from './decision-cards.mjs';
import { createGenerationCards } from './generation-cards.mjs';
import { createWorldActionCards } from './world-action-cards.mjs';
import { createAppearanceCards } from './appearance-cards.mjs';
import { createPersonaCards } from './persona-cards.mjs';
import { createConversationCards } from './conversation-cards.mjs';
import { createUiAgentCards } from './ui-agent-cards.mjs';
import { resolveInputTarget } from './input-target.mjs';
import { captureRegion } from './capture-region.mjs';
import { collectPaintLayout, createPaintLayoutPublisher } from './paint-layout.mjs';
import { createNativeAtlas } from './native-atlas.mjs';
import { resolveAtlasInput } from './atlas-input.mjs';
import { canDelegateView } from './ui-delegation-state.mjs';
const state = new WindowState();
let nativeAtlas=null;
function viewportWidth(){return nativeAtlas?.logical().width??window.innerWidth;}
function viewportHeight(){return nativeAtlas?.logical().height??window.innerHeight;}
state.workspace(document.body.dataset.workspaceVisible!=='false');
const nodes = new Map();
let lastSnapshot = null;
let connected = false;
let composition = false;
let drag = null;
const status = document.querySelector('#status');
let availableAgents = [];
const desktopWindows=createDesktopWindows({state,nodes,windowFor,send,pin:setWindowPinned,toggle:toggleWindow,persist:persistUiStateNow,agents:()=>availableAgents});
document.querySelector('#open-windows').onclick=desktopWindows.open;
const moreMenu=document.querySelector('#more-menu');
let menuEscapeClosedAt=-Infinity;
function dismissMoreWithEscape(){const now=performance.now();if(moreMenu.open){moreMenu.open=false;menuEscapeClosedAt=now;return true;}return now-menuEscapeClosedAt<250;}
moreMenu.addEventListener('toggle',()=>{publishPaintLayout();nativeAtlas?.schedule();});
document.querySelector('#more-panel').addEventListener('click',e=>{if(e.target.closest('button'))moreMenu.open=false;});
document.addEventListener('pointerdown',e=>{if(moreMenu.open&&!moreMenu.contains(e.target))moreMenu.open=false;});
let decisionContexts=[];
let hasServerSession = false, persistTimer;
let restoredLayouts = {}, restoredChat = '';
let hudRestoreStatus={entries:[],ready:false};
const layoutEdited = new Set();
const decisions = createDecisionCards({ windowFor, send, report, persist:saveUiState });
const nativeCompatibility=createNativeCompatibility({windowFor,send});
const worldRestore=createWorldRestore({windowFor,send,openCompatibility:nativeCompatibility.open});
const dataPacks=createDataPackCards({windowFor,send,openCompatibility:nativeCompatibility.open});
const bootExtensions=createBootExtensionCards({windowFor,send});document.querySelector('#open-boot-extensions').onclick=()=>bootExtensions.open();
window.addEventListener('mineagent:boot-upgrade-probe',()=>{const pane=id=>[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId===id),catalog=pane('runtime-package-catalog'),boot=pane('runtime-boot-extensions'),tools=pane('runtime-package-tools'),generation=pane('runtime-generation'),patch=pane('runtime-world-patch'),review=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId?.startsWith('world-review-')),card=boot?.querySelector('.event-card');send('bootUpgradeProbe',{catalogText:(catalog?.innerText||'').slice(0,16000),bootText:(boot?.innerText||'').slice(0,24000),toolsText:(tools?.innerText||'').slice(0,12000),generationText:(generation?.innerText||'').slice(0,16000),patchText:(patch?.innerText||'').slice(0,12000),reviewText:(review?.innerText||'').slice(0,16000),cardBackground:card?getComputedStyle(card).backgroundColor:''}).catch(()=>{});});
const nativeApi=createNativeApiCards({windowFor,send});document.querySelector('#open-native-api').onclick=nativeApi.open;
window.addEventListener('mineagent:native-api-probe',()=>{const find=id=>([...document.querySelectorAll('.window')].find(n=>n.dataset.viewId===id)?.innerText||'').slice(0,12000);send('nativeApiProbe',{selection:nativeApi.selection(),nativeText:find('runtime-native-api'),studioText:find('runtime-java-studio')}).catch(()=>{});});
const resourcePacks=createResourcePackCards({windowFor,send});document.querySelector('#open-resource-packs').onclick=()=>resourcePacks.open();
window.addEventListener('mineagent:resource-pack-probe',()=>{const pane=id=>[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId===id),catalog=pane('runtime-package-catalog'),resource=pane('runtime-resource-packs'),card=resource?.querySelector('.event-card');send('resourcePackProbe',{catalogText:(catalog?.innerText||'').slice(0,12000),resourceText:(resource?.innerText||'').slice(0,12000),windowBackground:resource?getComputedStyle(resource).backgroundColor:'',cardBackground:card?getComputedStyle(card).backgroundColor:''}).catch(()=>{});});
const clientScripts=createClientScriptCards({windowFor,send});document.querySelector('#open-client-scripts').onclick=()=>clientScripts.open();
window.addEventListener('mineagent:client-script-probe',()=>{const pane=id=>[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId===id),catalog=pane('runtime-package-catalog'),manager=pane('runtime-client-scripts'),studio=pane('runtime-java-studio'),delivery=pane('runtime-deliveries'),card=manager?.querySelector('.event-card'),studioCard=studio?.querySelector('.event-card'),studioEditor=studio?.querySelector('textarea[aria-label="Java 或 Rhino 源码"]'),studioPath=[...studio?.querySelectorAll('label')||[]].find(n=>n.textContent.includes('源码路径'))?.querySelector('input');send('clientScriptProbe',{catalogText:(catalog?.innerText||'').slice(0,12000),clientScriptText:(manager?.innerText||'').slice(0,12000),studioText:(studio?.innerText||'').slice(0,16000),studioSource:(studioEditor?.value||'').slice(0,16000),studioPath:(studioPath?.value||'').slice(0,128),deliveryText:(delivery?.innerText||'').slice(0,12000),cardBackground:card?getComputedStyle(card).backgroundColor:'',studioCardBackground:studioCard?getComputedStyle(studioCard).backgroundColor:''}).catch(()=>{});});
const generations = createGenerationCards({ windowFor, send, report, persist: saveUiState, openRestore:worldRestore.open, openDataPack:dataPacks.open, openResourcePack:resourcePacks.open, openClientScript:clientScripts.open });
const preferences=createPreferenceCards({windowFor,send});document.querySelector('#open-preferences').onclick=preferences.open;
const javaStudio=createJavaStudio({windowFor,send,openCatalog:()=>packageCatalog.open(),openNativeApi:()=>nativeApi.open(),openClientScripts:id=>clientScripts.open(id),nativeApiContext:()=>nativeApi.selection()});document.querySelector('#open-java-studio').onclick=javaStudio.open;nativeApi.onSelectionChanged(javaStudio.nativeContextChanged);
const packageAssets=createPackageAssetCards({windowFor,send,openTools:id=>generations.openPackageTools(id),openCatalog:()=>packageCatalog.open()});document.querySelector('#open-package-assets').onclick=()=>packageAssets.open();
const packageCatalog=createPackageCatalog({windowFor,send,openTools:generations.openPackageTools,openCompatibility:nativeCompatibility.open,openDataPack:dataPacks.open,openResourcePack:resourcePacks.open,openClientScript:clientScripts.open,openBoot:bootExtensions.open,openAssets:packageAssets.open,openJava:javaStudio.openPackage});document.querySelector('#open-package-catalog').onclick=packageCatalog.open;
const generationHistory=createGenerationHistory({windowFor,send,openGeneration:generations.open,openOperation:generations.openHistorical});document.querySelector('#open-generation-history').onclick=generationHistory.open;
const worldTasks=createWorldActionCards({windowFor,send,report});
const taskHistory=createTaskHistory({windowFor,send,openTasks:worldTasks.open});document.querySelector('#open-task-history').onclick=taskHistory.open;
const sentContent=createSentContent({windowFor,send,openInbox:()=>deliveries.open()});document.querySelector('#open-sent-content').onclick=sentContent.open;
const eventManagement=createEventManagement({windowFor,send,report,openTasks:worldTasks.open});document.querySelector('#open-events').onclick=eventManagement.open;
const scheduleManagement=createScheduleManagement({windowFor,send,openTasks:worldTasks.open,openEvents:eventManagement.open});document.querySelector('#open-schedules').onclick=scheduleManagement.open;
const serverSettings=createServerSettings({windowFor,send});document.querySelector('#open-server-settings').onclick=serverSettings.open;
const apiSettings=createApiSettings({windowFor,send,advanced:serverSettings.open});document.querySelector('#open-api-settings').onclick=apiSettings.open;
const scenePreview=createScenePreview({windowFor,send});
const buildingFiles=createBuildingFiles({windowFor,send,attached:(agent,file)=>{openChat();return conversations.attachment(agent,file);}});document.querySelector('#open-building-files').onclick=()=>buildingFiles.open();
const skins=createSkinCards({windowFor,send,openFiles:agent=>buildingFiles.open(agent)});
const agentModels=createAgentModels({windowFor,send});const agentManagement=createAgentManagement({windowFor,send,report,openModel:agentModels.open,openSkin:skins.open});document.querySelector('#open-agents').onclick=agentManagement.open;
const appearances=createAppearanceCards({windowFor,send,report,openDecision:q=>decisions.open(q)});
const personas=createPersonaCards({windowFor,send,report,persist:saveUiState});
const conversations=createConversationCards({send,report,persist:saveUiState,openApiSettings:apiSettings.open,openFiles:agent=>buildingFiles.open(agent)});
document.querySelector('#open-persona').onclick=personas.open;
document.querySelector('#open-appearance').onclick=appearances.open;
document.querySelector('#open-tasks').onclick=worldTasks.open;
const uiAgents = createUiAgentCards({windowFor,send,report});
const deliveries=createDeliveryCards({windowFor,send,report});
const feedbackHistory=createFeedbackHistory({windowFor,send});document.querySelector('#open-feedback-history').onclick=feedbackHistory.open;
document.querySelector('#open-deliveries').onclick=deliveries.open;
const rendererSettings=createRendererSettings({windowFor,send,report});document.querySelector('#open-renderer-settings').onclick=rendererSettings.open;
const captureHostDocumentId=crypto.randomUUID();
let paintLayoutReady=false,currentPaintToken='';
const paintLayoutPublisher=createPaintLayoutPublisher({documentId:captureHostDocumentId,
  token:()=>crypto.randomUUID().replaceAll('-','').slice(0,24),
  mark:token=>{currentPaintToken=token;
    let marker=document.getElementById('mineagent-layout-sync');
    if(!marker){marker=document.createElement('div');marker.id='mineagent-layout-sync';marker.setAttribute('aria-hidden','true');document.body.append(marker);}
    // One physical scanline, reserved for Native metadata and removed from our owned BGRA copy before upload.
    marker.style.cssText=`position:fixed;left:20px;top:0;width:16px;height:${1/devicePixelRatio}px;z-index:2147483646;pointer-events:none;display:flex;opacity:1;contain:strict;transform:none;border:0;padding:0;margin:0;`;
    marker.replaceChildren();for(let i=0;i<4;i++){const pixel=document.createElement('div');pixel.style.cssText=`width:4px;height:100%;flex:none;background:#${token.slice(i*6,i*6+6)}`;marker.append(pixel);}
  },
  send:frame=>window.mineagentPaintPlanQuery({request:JSON.stringify(frame),persistent:false,onSuccess(){},onFailure:(_,code)=>report(new Error(code))})
});
function publishPaintLayout(){if(!paintLayoutReady)return;try{const snapshot=nativeAtlas?nativeAtlas.snapshot():collectPaintLayout(document,window,getComputedStyle);if(snapshot)paintLayoutPublisher.publish(snapshot);}catch(e){report(e);}}
let captureLayoutEpoch=0,capturePaintToken='';
new MutationObserver(records=>{if(records.some(r=>{
  if(r.target.closest?.('#mineagent-capture-sync,#mineagent-layout-sync'))return false;
  if(r.type==='attributes')return true;
  return [...r.addedNodes,...r.removedNodes].some(n=>n.nodeType===1&&!['mineagent-capture-sync','mineagent-layout-sync'].includes(n.id));
})){captureLayoutEpoch++;publishPaintLayout();}}).observe(document.body,{subtree:true,childList:true,attributes:true,attributeFilter:['style','hidden','src','class']});
new ResizeObserver(publishPaintLayout).observe(document.body);
for(const id of ['dock','minimized','decision-page-tools','more-panel']){const node=document.getElementById(id);if(node)new ResizeObserver(publishPaintLayout).observe(node);}
window.addEventListener('resize',()=>captureLayoutEpoch++);
Object.defineProperty(window,'__mineagentCaptureRegion',{value:(requestId,viewId,paintToken)=>{
  let result;try{
    if(!/^[0-9a-f]{24}$/.test(paintToken))throw new Error('CAPTURE_PAINT_TOKEN');
    if(capturePaintToken!==paintToken){
      let marker=document.getElementById('mineagent-capture-sync');
      if(!marker){marker=document.createElement('div');marker.id='mineagent-capture-sync';marker.setAttribute('aria-hidden','true');
        marker.style.cssText='position:fixed;left:0;top:0;width:16px;height:4px;z-index:2147483647;pointer-events:none;display:flex;opacity:1;';
        document.body.append(marker);}
      marker.replaceChildren();for(let i=0;i<4;i++){const pixel=document.createElement('div');pixel.style.cssText=`width:4px;height:4px;flex:none;background:#${paintToken.slice(i*6,i*6+6)}`;marker.append(pixel);}capturePaintToken=paintToken;
    }
    result=captureRegion(viewId,document,window,getComputedStyle,captureHostDocumentId,captureLayoutEpoch);
  }catch(e){result={status:e.message};}
  window.mineagentCaptureQuery({request:JSON.stringify({requestId,result}),persistent:false,onSuccess(){},onFailure(){}});
},writable:false,configurable:false});
Object.defineProperty(window,'__mineagentPopupOwner',{value:()=>{
 const a=document.activeElement,n=a?.closest('.window'),r=a.getBoundingClientRect(),css=getComputedStyle(a);
 return {hostDocumentId:captureHostDocumentId,token:currentPaintToken,viewId:n?.dataset.viewId??'chrome:atlas',tag:a.tagName,url:a.tagName==='IFRAME'?a.src:location.href,frameRect:{x:r.x,y:r.y,width:r.width,height:r.height},control:{tag:a.tagName,secret:a.type==='password'||a.type==='file',visible:a.isConnected&&!a.disabled&&css.display!=='none'&&css.visibility!=='hidden'&&r.width>0&&r.height>0,rect:{x:r.x,y:r.y,width:r.width,height:r.height}}};
},writable:false,configurable:false});
Object.defineProperty(window,'__mineagentResolveInput',{value:(requestId,request)=>{
  let result;try{const viewId=nativeAtlas?resolveAtlasInput(request,document,window,nativeAtlas.snapshot(),currentPaintToken,drag):resolveInputTarget(request,document,viewportWidth(),viewportHeight());result={requestId,viewId};}catch(error){result={requestId,error:error.message};}
  window.mineagentInputQuery({request:JSON.stringify(result),persistent:false,onSuccess(){},onFailure(){}});
},writable:false,configurable:false});
function saveUiState() {
  if (!hasServerSession) return;
  clearTimeout(persistTimer);
  persistTimer = setTimeout(() => persistUiStateNow().catch(report), 200);
}
async function persistUiStateNow(){
  if(!hasServerSession)throw new Error('VIEW_NOT_RENDERED');clearTimeout(persistTimer);
  const value={layouts:savedLayouts(state.views,restoredLayouts),drafts:decisions.snapshot(),generationDraft:generations.snapshot(),personaDrafts:personas.snapshot(),conversationDrafts:conversations.snapshot(),chatDraft:document.querySelector('#chat-decision')?.value?document.querySelector('#chat-draft')?.value??restoredChat:restoredChat};
  if(JSON.stringify(value).length>48000)throw new Error('UI_DRAFT_BUDGET');return send('persistUiState',{state:value});
}
document.addEventListener('input', saveUiState);
document.addEventListener('change', saveUiState);
function send(channel, data = {}) {
  return new Promise((resolve, reject) => {
    if (typeof window.mineagentQuery !== 'function') return reject(new Error('BROWSER_BRIDGE_NOT_READY'));
    window.mineagentQuery({ request: JSON.stringify({ channel, ...data }), persistent: false,
      onSuccess: text => { try { resolve(JSON.parse(text)); } catch { reject(new Error('INVALID_RECEIPT')); } },
      onFailure: (_, text) => reject(new Error(text)) });
  });
}
function report(error) { status.textContent = String(error.message || error).slice(0, 250); }
function el(tag, text, parent) { const n = document.createElement(tag); if (text != null) n.textContent = text; parent?.append(n); return n; }
function renderLayout(id) {
  const view = state.views.get(id), node = nodes.get(id); if (!view || !node) return;
  const { x, y, width, height } = view.bounds;
  const desired=validateOpacity(view.opacity??view.agentPlacement?.opacity??view.placement?.opacity);
  const unavailable=desired!==undefined&&!nativeAtlas;if(unavailable)report(new Error('UI_OPACITY_BACKEND_REQUIRED'));
  const visible=state.visible(id)&&!unavailable;
  node.dataset.pinned=String(view.pinned);const pinButton=node.querySelector('[data-action=pin-window]');if(pinButton){pinButton.textContent=view.pinned?'已悬浮':'悬浮';pinButton.setAttribute('aria-pressed',String(view.pinned));pinButton.title=view.pinned?'取消游戏中悬浮':'返回游戏后继续显示';}
  const content=node.querySelector('.content');if(content)content.inert=view.mode==='PASSIVE_HUD'||!state.interacting;
  if(nativeAtlas&&desired!==undefined)nativeAtlas.setOpacity(id,desired);
  Object.assign(node.style, { left: nativeAtlas?node.style.left:`${x}px`, top: nativeAtlas?node.style.top:`${y}px`, width: `${width}px`, height: `${height}px`, zIndex: view.z, display: visible ? 'flex' : 'none' });
  if(nativeAtlas){const actual=node.getBoundingClientRect();nativeAtlas.note(node,{x,y,width:actual.width,height:actual.height,z:view.z});}
  if(node._visible!==visible){node._visible=visible;send('viewVisibility',{viewId:id,visible}).catch(report);}
  if(view.layoutKey){
    const r=nativeAtlas?{...view.bounds}:node.getBoundingClientRect(),value={viewId:id,hostDocumentId:captureHostDocumentId,visible,bounds:visible?{x:r.x,y:r.y,width:r.width,height:r.height}:null,storedBounds:{...view.bounds},viewport:{width:viewportWidth(),height:viewportHeight()},area:availableArea(),source:view.layoutSource||'DEFAULT',opacity:nativeAtlas?nativeAtlas.getOpacity(id):224/255,opacitySupported:!!nativeAtlas,opacityUnavailable:unavailable,appearance:view.placement?.appearance||'GLASS_SAGE',coordinateSpace:'VIEWPORT_CSS_PIXELS',anchorSpace:'AVAILABLE_AREA'};
    const signature=JSON.stringify(value);if(node._layoutSignature!==signature){node._layoutSignature=signature;view.layoutRevision=(view.layoutRevision||0)+1;send('viewLayout',{...value,revision:view.layoutRevision}).catch(report);}
    return {...value,revision:view.layoutRevision};
  }
}
function availableArea(){const top=Math.min(viewportHeight()-1,Math.max(12,document.querySelector('#dock').getBoundingClientRect().bottom+10));return {x:Math.min(12,Math.max(0,viewportWidth()-1)),y:top,width:Math.max(1,viewportWidth()-24),height:Math.max(1,viewportHeight()-top-12)};}
function applyInitialPlacement(id){
  const view=state.views.get(id);if(!view?.layoutKey)return false;
  const area=availableArea(),selected=chooseInitialLayout(view.placement,restoredLayouts[view.layoutKey]||restoredLayouts[id],area,layoutEdited.has(id)||drag?.id===id);if(!selected)return false;
  if(selected.opacity!==undefined)view.opacity=selected.opacity;
  if(typeof selected.pinned==='boolean'&&view.mode!=='MODAL')state.pin(id,selected.pinned);
  view.layoutSource=selected.source;view.minimized=selected.minimized===true;if(selected.agentPlacement)view.agentPlacement=selected.agentPlacement;
  if(selected.source==='PACKAGE'||selected.source==='AGENT'){const b=selected.bounds;state.layout(id,{...b,x:b.x-area.x,y:b.y-area.y},area.width,area.height);view.bounds.x+=area.x;view.bounds.y+=area.y;renderLayout(id);}
  else layoutInWorkspace(id,selected.bounds);return true;
}
function editedLayout(id){layoutEdited.add(id);const view=state.views.get(id);if(view?.layoutKey){view.layoutSource='PLAYER';view.agentPlacement=null;}}
async function applyPresentation(data){
  const base={operationId:data.operationId,documentId:data.documentId,executionMode:'HOST_PRESENTATION',persisted:false};let applied=false;
  try{
    const view=state.views.get(data.viewId),node=nodes.get(data.viewId);
    if(data.hostDocumentId!==captureHostDocumentId||!node||node.querySelector('iframe')?.src!==data.url||node.dataset.packageId!==data.packageId||Number(node.dataset.packageRevision)!==data.packageRevision)throw new Error('STALE_VIEW');
    if(node.dataset.actorKind!=='AGENT'||node.dataset.contentRendered!=='true')throw new Error('NOT_INTERACTABLE');
    const result=applyLivePlacement(view,data,availableArea(),state.visible(data.viewId),drag?.id===data.viewId,!!nativeAtlas);layoutEdited.add(data.viewId);applied=true;
    const actual=renderLayout(data.viewId);dock();await persistUiStateNow();const paint=data.placement.opacity!==undefined?await awaitPresentationPaint(data.requestId):null;await send('presentationAck',{requestId:data.requestId,receipt:{...base,status:'APPLIED_HOST',persisted:true,...(paint?{opacityPaint:paint}:{}),afterLayoutRevision:actual.revision,adjusted:result.adjusted,actual}});
  }catch(e){await send('presentationAck',{requestId:data.requestId,receipt:{...base,status:applied?'UI_PRESENTATION_UNKNOWN':['STALE_LAYOUT','STALE_VIEW','NOT_INTERACTABLE'].includes(e.message)?e.message:'UI_PRESENTATION_FAILED'}}).catch(report);}
}
async function awaitPresentationPaint(requestId){
  const until=performance.now()+8000;
  while(performance.now()<until){const response=await send('presentationPaint',{requestId});if(response.status==='NATIVE_ATLAS_PAINTED')return response;if(response.status!=='PENDING')throw new Error(response.status);await new Promise(resolve=>setTimeout(resolve,50));}
  throw new Error('UI_PRESENTATION_UNKNOWN');
}
function layoutInWorkspace(id, bounds) {
  const top = Math.min(viewportHeight() - 1, document.querySelector('#dock').getBoundingClientRect().bottom + 10);
  const availableHeight = Math.max(1, viewportHeight() - top - 35);
  state.layout(id, { ...bounds, y: bounds.y - top }, viewportWidth(), availableHeight);
  state.views.get(id).bounds.y += top;
  renderLayout(id);
}
function dock() {
  const tray = document.querySelector('#minimized'); tray.replaceChildren();
  for (const [id, view] of state.views) if (state.workspaceVisible||!['INTERACTIVE_FLOATING','MODAL'].includes(view.mode)) {
    const hidden=opacityHidden(view.opacity??view.agentPlacement?.opacity??view.placement?.opacity);
    const button=el('button',(hidden?'恢复可见 · ':'')+nodes.get(id).dataset.title+(view.pinned?' · 悬浮':''),tray);button.dataset.restoreOpacityView=id;button.dataset.windowTask=id;button.setAttribute('aria-pressed',String(!view.minimized&&state.focused===id));button.title=(view.minimized?'显示':'切换 / 隐藏')+' · '+nodes.get(id).dataset.title;
    button.onclick=async()=>{if(hidden){try{const revision=view.layoutRevision;await send('restoreViewOpacity',{viewId:id,expectedLayoutRevision:revision});if(state.views.get(id)!==view||view.layoutRevision!==revision)return;view.opacity=1;state.minimize(id,true);}catch(e){report(e);return;}}toggleWindow(id,true);};
  }
  desktopWindows.refresh();nativeAtlas?.schedule();
}
function toggleWindow(id,taskbar=false){const view=state.views.get(id);if(!view)return;editedLayout(id);if(taskbar&&taskbarAction(view,state.focused)==='FOCUS'){state.focus(id);}else{const hide=!view.minimized;if(hide)send('previewStop',{viewId:id}).catch(report);if(hide&&id==='runtime-windows')desktopWindows.cancel();state.minimize(id,hide);if(!hide)state.focus(id);}renderLayout(id);dock();saveUiState();}
function setWindowPinned(id,value){state.pin(id,value);editedLayout(id);renderLayout(id);dock();saveUiState();}
function close(id) {
  const node = nodes.get(id); if (!node) return;
  if(id==='runtime-windows')desktopWindows.cancel();
  if(id==='runtime-chat')conversations.closed();
  if(id==='runtime-preview')scenePreview.session();
  if(id==='runtime-skins')skins.session();
  if(id==='runtime-deliveries')deliveries.closed();
  const view=state.views.get(id);if(!view.layoutKey||view.layoutSource!=='PACKAGE')restoredLayouts[view.layoutKey||id] = {...savedLayouts(new Map([[id,view]]),{} )[view.layoutKey||id],minimized:false};
  node._observer?.disconnect(); node.remove(); nodes.delete(id); state.close(id); dock();
  send('viewClosed', { viewId: id }).catch(report);
  saveUiState();
}
function windowFor(id, title, mode = 'INTERACTIVE_FLOATING', reveal = true) {
  if (nodes.has(id)) { if(reveal){state.minimize(id, false); state.focus(id);} renderLayout(id); dock(); return nodes.get(id).querySelector('.content'); }
  const view = state.open(id, mode);
  const node = el('section', null, document.querySelector('#windows')); nodes.set(id, node);
  node.className = 'window'; node.dataset.title = title; node.dataset.mode = mode; node.dataset.viewId = id;
  node.setAttribute('aria-label', title); node.setAttribute('role', 'region');
  const bar = el('header', null, node); bar.className = 'titlebar'; const heading=el('strong', title, bar);heading.title=title;
  if (mode === 'PREVIEW') el('span', '预览 · 禁止世界写入', bar).className = 'badge';
  if (mode === 'CONTENT') el('span', '服务端绑定', bar).className = 'badge';
  if (mode === 'PASSIVE_HUD') el('span', '只读 HUD', bar).className = 'badge';
  if(mode!=='MODAL'){const pin=el('button','悬浮',bar);pin.dataset.action='pin-window';pin.setAttribute('aria-label','切换窗口悬浮');pin.onclick=()=>setWindowPinned(id,!view.pinned);}
  const min = el('button', '−', bar); min.setAttribute('aria-label', '收起'); min.onclick = () => {
    editedLayout(id);
    if(id==='runtime-windows')desktopWindows.cancel();
    send('previewStop',{viewId:id}).catch(report); // Also revoke a delegate still waiting for its first RPC.
    state.minimize(id, true); renderLayout(id); dock(); saveUiState(); };
  const shut = el('button', '×', bar); shut.setAttribute('aria-label', '关闭'); shut.onclick = () => close(id);
  const content = el('div', null, node); content.className = 'content';
  if(mode==='PASSIVE_HUD')content.inert=true;
  if(typeof restoredLayouts[id]?.pinned==='boolean'&&mode!=='MODAL')state.pin(id,restoredLayouts[id].pinned);
  const resize = el('div', null, node); resize.className = 'resize';
  node.addEventListener('pointerdown', event => {
    if(event.isTrusted && node.querySelector('.agent-stop')) send('previewStop',{viewId:id}).catch(report);
    state.focus(id); renderLayout(id); dock(); });
  function start(event, kind) {
    if (event.target.closest('button')) return;
    event.preventDefault(); event.currentTarget.setPointerCapture(event.pointerId);
    drag = { id, kind, capture:event.currentTarget, pointerId:event.pointerId, x: event.clientX, y: event.clientY, bounds: { ...view.bounds } };
  }
  bar.addEventListener('pointerdown', e => start(e, 'move')); resize.addEventListener('pointerdown', e => start(e, 'resize'));
  layoutInWorkspace(id, restoredLayouts[id]?.bounds || (mode==='PASSIVE_HUD'
    ? {...view.bounds,x:24,y:80}
    : { x: 30 + nodes.size * 22, y: 65 + nodes.size * 22, width: id==='runtime-chat'?760:id==='runtime-windows'?680:520, height: id==='runtime-chat'?620:id==='runtime-windows'?660:430 }));
  state.focus(id); renderLayout(id);dock(); return content;
}
document.addEventListener('pointermove', event => {
  if (!drag) return;
  const b = { ...drag.bounds }, dx = event.clientX - drag.x, dy = event.clientY - drag.y;
  if (drag.kind === 'move') { b.x += dx; b.y += dy; } else { b.width += dx; b.height += dy; }
  layoutInWorkspace(drag.id, b);
});
document.addEventListener('pointerup', () => { if (drag) { editedLayout(drag.id);renderLayout(drag.id);saveUiState(); } drag = null; });
document.addEventListener('pointercancel', () => { drag = null; });
function releaseWorkspaceInput(){
  moreMenu.open=false;desktopWindows.cancel();
  if(drag?.capture?.hasPointerCapture(drag.pointerId))drag.capture.releasePointerCapture(drag.pointerId);
  drag=null;document.activeElement?.blur();saveUiState();
}
function reflowWorkspace(){
  const area=availableArea();
  for(const [id,view] of state.views){
    if(drag?.id===id)continue;
    const anchored=reflowAnchoredLayout(view,area,false);
    if(anchored){view.bounds=anchored;renderLayout(id);}else layoutInWorkspace(id,view.bounds);
  }
}
addEventListener('resize',()=>{reflowWorkspace();nativeAtlas?.schedule();});
// Trust/status text can wrap without a viewport resize. Keep the actual dock
// geometry authoritative without recreating views, resetting drafts or focus.
let dockReflowQueued=false;
new ResizeObserver(()=>{if(dockReflowQueued)return;dockReflowQueued=true;requestAnimationFrame(()=>{dockReflowQueued=false;reflowWorkspace();});}).observe(document.querySelector('#dock'));
document.addEventListener('compositionstart', () => { composition = true;send('compositionState',{active:true}).catch(report); });
document.addEventListener('compositionend', () => { composition = false;send('compositionState',{active:false}).catch(report); });
document.addEventListener('keydown', event => {
  if (event.key !== 'Escape' || composition || event.isComposing) return;
  event.preventDefault();
  if(dismissMoreWithEscape())return;
  send('releaseInput').catch(report);
});
function openStatus() {
  const content = windowFor('runtime-status', 'WebGUI 宿主状态');
  if (content.childElementCount) return;
  el('h2', '本地网页 · 独立生命周期', content);
  el('p', '一个透明 WebGUI 宿主协调多个窗口。关闭对话不会关闭其他窗口。', content);
  el('p', '服务端会话授权、可信选择卡和对话已接通。包页面生成与 AI 操作仍在实施。', content).className = 'muted';
  const echoLabel = el('label', '桥接回读测试', content); echoLabel.htmlFor = 'echo-input';
  const input = el('input', null, content); input.id = 'echo-input'; input.value = '你好，Minecraft';
  const button = el('button', '发送并回读', content); button.id = 'echo-submit';
  const receipt = el('pre', '', content); receipt.id = 'echo-receipt'; receipt.setAttribute('role', 'status');
  button.onclick = async () => { try { const r = await send('echo', { text: input.value }); receipt.textContent = r.text; } catch (e) { report(e); } };
  el('h3','登录时恢复的 HUD',content);const bookmarks=el('div','',content);bookmarks.id='hud-restore-list';renderHudRestoreStatus();
}
function renderHudRestoreStatus(){
  const list=document.querySelector('#hud-restore-list');if(!list)return;list.replaceChildren();
  if(hudRestoreStatus.storageError){el('p',hudRestoreStatus.storageError,list).className='error';el('button','清除无效的 HUD 恢复设置',list).onclick=()=>send('hudResetPreferences').catch(report);return;}
  if(!hudRestoreStatus.entries.length){el('p','在 HUD 标题栏启用“登录时恢复”；关闭 HUD 会取消恢复。',list).className='muted';return;}
  for(const entry of hudRestoreStatus.entries){const row=el('div',null,list);row.className='generation-job';el('p',`HUD ${entry.target.slice(0,8)} · ${entry.status}`,row);
    el('button','不再恢复',row).onclick=()=>send('hudForget',{key:entry.key}).catch(report);
    if(entry.status==='RESTORE_FAILED')el('button','重试恢复',row).onclick=()=>send('hudRetry',{key:entry.key}).catch(report);
  }
}
function openChat() {
  const content = windowFor('runtime-chat', 'AI 对话');
  if (content.childElementCount) return;
  const intro=el('p', '', content);intro.className='muted';intro.id='conversation-intro';
  el('label', 'Agent', content).id='chat-agent-label';
  const agent = el('select', null, content); agent.id = 'chat-agent'; agent.setAttribute('aria-label', 'Agent');
  fillAgents();
  const taskAnswer=el('details',null,content);taskAnswer.id='chat-task-answer';el('summary','待确认',taskAnswer);
  const target=el('select',null,taskAnswer);target.id='chat-decision';target.setAttribute('aria-label','回答待决问题');agent.onchange=()=>{fillDecisionTargets();conversations.agent(agent.value);};fillDecisionTargets();
  const notice=el('p','',taskAnswer);notice.id='chat-decision-result';notice.setAttribute('role','status');
  conversations.mount(content,agent.value);
  el('label', '消息', content).id='chat-message-label';
  const text = el('textarea', null, content); text.setAttribute('aria-label', '消息');
  text.id = 'chat-draft';text.maxLength=16384;text.value = '';text.disabled=!conversations.current();text.placeholder='输入消息或需求';text.addEventListener('input',()=>{if(!target.value)conversations.edit(text.value);else restoredChat=text.value;});
  target.addEventListener('change',()=>{text.disabled=!target.value&&(!conversations.current()||conversations.current().state!=='ACTIVE');text.value=target.value?restoredChat:conversations.current()?conversations.snapshot().drafts[conversations.current().conversationId]||'':'';});
  const actions = el('div', null, content); actions.className = 'actions';
  const submit = el('button', '发送', actions);
  submit.onclick = async () => {
    if (!connected || !text.value.trim()) return;
    submit.disabled = true;
    try {
      let context=decisionContexts.find(q=>q.decisionId===target.value&&q.agentId===agent.value);
      if(!context&&/^(?:我选|选择|选)?\s*第?[0-9一二三四五六七八九十两]+(?:个|项|号)/.test(text.value.trim())){
        const pending=decisionContexts.filter(q=>q.agentId===agent.value&&q.status==='OPEN');if(pending.length>1||pending.some(q=>q.agentPendingCount>1))throw new Error('DECISION_CONTEXT_REQUIRED');context=pending[0];
      }
      const submitted=text.value,submittedAgent=agent.value;
      const result = context?await send('chatSend', { agentId: agent.value, text: submitted,decisionId:context.decisionId,decisionRevision:context.revision }):await conversations.send(submitted);
      if (!['ACCEPTED', 'APPLIED'].includes(result.code)) throw new Error(result.values?.errorCode || result.code);
      if(result.values?.transport==='DECISION_CHAT'){
        if(result.values.decision)decisions.updateOne(JSON.parse(result.values.decision),result.values.answer?JSON.parse(result.values.answer):null,result.values.domainEffect?JSON.parse(result.values.domainEffect):null);
        notice.textContent='聊天回答已记录；外观执行结果见对应选择卡，回答接受不等于外观已应用。';
      }
      if(context&&agent.value===submittedAgent&&text.value===submitted)text.value = ''; }
    catch (e) { report(e); } finally { submit.disabled = false; }
  };
  el('button', '刷新', actions).onclick = () => conversations.refresh();
  if (lastSnapshot) showSnapshot(lastSnapshot);
}
function fillAgents() {
  const select = document.querySelector('#chat-agent'); if (!select) return;
  const previous = select.value; select.replaceChildren();
  for (const agent of availableAgents) { const option = el('option', agent.name, select); option.value = agent.id; }
  if (availableAgents.some(a => a.id === previous)) select.value = previous;
  if(select.value!==previous)conversations.agent(select.value);
  fillDecisionTargets();
}
function fillDecisionTargets(){const select=document.querySelector('#chat-decision'),agent=document.querySelector('#chat-agent');if(!select||!agent)return;const previous=select.value;select.replaceChildren();el('option','普通对话 / 唯一问题的序号回答',select).value='';for(const q of decisionContexts.filter(q=>q.agentId===agent.value)){const option=el('option',`${q.title} · ${q.status}`,select);option.value=q.decisionId;}if([...select.options].some(o=>o.value===previous))select.value=previous;}
function showSnapshot(data) {
  lastSnapshot = data; connected = data.connected === true;
  status.textContent = connected ? 'WebGUI 已连接游戏' : 'WebGUI 已就绪 · 未进入世界';
  const history = document.querySelector('#chat-history');
  if (history&&!history.dataset.persistentConversation) history.textContent = data.conversationText || '尚无当前会话';
}
function openPackage(data) {
  // Only native host messages create a frame. Package messages cannot create/retarget windows.
  const url = new URL(data.url);
  if (url.origin !== location.origin || !['PASSIVE_HUD', 'PREVIEW', 'CONTENT'].includes(data.mode)) throw new Error('PACKAGE_MODE_INVALID');
  const content = windowFor(data.viewId, data.title, data.mode);
  const view=state.views.get(data.viewId);if(typeof data.layoutKey!=='string'||!/^package:[a-f0-9]{64}$/.test(data.layoutKey))throw new Error('UI_LAYOUT_SCOPE');view.layoutKey=data.layoutKey;view.placement=data.placement||null;applyInitialPlacement(data.viewId);renderLayout(data.viewId);
  if(data.contentKind==='CONTAINER'&&!view.placement&&!restoredLayouts[view.layoutKey])layoutInWorkspace(data.viewId,containerBounds(viewportWidth(),viewportHeight()));
  nodes.get(data.viewId).dataset.targetObjectId=data.targetObjectId||'';nodes.get(data.viewId).dataset.contentKind=data.contentKind||'';if(data.contentKind==='CONTAINER')nodes.get(data.viewId).querySelector('.badge').textContent=data.actorKind==='AGENT'?'原生容器 · Agent 自身背包':'原生容器 · 玩家操作';
  nodes.get(data.viewId).dataset.packageId=data.packageId||'';nodes.get(data.viewId).dataset.packageRevision=data.packageRevision||'';
  nodes.get(data.viewId).dataset.candidatePreview=String(!!data.candidatePreview);
  if(data.candidatePreview){const badge=nodes.get(data.viewId).querySelector('.badge');if(badge)badge.textContent='候选预览 · 只读';}
  if (content.childElementCount) return;
  content.classList.add('frame');
  if(data.mode==='PASSIVE_HUD'&&data.targetObjectId){
    const remember=el('button','登录时恢复',nodes.get(data.viewId).querySelector('.titlebar'));remember.dataset.action='remember-hud';remember.setAttribute('aria-pressed','false');remember.disabled=true;
    remember.title='仅保存该世界/玩家的 HUD 绑定和布局；下次登录仍重新授权，不保存比分或 Session';
    remember.onclick=async()=>{const v=state.views.get(data.viewId);remember.disabled=true;try{await send('hudRemember',{viewId:data.viewId,enabled:remember.getAttribute('aria-pressed')!=='true',bounds:v.bounds,minimized:v.minimized});}catch(e){report(e);}finally{remember.disabled=false;}};
  }
  if(data.mode==='CONTENT'&&!['CONTAINER','WORLD','DELIVERY'].includes(data.contentKind)){const delegate=el('button','让 AI 操作',nodes.get(data.viewId).querySelector('.titlebar'));delegate.dataset.action='open-delegation';delegate.disabled=true;delegate.onclick=()=>uiAgents.open(data.viewId);}
  if(data.mode==='CONTENT'&&data.contentKind==='WORLD'){
    const bar=nodes.get(data.viewId).querySelector('.titlebar');bar.querySelector('.badge').textContent=data.actorKind==='AGENT'?'物件界面 · Agent 自身身份':'物件界面 · 玩家身份';
    if(data.actorKind==='PLAYER'){const button=el('button','让 Agent 操作',bar);button.dataset.action='open-world-delegation';button.disabled=true;button.onclick=()=>uiAgents.openWorld(data.viewId);}
    else{const stop=el('button','停止 Agent',bar);stop.dataset.action='stop-world-agent';stop.onclick=()=>send('previewStop',{viewId:data.viewId}).catch(report);}
  }
  if(data.mode==='PREVIEW'){
    const delegate=el('button','让 AI 操作',nodes.get(data.viewId).querySelector('.titlebar'));delegate.dataset.action='open-page-delegation';
    delegate.onclick=async()=>{delegate.disabled=true;try{const r=await send('packageAction',{action:'preparePage',viewId:data.viewId});if(r.code!=='ACCEPTED')throw new Error(r.values?.errorCode||r.code);uiAgents.open(data.viewId,true);}catch(e){report(e);}finally{delegate.disabled=false;}};
  }
  if(data.mode==='CONTENT'&&!['CONTAINER','WORLD','DELIVERY'].includes(data.contentKind)){
    const takeover=el('button','接管',nodes.get(data.viewId).querySelector('.titlebar'));takeover.dataset.action='takeover';
    takeover.title='停止当前执行者，保存草稿并在新的只读文档中恢复；不会自动提交';
    takeover.onclick=async()=>{takeover.disabled=true;try{const r=await send('takeoverUi',{action:'begin',viewId:data.viewId});if(r.code!=='ACCEPTED')throw new Error(r.values?.errorCode||r.code);}catch(e){report(e);takeover.disabled=false;}};
  }
  const frame = document.createElement('iframe'); frame.title = data.title; frame.name = data.viewId;
  if(data.mode==='PASSIVE_HUD')frame.tabIndex=-1;
  frame.setAttribute('sandbox', 'allow-scripts allow-forms'); frame.referrerPolicy = 'no-referrer';
  frame.onload = () => send('previewLoaded',{viewId:data.viewId}).catch(report);
  frame.src = url.href; nodes.get(data.viewId)._frame = frame; content.append(frame);dock();
}
addEventListener('message', event => {
  const pair = [...nodes].find(([, node]) => node._frame?.contentWindow === event.source);
  if (!pair || event.origin !== 'null' || !event.data || typeof event.data !== 'object') return;
  if (event.data.channel === 'close') close(pair[0]);
  // No package-to-game write route until authoritative UiSession/actor validation is connected.
});
addEventListener('mineagent:host', event => {
  try {
    const { channel, data } = event.detail;
    if (channel === 'snapshot') showSnapshot(data);
    else if(channel==='presentationApply')applyPresentation(data).catch(report);
    else if(channel==='nativeEscape'&&releaseTrustedEscape(composition,document.activeElement?.tagName)){if(!dismissMoreWithEscape())send('releaseInput').catch(report);}
    else if(channel==='interactionMode'){
      state.interaction(data.active);document.body.dataset.interacting=String(state.interacting);
      if(!state.interacting)releaseWorkspaceInput();
      for(const id of state.views.keys())renderLayout(id);
    }
    else if(channel==='workspaceMode'){
      state.workspace(data.visible);document.body.dataset.workspaceVisible=String(state.workspaceVisible);
      if(!state.workspaceVisible)releaseWorkspaceInput();
      for(const id of state.views.keys())renderLayout(id);dock();
    }
    else if(channel==='skinUiOpen'){skins.open(data.agentId);}
    else if(channel==='previewOpen'){scenePreview.open(data.previewId);}
    else if(channel==='buildingFilesOpen'){buildingFiles.open(data.agentId||'',data.fileId||'');}
    else if (channel === 'session') { skins.session();scenePreview.session();buildingFiles.session();deliveries.session(data);feedbackHistory.session(data);sentContent.session(data);taskHistory.session(data);generationHistory.session(data);packageCatalog.session(data);packageAssets.session(data);preferences.session(data);javaStudio.session(data);nativeCompatibility.session(data);worldRestore.session(data);dataPacks.session(data);resourcePacks.session(data);clientScripts.session(data);nativeApi.session(data);bootExtensions.session(data);generations.session(data);scheduleManagement.session(data);eventManagement.session(data);agentModels.session();agentManagement.session(data);worldTasks.session();serverSettings.session();apiSettings.session();hasServerSession = true; status.textContent = ''; }
    else if(channel==='hudRestoreStatus'){hudRestoreStatus=data;renderHudRestoreStatus();}
    else if(channel==='hudRememberState'){
      const button=nodes.get(data.viewId)?.querySelector('[data-action=remember-hud]');if(button){button.disabled=!data.available;button.setAttribute('aria-pressed',String(data.enabled));button.textContent=data.enabled?'已开启登录恢复':'登录时恢复';}
    }
    else if(channel==='hudRestoreLayout'){
      if(restoreHudLayout(state,data.viewId,data.layout,layoutEdited.has(data.viewId)||drag?.id===data.viewId,viewportWidth(),viewportHeight())){
        renderLayout(data.viewId);dock();
      }
    }
    else if (channel === 'uiStateRestore') {
        conversations.restore(data.conversationDrafts);
        personas.restore(data.personaDrafts);
      if (data.layouts && typeof data.layouts === 'object' && Object.keys(data.layouts).length <= 64) {
        restoredLayouts = data.layouts;
        for (const [id, view] of state.views) if (!layoutEdited.has(id) && drag?.id !== id) {
          try { if(!applyInitialPlacement(id)&&restoredLayouts[id]?.bounds){if(typeof restoredLayouts[id].pinned==='boolean'&&view.mode!=='MODAL')state.pin(id,restoredLayouts[id].pinned);layoutInWorkspace(id, restoredLayouts[id].bounds);} } catch { /* Invalid local layout is not authority. */ }
        }
      }
      dock();decisions.restore(data.drafts);
      generations.restore(data.generationDraft);
      if (typeof data.chatDraft === 'string' && data.chatDraft.length <= 8192) {
        restoredChat = data.chatDraft; const input = document.querySelector('#chat-draft'); if (input && !input.value&&!conversations.current()) input.value = restoredChat;
      }
    }
    else if (channel === 'sessionError') report(new Error(data.code));
    else if (channel === 'decisions') decisions.update(data);
    else if (channel === 'decisionUpdate') decisions.updateOne(data.request,data.answer,data.effect);
    else if (channel === 'decisionPaging') {
      document.querySelector('#decision-page-tools').hidden=data.count===0;
      document.querySelector('#decision-page-label').textContent=`${data.page+1}/${data.pages} · 待决 ${data.pendingCount}`;
      const prev=document.querySelector('#decision-prev'),next=document.querySelector('#decision-next');
      prev.disabled=data.page===0;next.disabled=data.page+1>=data.pages;
      prev.onclick=()=>send('decisionPage',{page:data.page-1}).catch(report);next.onclick=()=>send('decisionPage',{page:data.page+1}).catch(report);
    }
    else if(channel==='decisionContexts'){decisionContexts=data;fillDecisionTargets();}
    else if (channel === 'agents') { availableAgents = data; desktopWindows.refresh(); preferences.agents(data);javaStudio.agents(data); fillAgents(); generations.agents(data); uiAgents.agents(data); worldTasks.agents(data); appearances.agents(data); personas.agents(data); }
    else if(channel==='conversationVoiceStatus')conversations.voiceStatus(data);
    else if(channel==='conversationSpeechDraft')conversations.speechDraft(data);
    else if(channel==='sessionError'){deliveries.session(null);feedbackHistory.reset();sentContent.reset();taskHistory.reset();generationHistory.reset();packageCatalog.reset();packageAssets.reset();preferences.reset();javaStudio.reset();nativeCompatibility.reset();worldRestore.reset();dataPacks.reset();resourcePacks.reset();clientScripts.reset();nativeApi.reset();bootExtensions.reset();generations.session(null);scheduleManagement.reset();eventManagement.reset();report(new Error(data.code||'SESSION_UNAVAILABLE'));}
    else if(channel==='settingsVersion'||channel==='settingsChanged'){serverSettings.changed(data);apiSettings.changed(data);}
    else if(channel==='agentManagement')agentManagement.update(data);
    else if(channel==='worldTasks')worldTasks.update(data);
    else if (channel === 'generationJobs') generations.update(data);
    else if (channel === 'packageHeads') generations.heads(data);
    else if (channel === 'patchJobs') generations.patches(data);
    else if (channel === 'worldPatchJobs') generations.worldPatches(data);
    else if(channel==='closeManagedView')close(data.viewId);
    else if(channel==='uiPackageTransition'){
      const labels={CAPTURING:'保存旧窗口草稿…',PREVIEW:'载入只读改版候选…',CHECKING_CANDIDATE:'核验候选表单与真实画面…',COMMITTING:'提交包版本…',RESTORING:'在新只读文档恢复草稿…',SWAPPING:'切换窗口并回收旧页面…',COMPLETE:'页面已切换；草稿只读恢复，仍须明确启用编辑后保存。',NOT_COMMITTED:'改版未确认提交，旧草稿保留；请检查改版历史。',COMMITTED_RESTORE_FAILED:'包已更新但窗口恢复失败；旧草稿保留，请勿重复提交保存。'};
      status.textContent=labels[data.phase]||data.phase;
      let stop=document.querySelector('#stop-ui-transition');
      if(['COMPLETE','NOT_COMMITTED','COMMITTED_RESTORE_FAILED'].includes(data.phase)){stop?.remove();}
      else{if(!stop){stop=el('button','停止页面切换',document.querySelector('#dock'));stop.id='stop-ui-transition';}stop.onclick=()=>send('packageAction',{action:'patchSwapCancel',operationId:data.operationId}).catch(report);}
    }
    else if(channel==='contentHotSwap'){
      const old=nodes.get(data.oldViewId),next=nodes.get(data.viewId);
      if(!old||!next||old.dataset.packageId!==String(data.packageId)||next.dataset.packageId!==String(data.packageId)||Number(next.dataset.packageRevision)!==Number(data.revision)||next.dataset.takeoverStatus!=='READ_ONLY_READY'){
        send('hotSwapAck',{id:data.id,status:'REJECTED'}).catch(report);
      }else{
        state.replace(data.oldViewId,data.viewId);if(layoutEdited.delete(data.oldViewId))layoutEdited.add(data.viewId);old._observer?.disconnect();old.remove();nodes.delete(data.oldViewId);
        next.dataset.hotSwapSource=data.oldViewId;renderLayout(data.viewId);dock();saveUiState();
        send('hotSwapAck',{id:data.id,status:'SWAPPED'}).catch(report);
        send('viewClosed',{viewId:data.oldViewId}).catch(report);
      }
    }
    else if (channel === 'packageViewOutdated'){
      const node=nodes.get(data.viewId);if(node){node.dataset.outdated='true';const badge=node.querySelector('.badge');if(badge)badge.textContent='旧会话 · 草稿保留';node.querySelector('[data-action="open-delegation"]')?.setAttribute('disabled','');}
      report(new Error('旧页面已停止提交；请从内容库打开当前版本。原窗口草稿仍保留。'));
    }
    else if (channel === 'scoreSources') generations.sources(data);
    else if (channel === 'scoreViews') generations.views(data);
    else if (channel === 'scoreViewPaging') generations.paging(data);
    else if(channel==='deliveryInboxChanged') deliveries.changed();
    else if (channel === 'contentError') report(new Error(`${data.viewId}: ${data.code}`));
    else if (channel === 'deliveryDraftStatus') renderDeliveryDraftStatus(nodes.get(data.viewId),data,send,report);
    else if (channel === 'contentReady'){const node=nodes.get(data.viewId);if(node){node.dataset.pageOnly=String(!!data.pageOnly);node.dataset.actorKind=data.actorKind||'';node.dataset.contentRendered=String(data.rendered===true);}const button=node?.querySelector('[data-action="open-delegation"],[data-action="open-page-delegation"],[data-action="open-world-delegation"]');if(button)button.disabled=!canDelegateView(data);}
    else if (channel === 'uiAgentStatus') uiAgents.status(data);
    else if (channel === 'uiAgentTasks') data.forEach(uiAgents.status);
    else if (channel === 'uiAgentStopped') report(new Error(data.status));
    else if (channel === 'takeoverReady'){
      const node=nodes.get(data.viewId);if(node){node.dataset.takeoverStatus='READ_ONLY_READY';node.querySelector('.badge').textContent='草稿已恢复 · 只读';
        node.querySelector('[data-action="takeover"]')?.setAttribute('hidden','');
        const activate=el('button','启用玩家编辑',node.querySelector('.titlebar'));activate.dataset.action='activate-takeover';const operationId=crypto.randomUUID();
        activate.title='明确开启此新文档的玩家写权限；草稿不会自动提交，仍需点击页面保存';
        activate.onclick=async()=>{activate.disabled=true;try{const r=await send('takeoverUi',{action:'activate',viewId:data.viewId,operationId});if(r.code!=='APPLIED')throw new Error(r.values?.errorCode||r.code);}catch(e){report(e);activate.disabled=false;}};
      }
    }
    else if(channel==='takeoverPending'){const node=nodes.get(data.viewId);if(node){node.dataset.takeoverStatus='RESTORING';node.querySelector('[data-action="activate-takeover"]')?.remove();}}
    else if (channel === 'takeoverActivated'){
      const node=nodes.get(data.viewId);if(node){node.dataset.takeoverStatus='ACTIVE';node.querySelector('.badge').textContent='玩家编辑';node.querySelector('[data-action="activate-takeover"]')?.remove();}
      if(data.oldViewId&&data.oldViewId!==data.viewId)close(data.oldViewId);
    }
    else if (channel === 'setupNotice') {
      const button = document.querySelector('#setup-notice');
      button.hidden = data.trust === 'TRUSTED' && data.initialized;
      button.textContent = data.trust === 'TRUSTED' ? '待设置' : '待授权';
      button.title = `当前信任状态：${data.trust}；不会自动批准。指纹：${data.fingerprint}`;
      reflowWorkspace();
    }
    else if (channel === 'openChat') openChat();
    else if(channel==='nativeAtlasStart'){
      if(!nativeAtlas){const screenWidth=viewportWidth(),screenHeight=viewportHeight();nativeAtlas=createNativeAtlas({doc:document,host:window,screenWidth,screenHeight,physicalWidth:data.physicalWidth,physicalHeight:data.physicalHeight,onViewport:reflowWorkspace,requestSize:request=>send('nativeAtlasResize',request).catch(report),publish:publishPaintLayout});document.documentElement.dataset.nativeAtlas='render-only';for(const id of state.views.keys())renderLayout(id);dock();nativeAtlas.schedule();}
    }
    else if(channel==='nativeAtlasViewport'){nativeAtlas?.resize(data.width,data.height);reflowWorkspace();}
    else if(channel==='nativeAtlasOpacity')nativeAtlas?.setOpacity(data.viewId,data.opacity);
    else if (channel === 'openPackage') openPackage(data);
    else if (channel === 'revealPackage') {if(nodes.has(data.viewId)){state.minimize(data.viewId,false);state.focus(data.viewId);renderLayout(data.viewId);dock();}}
    else if (channel === 'previewAgentStatus') {
      const node=nodes.get(data.viewId); if(node) {
        node.querySelector('.agent-stop')?.remove();
        if(data.running) { const stop=el('button','停止 AI 操作',node.querySelector('.titlebar')); stop.className='agent-stop';
          stop.onclick=()=>send('previewStop',{viewId:data.viewId}).catch(report); }
      }
    }
    else if (channel === 'probe') send('probeReply', { operationId: data.operationId,
      title: document.title, windows: state.views.size, defaultBridgeBlocked: window.__defaultBridgeBlocked === true,
      independentClose: window.__independentClose === true,
      titlebarVisible: document.querySelector('.titlebar').getBoundingClientRect().top >= document.querySelector('#dock').getBoundingClientRect().bottom,
      echo: document.querySelector('#echo-receipt')?.textContent || '',
      controls: [...document.querySelectorAll('button,input,textarea')].map(e => ({ tag: e.tagName, label: e.getAttribute('aria-label') || e.textContent, value: e.value || '' })) }).catch(report);
  } catch (e) { report(e); }
});
document.querySelector('#open-chat').onclick = openChat;
document.querySelector('#open-generation').onclick = generations.open;
document.querySelector('#open-status').onclick = openStatus;
document.querySelector('#release').onclick = () => send('releaseInput').catch(report);
document.querySelector('#setup-notice').onclick = () => send('openSetup').catch(report);
addEventListener('error', event => send('diagnostic', { message: String(event.message).slice(0, 500) }).catch(report));
addEventListener('unhandledrejection', event => report(event.reason));
if(document.body.dataset.standalone!=='true')openChat();
// Router functions may be installed just after document creation. Bounded, no perpetual retry loop.
for (let attempt = 0; attempt < 100; attempt++) {
  try { await send('ready', { bridgeVersion: 1,hostDocumentId:captureHostDocumentId });paintLayoutReady=true;publishPaintLayout();break; }
  catch (e) { if (attempt === 99) report(e); else await new Promise(resolve => setTimeout(resolve, 100)); }
}
