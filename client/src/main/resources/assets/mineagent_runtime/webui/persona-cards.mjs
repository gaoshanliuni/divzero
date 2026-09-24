import {t as __uiT,tf as __uiF} from './i18n.mjs';
import {PersonaDraft,editablePersonaAgents} from './persona-state.mjs';
export function createPersonaCards({windowFor,send,report,persist}){
  let agents=[],epoch=0;const drafts=new Map();
  const add=(tag,text,parent)=>{const n=document.createElement(tag);if(text!==null)n.textContent=text;parent?.append(n);return n;};
  function fill(){const s=document.querySelector('#persona-agent');if(!s)return;const old=s.value;s.replaceChildren();for(const a of agents)add('option',a.name,s).value=a.id;if(old)s.value=agents.some(a=>a.id===old)?old:'';}
  async function load(discard=false){
    const agent=document.querySelector('#persona-agent')?.value,fields=document.querySelector('#persona-fields');if(!fields)return;if(!agent){++epoch;fields.replaceChildren();add('p',__uiT("请选择有编辑权限的 AI。"),fields);return;}
    const turn=++epoch;let draft=discard?new PersonaDraft(agent):drafts.get(agent)||new PersonaDraft(agent);drafts.set(agent,draft);fields.replaceChildren();
    const status=add('p',__uiT("读取人设…"),fields);status.id='persona-status';status.setAttribute('role','status');
    try{const r=await send('personaAction',{action:'read',agentId:agent});if(turn!==epoch||!fields.isConnected)return;if(r.code!=='OBSERVED')throw new Error(r.values?.errorCode||r.code);
      const saved=JSON.parse(r.values.state);draft.observe(saved);status.textContent=__uiF("已保存 r{0}{1}",saved.revision,draft.dirty?__uiT(" · 本地草稿未保存"):'');
      const note=add('p',__uiT("同一 AI 的所有会话使用其当前人设。只影响后续回复，不授予权限、不修改历史或世界任务。"),fields);note.className='muted';
      const label=add('label',__uiT("自由人设 · 身份 / 背景 / 性格 / 说话风格 / 与玩家的关系 / 角色扮演要求"),fields);label.htmlFor='persona-text';
      const input=add('textarea',null,fields);input.id='persona-text';input.maxLength=8192;input.rows=5;input.value=draft.text;input.placeholder=__uiT("留空使用默认 AI 玩家身份；最多 8192 个字符。");
      input.oninput=()=>{draft.edit(input.value);persist();};const actions=add('div',null,fields);actions.className='actions';
      const save=add('button',__uiT("保存人设"),actions);save.id='persona-save';const reset=add('button',__uiT("恢复默认"),actions);reset.id='persona-reset';const refresh=add('button',__uiT("重新读取（放弃该 AI 草稿）"),actions);refresh.id='persona-refresh';
      refresh.onclick=()=>{load(true);persist();};
      async function commit(defaults){save.disabled=reset.disabled=refresh.disabled=true;try{
        draft.edit(input.value);const request=draft.request(()=>crypto.randomUUID(),defaults);persist();
        const r=await send('personaAction',{action:'save',...request});const result=r.values?.state?JSON.parse(r.values.state):null;
        if(turn!==epoch||drafts.get(agent)!==draft||!fields.isConnected)return;
        const accepted=draft.accept(request,result);if(r.code!=='APPLIED'||!accepted)throw new Error(result?.accepted?'PERSONA_CHANGED_SINCE_APPLY':r.values?.errorCode||r.code);
        input.value=draft.text;status.textContent=__uiF("已保存 r{0}{1}",draft.revision,draft.dirty?__uiT(" · 仍有新草稿未保存"):defaults?__uiT(" · 默认 AI 玩家身份"):'');fields.dataset.personaRevision=String(draft.revision);fields.dataset.personaAgent=agent;persist();
      }catch(e){if(turn===epoch){status.textContent=__uiF("{0}；草稿保留，请核对后重新读取。",e.message);report(e);}}finally{if(turn===epoch)save.disabled=reset.disabled=refresh.disabled=false;}}
      save.onclick=()=>commit(false);reset.onclick=()=>commit(true);
    }catch(e){if(turn===epoch){status.textContent=e.message;report(e);}}
  }
  function open(){const c=windowFor('persona',__uiT("AI 人设"));if(c.childElementCount)return;add('p',__uiT("每个 AI 独立保存，所有者及授权协作者可编辑。"),c);const s=add('select',null,c);s.id='persona-agent';s.setAttribute('aria-label',__uiT("人设所属 AI"));s.onchange=()=>load();add('div',null,c).id='persona-fields';fill();load();}
  return {open,agents(value){const before=document.querySelector('#persona-agent')?.value;agents=editablePersonaAgents(value);fill();const after=document.querySelector('#persona-agent')?.value;if(before!==after)load();},snapshot(){return Object.fromEntries([...drafts].map(([id,d])=>[id,d.snapshot()]));},restore(values){if(!values||typeof values!=='object'||Array.isArray(values)||Object.keys(values).length>16)return;for(const [id,v]of Object.entries(values))if(/^[a-f\d-]{36}$/i.test(id)&&!drafts.get(id)?.dirty)drafts.set(id,PersonaDraft.restore(id,v));if(document.querySelector('#persona-fields'))load();}};
}
