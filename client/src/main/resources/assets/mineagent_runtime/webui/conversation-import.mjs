const labels={VERIFIED:'身份已核验，可预览导入',IDENTITY_UNVERIFIED:'缺少可核验的 Agent/会话身份，不能导入',SOURCE_IDENTITY_CONFLICT:'来源身份冲突，拒绝导入',INVALID_SOURCE_ID:'旧记录缺少有效会话 ID',NO_RECORDS:'没有仍可读取的审计消息',NO_AUDIT_STORE:'没有旧审计存储',READY:'等待下一批',PAUSED_ARCHIVE:'请先把目标会话重新归档',COMPLETE:'当前可用记录已处理',INCOMPLETE:'来源已有缺失，仅保留已导入片段',BLOCKED:'来源或目标条件不满足，已停止'};
const errorText=code=>({AUDIT_IDENTITY_UNVERIFIED:labels.IDENTITY_UNVERIFIED,AUDIT_PREVIEW_CHANGED:'来源或身份版本已变化，请重新预览，不自动导入。',AUDIT_ARCHIVE_NOT_ARCHIVED:'目标已被恢复、删除或续聊，不覆盖现有会话。',AUDIT_SOURCE_CHANGED:'来源内容与已保存证据冲突，未重复导入。',AUDIT_CONFIRM_REQUIRED:'请先确认审计片段与归档说明。',AUDIT_SOURCE_UNSUPPORTED:'审计消息为空、过大或时间无效，未截断导入。',AUDIT_SOURCE_LOST:'部分源记录已过期或缺失，已导入片段保留，不能标为完整恢复。',AUDIT_ARCHIVE_HAS_NEW_MESSAGES:'目标已经用于新的聊天，不再把旧审计混入该会话。'})[code]||code;

