import {t as __uiT,tf as __uiF} from './i18n.mjs';
import {GenerationDraft} from './generation-state.mjs';
import {OperationDraft,agentError} from './agent-management-state.mjs';
export function taskControls(task){
  if(task.status==='RUNNING')return [[__uiT("暂停"),'pause'],[__uiT("取消任务"),'cancel']];
  if(task.status==='PAUSED')return [...(task.actionState==='RESUMABLE_WORLD_WAIT'?[[__uiT("恢复只读世界等待"),'resumeWorldWait']]:[]),...(task.canReplan?[[__uiT("重新规划…"),'replan']]:[]),[__uiT("取消任务"),'cancel']];
  if(task.status==='FAILED'&&task.canReplan)return [[__uiT("重新规划…"),'replan']];
  return [];
}
export function createWorldActionCards({windowFor,send,report}) {
  const draft=new GenerationDraft();let agents=[],tasks=[],replanning=null,epoch=0;
  const add=(tag,text,parent)=>{const e=document.createElement(tag);if(text!=null)e.textContent=text;parent.append(e);return e;};
  function fillAgents(){const select=document.querySelector('#world-task-agent');if(!select)return;const previous=select.value||draft.agentId;select.replaceChildren();for(const a of agents)add('option',a.name,select).value=a.id;if(agents.some(a=>a.id===previous))select.value=previous;}
  function render(){const list=document.querySelector('#world-task-list');if(!list)return;list.replaceChildren();for(const t of tasks){
    const card=add('article',null,list);card.className='generation-job';card.dataset.worldTask=t.taskId;
    add('strong',t.title,card);add('p',`${t.status} · ${t.actionState}${t.total?__uiF(" · 第 {0} 轮已验证 {1}/{2}",t.round,t.cursor,t.total):''}`,card).className='muted';
    if(t.error)add('p',t.error==='AUTHORITY_REVOKED'?__uiT("控制权限已撤销；已停止此任务。重新授权不会恢复旧请求，请新建任务或明确重规划。"):t.error,card).className='error';add('p',`Task ${t.taskId}`,card).className='muted';
    if(t.statusReason)add('p',t.statusReason,card).className='muted';
    if(t.replanOf)add('p',__uiF("基于原任务 {0} 的新规划；原任务未重启。",t.replanOf),card).className='muted';
    for(const [label,control] of taskControls(t)){
      const button=add('button',label,card);button.dataset.taskControl=control;button.onclick=async()=>{if(control==='replan'){openReplan(t);return;}button.disabled=true;try{const r=await send('packageAction',{action:'taskControl',taskId:t.taskId,taskRevision:t.revision,control});if(r.code!=='APPLIED')throw new Error(r.values?.errorCode||r.code);}catch(e){report(e);}finally{button.disabled=false;}};
    }
  }}
  function openReplan(source){
    const content=windowFor('runtime-task-replan',__uiT("明确新建任务并重新规划"));content.replaceChildren();const token=++epoch,operation=new OperationDraft();let requestSent=false;
    replanning={source,content,token};content.dataset.replanSource=source.taskId;
    add('p',__uiT("这会启动一个新的模型规划请求，可能产生费用。原任务与已发生的动作记录保持原样，不恢复旧动作队列。"),content).className='warning';
    add('strong',__uiF("原目标：{0}",source.title),content);add('p',__uiF("原任务 {0} · {1} / {2} · {3}",source.taskId,source.status,source.actionState,source.error||__uiT("无额外诊断")),content).className='muted';
    add('label',__uiT("当前希望完成的目标（最多 256 字）"),content);const goal=add('textarea',null,content);goal.id='task-replan-goal';goal.maxLength=512;goal.value=source.title;
    add('label',__uiT("补充说明（可留空）"),content);const note=add('textarea',null,content);note.id='task-replan-note';note.maxLength=2048;note.placeholder=__uiT("例如：保留已经完成的部分，先检查当前世界状态。");
    const footer=add('div',null,content);footer.className='replan-controls';
    const consent=add('label',null,footer);consent.className='agent-consent';const check=add('input',null,consent);check.type='checkbox';check.id='task-replan-consent';add('span',__uiT("我确认重新观察当前世界，并新发起一次可能计费的规划请求"),consent);
    const result=add('p',__uiT("未确认前不会发起请求。"),footer);result.setAttribute('role','status');const buttons=add('div',null,footer);buttons.className='actions';const submit=add('button',__uiT("确认新建并重新规划"),buttons);submit.id='task-replan-submit';submit.disabled=true;
    function changed(){operation.reset();check.checked=false;submit.disabled=true;}
    goal.oninput=note.oninput=changed;check.onchange=()=>{submit.disabled=!check.checked;};
    submit.onclick=async()=>{
      if(!check.checked||token!==epoch)return;
      const current=tasks.find(t=>t.taskId===source.taskId);if(!current?.canReplan||current.revision!==source.revision){result.textContent=agentError('STALE_TASK_REVISION');result.className='error';check.checked=false;submit.disabled=true;return;}
      if(!goal.value.trim()||[...goal.value.trim()].length>256){result.textContent=__uiT("请填写 1–256 字的目标。");result.className='error';return;}
      const request=operation.request({action:'taskReplan',sourceTaskId:source.taskId,sourceRevision:source.revision,goal:goal.value.trim(),note:note.value.trim(),confirmed:true},()=>crypto.randomUUID());requestSent=true;submit.disabled=true;goal.disabled=note.disabled=check.disabled=true;result.textContent=__uiT("正在受理新规划…");
      try{const r=await send('packageAction',request);if(token!==epoch||!content.isConnected)return;if(r.code!=='ACCEPTED')throw new Error(r.values?.errorCode||r.code);result.textContent=__uiF("新任务已受理：{0}。当前结果以任务列表为准，不能当作目标已完成。",r.values.taskId);result.className='success';check.checked=false;}
      catch(e){if(token===epoch&&content.isConnected){result.textContent=agentError(e.message)+__uiT("；请先查看任务列表确认结果，不要重复发起新请求。");result.className='error';}}
      finally{if(token===epoch){goal.disabled=note.disabled=check.disabled=false;submit.disabled=true;check.checked=false;}}
    };
    const cancel=add('button',__uiT("关闭确认"),buttons);cancel.onclick=()=>{epoch++;replanning=null;content.replaceChildren();add('p',requestSent?__uiT("已关闭说明。请求可能已受理，请在任务列表查看或取消新任务；关窗不会取消模型请求。"):__uiT("已取消确认，没有新增规划请求。"),content);};
  }
  function open(){const content=windowFor('runtime-world-tasks',__uiT("Agent 任务与实际动作"));if(content.childElementCount){render();return;}
    add('p',__uiT("使用配置模型规划，消耗额度。按实际 Actor 的位置、工具和背包顺序执行；开始移动/挖掘不代表完成。失败或未知结果不会自动重放整批原生动作。"),content).className='muted';
    add('p',__uiT("世界代码生成不等于实例已运行。发布后请在“内容生成”面板检查包并明确确认原生启用；任务会等待实际实例与原生目标检查。"),content).className='muted';
    add('label',__uiT("执行 Agent"),content);const agent=add('select',null,content);agent.id='world-task-agent';fillAgents();
    add('label',__uiT("任务（最多 256 字）"),content);const input=add('textarea',null,content);input.id='world-task-prompt';input.maxLength=512;input.value=draft.prompt;
    input.oninput=agent.onchange=()=>draft.edit(agent.value,input.value);
    const start=add('button',__uiT("启动通用 Agent 任务"),content);start.id='world-task-start';
    start.onclick=async()=>{start.disabled=true;try{if([...input.value].length>256)throw new Error('TASK_PROMPT_TOO_LONG');draft.edit(agent.value,input.value);const request=draft.request(()=>crypto.randomUUID());const r=await send('packageAction',{action:'taskStart',scope:'GENERAL',...request});if(r.code!=='ACCEPTED')throw new Error(r.values?.errorCode||r.code);add('p',__uiF("任务已排队：{0}，尚未完成动作。",r.values.taskId),content);}catch(e){report(e);}finally{start.disabled=false;}};
    add('button',__uiT("明确作为新任务提交"),content).onclick=()=>{draft.operationId=null;start.click();};
    add('div',null,content).id='world-task-list';render();
  }
  return {open,agents(values){agents=values;fillAgents();},update(values){tasks=values;render();if(replanning){const current=tasks.find(t=>t.taskId===replanning.source.taskId);if(!current?.canReplan||current.revision!==replanning.source.revision){const check=replanning.content.querySelector('#task-replan-consent');if(check)check.checked=false;const submit=replanning.content.querySelector('#task-replan-submit');if(submit)submit.disabled=true;}}},session(){epoch++;replanning=null;const check=document.querySelector('#task-replan-consent');if(check){check.checked=false;check.disabled=true;}const submit=document.querySelector('#task-replan-submit');if(submit)submit.disabled=true;}};
}
