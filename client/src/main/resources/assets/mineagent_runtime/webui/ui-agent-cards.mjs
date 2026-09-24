import {t as __uiT,tf as __uiF} from './i18n.mjs';
import { UiDelegationDraft,WorldUiDelegationDraft } from './ui-delegation-state.mjs';
export function createUiAgentCards({windowFor,send,report}){
  let agents=[];const drafts=new Map();
  const el=(tag,text,parent)=>{const n=document.createElement(tag);if(text!=null)n.textContent=text;parent?.append(n);return n;};
  function open(viewId,pageOnly=false){
    const content=windowFor(`delegate-${viewId}`,__uiT("授权 Agent 操作此窗口"));if(content.childElementCount)return;
    const draft=drafts.get(viewId)||new UiDelegationDraft();drafts.set(viewId,draft);content.dataset.delegationView=viewId;
    el('p',pageOnly?__uiT("仅委派此已加载页面的 DOM/截图操作，不授予计分、容器、OP 或授权卡权限。完成只表示页面条件已满足，不代表世界业务结果。"):__uiT("仅委派当前 ScoreView 布局。Agent 不继承你的 OP、背包或授权卡权限。当前以期望标题作独立 UI/业务验证。"),content);
    const agent=el('select',null,content);agent.dataset.field='agent';agent.setAttribute('aria-label',__uiT("执行 Agent"));
    for(const a of agents){const option=el('option',a.name,agent);option.value=a.id;}if(agents.some(a=>a.id===draft.agentId))agent.value=draft.agentId;
    el('label',__uiT("操作要求"),content);const goal=el('textarea',null,content);goal.dataset.field='goal';goal.value=draft.goal;goal.maxLength=8192;
    el('label',pageOnly?__uiT("完成后新出现的页面文本（空白归一化）"):__uiT("期望标题（独立验证）"),content);const title=el('input',null,content);title.dataset.field='expectedTitle';title.value=draft.expectedTitle;title.maxLength=256;
    let presentation=null;if(pageOnly){const label=el('label',null,content);presentation=el('input',null,label);presentation.type='checkbox';presentation.dataset.field='presentationOnly';label.append(document.createTextNode(__uiT("只调整此窗口位置/尺寸及支持的 Native 透明度：不点击页面内容，模型仅接收窗口信息；无需填写期望页面文本。")));}
    const consent=el('label',null,content);const check=el('input',null,consent);check.type='checkbox';check.dataset.field='consent';check.checked=false;
    consent.append(document.createTextNode(pageOnly?__uiT("我授权所选 Agent 操作此页面；不授权任何世界写入或权限批准。原生输入或停止按钮会中断，结束后需重新打开新文档才能再次委派。"):__uiT("我授权所选 Agent 操作此窗口的布局。原生输入或停止按钮会终止操作，保留草稿；结束后此文档不自动恢复玩家写权限。")));
    const sync=()=>{const only=presentation?.checked===true;title.disabled=only;draft.edit(agent.value,goal.value,only?'HOST_PRESENTATION':title.value,check.checked,only);};for(const n of [agent,goal,title,check,...(presentation?[presentation]:[])]){n.addEventListener('input',sync);n.addEventListener('change',sync);}
    const result=el('p',__uiT("尚未授权"),content);result.setAttribute('role','status');
    const submit=el('button',__uiT("确认委派并开始"),content);submit.dataset.action='delegate';
    submit.onclick=async()=>{submit.disabled=true;try{sync();const r=await send('delegateUi',{viewId,...draft.request(()=>crypto.randomUUID())});
      if(r.code!=='ACCEPTED')throw new Error(r.values?.errorCode||r.code);result.textContent=`Task ${r.values.taskId} · ${r.values.status}`;
      content.closest('.window').querySelector('button[data-action="minimize-window"]').click();
    }catch(e){result.textContent=e.message;report(e);}finally{submit.disabled=false;}};
  }
  function status(data){
    const frame=[...document.querySelectorAll('iframe')].find(f=>f.name===data.viewId);const host=frame?.closest('.window');if(!host)return;
    host.dataset.agentTask=data.taskId;host.dataset.agentStatus=data.status;
    let label=host.querySelector('.agent-task-status');if(!label){label=el('span','',host.querySelector('.titlebar'));label.className='agent-task-status';}label.textContent=data.status;
  }
  function openWorld(viewId){
    const content=windowFor(`world-delegate-${viewId}`,__uiT("由 Agent 自身操作物件界面"));if(content.childElementCount)return;
    const draft=drafts.get(`world:${viewId}`)||new WorldUiDelegationDraft();drafts.set(`world:${viewId}`,draft);content.dataset.worldDelegationView=viewId;
    el('p',__uiT("Agent 必须已在物件的实际交互距离内且可见，系统不会传送它。用它自己的 Native 交互取得界面，重新加载独立文档，不复制玩家私有页面、草稿或 OP 权限。"),content).className='muted';
    const agent=el('select',null,content);agent.dataset.field='agent';agent.setAttribute('aria-label',__uiT("执行 Agent"));for(const a of agents){const option=el('option',a.name,agent);option.value=a.id;}if(agents.some(a=>a.id===draft.agentId))agent.value=draft.agentId;
    el('label',__uiT("操作目标"),content);const goal=el('textarea',null,content);goal.dataset.field='goal';goal.maxLength=8192;goal.value=draft.goal;
    el('label',__uiT("服务端投影与页面文本条件（JSON）"),content);const expected=el('textarea',null,content);expected.dataset.field='expected';expected.maxLength=8192;expected.value=draft.expected;expected.placeholder='{"equals":{"/enabled":false},"text":"已停止"}';
    el('p',__uiT("equals 使用 JSON Pointer 与期望标量，text 是页面应出现的文字。条件不产生权限；必须有本任务的真实页面 action 回执和服务端投影同时匹配。"),content).className='muted';
    const label=el('label',null,content),consent=el('input',null,label);consent.type='checkbox';consent.dataset.field='consent';label.append(document.createTextNode(__uiT("明确让所选 Agent 执行 Native 交互与本次页面操作；停止后不自动恢复玩家写权限")));
    const sync=()=>draft.edit(agent.value,goal.value,expected.value,consent.checked);for(const n of [agent,goal,expected,consent]){n.addEventListener('input',sync);n.addEventListener('change',sync);}
    const result=el('p',__uiT("尚未委派"),content);result.setAttribute('role','status');const start=el('button',__uiT("确认 Agent 物件任务"),content);start.dataset.action='delegate-world';
    start.onclick=async()=>{start.disabled=true;try{sync();const r=await send('delegateWorldUi',{viewId,...draft.request(()=>crypto.randomUUID())});if(r.code!=='ACCEPTED')throw new Error(r.values?.errorCode||r.code);result.textContent=__uiF("Task {0} · 新 Agent 文档 {1}",r.values.taskId,r.values.viewId);content.closest('.window').querySelector('button[data-action="minimize-window"]').click();}catch(e){result.textContent=e.message;report(e);}finally{start.disabled=false;}};
  }
  return {open,openWorld,status,agents(value){agents=value;}};
}
