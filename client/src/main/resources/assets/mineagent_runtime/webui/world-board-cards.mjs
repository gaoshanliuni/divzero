import {t as __uiT,tf as __uiF} from './i18n.mjs';
import {WorldBoardDraft} from './world-board-state.mjs';
export function createWorldBoardCards({send,report}){
  const drafts=new Map();
  const el=(tag,text,parent)=>{const n=document.createElement(tag);if(text!=null)n.textContent=text;parent?.append(n);return n;};
  return {render(parent,job,revision,view){
    if(!drafts.has(view.id)){if(drafts.size>=64)drafts.delete(drafts.keys().next().value);drafts.set(view.id,new WorldBoardDraft());}
    const draft=drafts.get(view.id);draft.update(view);
    const box=el('details',null,parent);box.className='world-board-controls';box.dataset.worldViewId=view.id;
    el('summary',__uiF("世界文字看板 · {0}",view.kind==='WORLD_BOARD'?__uiT("已投射"):__uiT("未投射")),box);
    el('p',__uiT("复用本视图的同一计分源与版式。按既有受众定向显示；关闭网页不停止世界展示。"),box).className='muted';
    const grid=el('div',null,box);grid.className='world-coordinates';
    for(const key of ['dimension','x','y','z','yaw','scale','viewDistance']){const label=el('label',key,grid);const input=el('input',null,label);input.type=key==='dimension'?'text':'number';input.step='any';input.value=String(draft.fields[key]??0);input.dataset.worldField=key;input.oninput=()=>draft.edit(key,input.value);}
    const actions=el('div',null,box);actions.className='actions';
    for(const [action,title] of [['worldFront',__uiT("投射到我前方")],['worldMove',__uiT("应用位置/旋转/缩放")],['worldDetach',__uiT("移除世界投射")],['worldDelete',__uiT("删除此视图（保留分数）")]]){
      const button=el('button',title,actions);button.dataset.worldAction=action;
      button.onclick=async()=>{button.disabled=true;try{
        const args=draft.request(action,()=>crypto.randomUUID());
        const r=await send('packageAction',{action,packageId:job.packageId,packageRevision:revision,targetViewId:view.id,...args});
        if(r.code!=='APPLIED')throw new Error(r.values?.errorCode||r.code);drafts.delete(view.id);el('p',r.values.state||__uiT("已应用"),box).setAttribute('role','status');
      }catch(e){report(e);}finally{button.disabled=false;}};
    }
  }};
}