export function createConversationImporter({request,agentId,openConversation}){
  let root=null,epoch=0,running=false,busy=false,cursor=0,jobCursor=0;const jobs=new Map();
  const add=(tag,text,parent)=>{const node=document.createElement(tag);if(text!=null)node.textContent=text;parent.append(node);return node;};
  const button=(text,parent,action)=>{const node=add('button',text,parent);node.type='button';node.onclick=action;return node;};
  const status=(text,error=false)=>{const node=root?.querySelector('.audit-status');if(node){node.textContent=text;node.className='audit-status '+(error?'warning':'muted');}};
  const current=(token,agent)=>token===epoch&&root?.isConnected&&agent===agentId();
  const visible=()=>root?.isConnected&&root.open&&(!root.parentElement.closest('details')||root.parentElement.closest('details').open);
  function pause(){running=false;epoch++;busy=false;status('已暂停后续批次。已经送达服务端的一批可能已提交；继续前会读取持久游标。');}
  function renderJobs(){const area=root?.querySelector('.audit-jobs');if(!area)return;area.replaceChildren();for(const job of jobs.values()){
    const card=add('div',null,area);card.className='settings-group';add('p',`${labels[job.state]||job.state} · 已处理 ${job.seenRecords} / ${job.expectedRecords} 条 · 本次新增 ${job.importedRecords} 条${job.errorCode?' · '+errorText(job.errorCode):''}`,card);
    add('p',`源会话 ${job.sourceConversationId} · 游标 ${job.cursor} / ${job.upperSequence}`,card).className='muted';
    if(['READY','PAUSED_ARCHIVE'].includes(job.state)){const next=button('继续导入',card,()=>run(job.jobId));next.disabled=running;}
    button('打开归档片段',card,()=>{pause();openConversation(job.conversationId);});
  }}
  async function refreshJobs(more=false){const token=epoch,agent=agentId();try{const r=await request('auditJobs',{agentId:agent,before:more?jobCursor:0});if(!current(token,agent))return;if(!more)jobs.clear();for(const job of r.state.jobs)jobs.set(job.jobId,job);jobCursor=r.state.jobs.at(-1)?.upperSequence||0;root.querySelector('.audit-jobs-more').disabled=r.state.jobs.length<20;renderJobs();}catch(e){if(current(token,agent))status(errorText(e.message),true);}}
  async function run(jobId){if(running||busy)return;running=true;busy=true;const token=epoch,agent=agentId();renderJobs();
    try{let job=(await request('auditJob',{agentId:agent,jobId})).state;
      while(current(token,agent)&&visible()&&running&&['READY','PAUSED_ARCHIVE'].includes(job.state)){
        if(job.state==='PAUSED_ARCHIVE')status('正在重新核对目标归档条件…');
        const r=await request('auditStep',{agentId:agent,jobId,cursor:job.cursor,requestId:crypto.randomUUID()});
        if(!current(token,agent))return;const previous=job;job=r.state;jobs.set(job.jobId,job);renderJobs();status(`${labels[job.state]||job.state} · ${job.seenRecords} / ${job.expectedRecords}。本流程不会调用模型。`);
        if(job.state==='PAUSED_ARCHIVE'||job.cursor===previous.cursor&&job.state==='READY')break;
        await new Promise(resolve=>setTimeout(resolve,200));
      }
      if(current(token,agent)&&job.state==='COMPLETE')status('当前仍存在的审计记录已处理。来源可能早已脱敏、截断或过期，不能视为完整聊天恢复。');
    }catch(e){if(current(token,agent))status(errorText(e.message)+' 结果未知时请刷新导入记录；不会自动重发。',true);}
    finally{if(current(token,agent)){running=false;busy=false;renderJobs();}}
  }
  function candidate(preview,area){const card=add('div',null,area);card.className='settings-group';add('p',`${preview.sourceConversationId||'无法寻址的旧记录'} · ${labels[preview.state]||preview.state}`,card);
    if(preview.availableRecords!=null)add('p',`可见审计记录 ${preview.availableRecords} 条 · ${preview.identityKind||'未取得服务端身份依据'}`,card).className='muted';
    if(preview.state!=='VERIFIED')return;
    const label=add('label',null,card),confirm=add('input',null,label);confirm.type='checkbox';label.className='settings-check';add('span','我确认导入到独立归档会话；这只是审计片段，不自动续聊或执行旧动作。',label);
    const start=button('导入已核验记录',card,async()=>{if(!confirm.checked){status('请先确认导入说明。',true);return;}if(running||busy)return;busy=true;start.disabled=true;const token=epoch,agent=agentId();
      try{const r=await request('auditStart',{agentId:agent,sourceConversationId:preview.sourceConversationId,upperSequence:preview.upperSequence,availableRecords:preview.availableRecords,identityHash:preview.identityHash,confirmed:true,requestId:crypto.randomUUID()});if(!current(token,agent))return;jobs.set(r.state.jobId,r.state);busy=false;renderJobs();await run(r.state.jobId);}
      catch(e){if(current(token,agent))status(errorText(e.message)+' 不自动重试；可以先刷新导入记录。',true);}finally{if(current(token,agent)){busy=false;start.disabled=false;}}
    });
  }
  async function scan(more=false){if(busy)return;busy=true;const token=epoch,agent=agentId();status('读取本人旧审计目标并核验身份，不读取其他玩家的正文…');
    try{const r=await request('auditCandidates',{agentId:agent,before:more?cursor:0});if(!current(token,agent))return;const area=root.querySelector('.audit-candidates');if(!more)area.replaceChildren();for(const row of r.state.candidates)candidate(row,area);cursor=r.state.nextBefore;root.querySelector('.audit-more').disabled=!cursor;status(!r.state.auditAvailable?'没有旧审计存储。':r.state.candidates.length?'未核验身份的记录不会导入；不支持手工猜测 Agent 对应关系。':'当前页没有本人旧 prompt 记录；不表示所有历史都已完整恢复。');}
    catch(e){if(current(token,agent))status(errorText(e.message),true);}finally{if(current(token,agent))busy=false;}
  }
  function mount(parent){epoch++;running=false;busy=false;jobs.clear();cursor=0;jobCursor=0;root=add('details',null,parent);root.className='settings-group';root.id='conversation-audit-import';add('summary','旧审计片段导入（需可核验身份）',root);
    add('p','仅接受服务端现存会话身份；没有 Agent 归属不能猜测恢复。分批导入保留审计证据副本，不覆盖原会话；关闭/切换会暂停后续批次，已提交批次不回滚。',root).className='muted';
    const controls=add('div',null,root);controls.className='actions';button('查找本人旧记录',controls,()=>scan());button('暂停后续批次',controls,pause);button('刷新已有导入',controls,()=>refreshJobs());
    add('p','请先查找记录或读取以前的导入进度。',root).className='audit-status muted';add('div',null,root).className='audit-candidates';const more=button('更早的旧记录',root,()=>scan(true));more.className='audit-more';more.disabled=true;
    const label=add('label','或核验已知的源会话 UUID（不是手工身份映射）',root),source=add('input',null,label);source.maxLength=36;
    button('核验指定会话',root,async()=>{if(!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(source.value.trim())){status('请输入有效会话 UUID。',true);return;}const token=epoch,agent=agentId();try{const r=await request('auditPreview',{agentId:agent,sourceConversationId:source.value.trim()});if(current(token,agent))candidate(r.state,root.querySelector('.audit-candidates'));}catch(e){if(current(token,agent))status(errorText(e.message),true);}});
    add('h4','已有导入与持久进度',root);add('div',null,root).className='audit-jobs';const older=button('更早的导入',root,()=>refreshJobs(true));older.className='audit-jobs-more';older.disabled=true;
    root.addEventListener('toggle',()=>{if(!root.open)pause();});
  }
  return {mount,pause,reset(){pause();jobs.clear();cursor=0;jobCursor=0;root?.querySelector('.audit-candidates')?.replaceChildren();renderJobs();}};
}
