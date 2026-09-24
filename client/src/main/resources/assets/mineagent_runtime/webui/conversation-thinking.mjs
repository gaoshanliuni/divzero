import {t as __uiT,tf as __uiF} from './i18n.mjs';
/** Separate, lazily paged Provider reasoning. Never render model output as HTML. */
export function createThinkingHistory({request,current,notice}){
 const expanded=new Map(),controllers=new WeakMap();
 return {clear(){expanded.clear();},decorate(row,meta,stamp){
  if(!meta?.textLength)return;
  const old=controllers.get(row);if(old&&old.details.parentNode===row){old.update(meta);return;}
  const details=document.createElement('details');details.className='conversation-thinking';details.dataset.messageId=meta.messageId;
  const summary=document.createElement('summary');summary.textContent=meta.active?__uiT("思考中…"):__uiT("思考过程");details.append(summary);
  const body=document.createElement('pre');body.textContent=__uiT("展开查看");details.append(body);
  const more=document.createElement('button');more.textContent=__uiT("加载更多思考");more.hidden=true;details.append(more);row.append(details);
  let busy=false,offset=0,text='';
  async function read(target){if(busy)return;busy=true;more.disabled=true;
   try{while(offset<Math.min(target,meta.textLength)){
    if(!current(stamp)||!details.isConnected||!details.open)return;
    const r=await request('thinking',{agentId:stamp.agentId,conversationId:stamp.conversationId,messageId:meta.messageId,messageRevision:meta.revision,offset});
    if(!current(stamp)||!details.isConnected||!details.open)return;
    const c=r.state;if(c.revision!==meta.revision||c.offset!==offset||typeof c.text!=='string'||!c.text.length)throw new Error('CONVERSATION_THINKING_CHUNK');
    text+=c.text;offset+=c.text.length;body.textContent=text;
   }}catch(e){if(current(stamp)&&details.isConnected){body.textContent=text||__uiT("思考内容已更新，请重新展开");notice(e.message);}}
   finally{busy=false;more.disabled=false;more.hidden=offset>=meta.textLength;}
  }
  details.addEventListener('toggle',()=>{if(!details.isConnected||!current(stamp))return;if(details.open){const target=expanded.get(meta.messageId)||4096;expanded.set(meta.messageId,target);read(target);}else expanded.delete(meta.messageId);});
  more.onclick=()=>{expanded.set(meta.messageId,offset+4096);read(offset+4096);};
  // Preserve an explicit expansion across stream refreshes, not across conversation selections.
  controllers.set(row,{details,update(next){if(next.revision===meta.revision&&next.active===meta.active)return;meta=next;summary.textContent=meta.active?__uiT("思考中…"):__uiT("思考过程");if(offset>meta.textLength){offset=0;text='';}more.hidden=offset>=meta.textLength;if(details.open)void read(expanded.get(meta.messageId)||4096);}});
  details.open=expanded.has(meta.messageId);
 }};
}
