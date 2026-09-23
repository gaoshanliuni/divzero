import {AppearanceDraft} from './appearance-state.mjs';
export function createAppearanceCards({windowFor,send,report,openDecision}){
  let agents=[],epoch=0,draft=null;
  const add=(tag,text,parent)=>{const e=document.createElement(tag);if(text!=null)e.textContent=text;parent.append(e);return e;};
  function fill(){const s=document.querySelector('#appearance-agent');if(!s)return;const old=s.value;s.replaceChildren();for(const a of agents)add('option',a.name,s).value=a.id;if(agents.some(a=>a.id===old))s.value=old;}
  async function load(){const select=document.querySelector('#appearance-agent'),content=document.querySelector('#appearance-fields');if(!select?.value||!content)return;const current=++epoch;draft=new AppearanceDraft(select.value);content.replaceChildren();const status=add('p','读取实际外观…',content);status.setAttribute('role','status');
    try{const r=await send('appearanceAction',{action:'read',agentId:select.value});if(current!==epoch||!content.isConnected)return;if(r.code!=='OBSERVED')throw new Error(r.values?.errorCode||r.code);const state=JSON.parse(r.values.state);draft.observe(state);status.textContent=`${state.diagnostic} · ${state.version||'未安装'}`;
      add('p','只修改选中 AI 的外观；不改变身体、背包、生命或任务。卸载 YSM 需要重启。',content).className='muted';
      const fields={};for(const [key,label] of [['model','模型 ID'],['texture','纹理 ID（留空使用模型默认）'],['animation','动画 ID（留空不额外指定）']]){const l=add('label',label,content);const input=add('input',null,content);input.id='appearance-'+key;l.htmlFor=input.id;input.maxLength=128;input.value=draft[key];fields[key]=input;input.oninput=()=>{if(current===epoch&&content.isConnected)draft.edit(fields.model.value,fields.texture.value,fields.animation.value);};}
      const list=add('datalist',null,content);list.id='appearance-model-list';fields.model.setAttribute('list',list.id);for(const id of state.models)add('option',null,list).value=id;
      add('p',state.catalogTruncated?'目录显示前128项，可填写已授权的准确 ID。':'模型列表来自当前服务器；纹理和动画不伪造可用列表。',content).className='muted';
      const apply=add('button','应用外观',content);apply.id='appearance-apply';apply.disabled=!state.runtimeAvailable;
      apply.onclick=async()=>{apply.disabled=true;try{draft.edit(fields.model.value,fields.texture.value,fields.animation.value);const request=draft.request(()=>crypto.randomUUID());const result=await send('appearanceAction',{action:'apply',...request});if(current!==epoch||!content.isConnected)return;const response=result.values?.state?JSON.parse(result.values.state):null;if(response)draft.accept(response);if(result.code!=='APPLIED'||!response?.accepted)throw new Error(result.values?.errorCode||result.code);status.textContent=`服务端已确认外观`;content.dataset.appearanceRevision=String(response.revision);content.dataset.appearanceAgent=request.agentId;}catch(e){if(current===epoch){status.textContent=`${e.message}；草稿保留，请显式刷新/重试。`;report(e);}}finally{if(current===epoch)apply.disabled=!state.runtimeAvailable;}};
      add('button','重新读取（放弃本页未提交修改）',content).onclick=load;
      const choice=add('button','创建外观选择卡',content);choice.id='appearance-decide';choice.disabled=!state.runtimeAvailable;
      choice.onclick=async()=>{choice.disabled=true;try{draft.edit(fields.model.value,fields.texture.value,fields.animation.value);const r=await send('appearanceAction',{action:'decide',...draft.choiceRequest(()=>crypto.randomUUID())});if(current!==epoch||!content.isConnected)return;if(r.code!=='APPLIED')throw new Error(r.values?.errorCode||r.code);openDecision(JSON.parse(r.values.decision));}catch(e){if(current===epoch)report(e);}finally{if(current===epoch)choice.disabled=!state.runtimeAvailable;}};
    }catch(e){if(current===epoch){status.textContent=e.message;report(e);add('button','重试读取',content).onclick=load;}}
  }
  function open(){const c=windowFor('runtime-appearance','AI 外观');if(c.childElementCount)return;add('label','执行 Agent',c);const s=add('select',null,c);s.id='appearance-agent';s.onchange=load;fill();const button=add('button','读取外观',c);button.id='appearance-read';button.onclick=load;add('div',null,c).id='appearance-fields';if(s.value)load();}
  return {open,agents(values){agents=values;fill();}};
}
