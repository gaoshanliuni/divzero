const serverLabels={REQUESTED:'服务端已接受，等待合成',AUDIO_READY:'音频已生成并验 hash',TRANSFER_SENT:'分片已发送（不是播放证明）',FAILED:'合成或发送失败',CANCELLED:'请求已取消',INTERRUPTED:'上次请求已中断'};
const localLabels={REQUESTED:'等待音频',RECEIVING:'正在接收',QUEUED:'等待音频设备',PLAYING:'正在向音频设备提交',FINISHED:'设备播放流程结束',STOPPED:'已停止',FAILED:'接收/设备失败'};
export function createConversationVoice({send,request,getFocus,getSelected,report}){
  let root=null,epoch=0,actionGeneration=0,busy=false,polling=false,auto=false,watermark=Infinity,enabled=true;const operations=new Map(),local=new Map(),rows=new Map(),cancelled=new Set();let serverJobs=[];
  const current=(token,focus)=>token===epoch&&root?.isConnected&&getFocus()?.contextId===focus.contextId;
  const add=(tag,text,parent)=>{const n=document.createElement(tag);if(text!=null)n.textContent=text;parent.append(n);return n;};
  const button=(text,parent,action)=>{const n=add('button',text,parent);n.type='button';n.onclick=action;return n;};
  function status(text){const n=root?.querySelector('.voice-status');if(n)n.textContent=text;}
  const control=(focus,action,operationId)=>send('conversationAudioControl',{contextId:focus.contextId,action,...(operationId?{operationId}:{})});
  async function cancelServer(focus,operation){if(cancelled.has(operation))return;cancelled.add(operation);try{await request('voiceCancel',{...focus,targetOperation:operation,requestId:crypto.randomUUID()});}catch{/* Unknown or finished synthesis is never retried by cancellation. */}}
  async function stop(disableAuto=true){const focus=getFocus(),token=epoch;if(disableAuto){actionGeneration++;busy=false;auto=false;const checkbox=root?.querySelector('.voice-auto');if(checkbox)checkbox.checked=false;}if(!focus)return;
    const actionToken=actionGeneration,targets=[...operations.entries()].filter(([,value])=>value.focus.contextId===focus.contextId);
    await send('conversationAudioControl',{contextId:focus.contextId,action:'stop',operationIds:targets.map(([id])=>id)}).catch(()=>{});await Promise.all(targets.map(([operation,value])=>cancelServer(value.focus,operation)));
    if(!current(token,focus)||actionToken!==actionGeneration)return;status(disableAuto?'已停止本地播放及后续自动朗读。已发出的 TTS 请求可能已被处理，不承诺撤销费用。':'已停止上一段朗读；不会重放旧合成请求。');await poll();
  }
  async function speak(message,automatic=false){const focus=getFocus();if(!focus||busy||!enabled)return;busy=true;const token=epoch,actionToken=++actionGeneration;const active=()=>current(token,focus)&&actionToken===actionGeneration;
    try{
      await stop(false);if(!active())return;
      const operation=crypto.randomUUID();operations.set(operation,{focus,messageId:message.messageId});
      while(operations.size>32){const id=operations.keys().next().value;operations.delete(id);cancelled.delete(id);}
      await control(focus,'prepare',operation);if(!active())return;
      const r=await request('voice',{...focus,messageId:message.messageId,requestId:operation});if(!active())return;
      status((automatic?'自动朗读：':'')+(r.state.status==='VOICE_ALREADY_REQUESTED'?'原请求已记录，不会重新合成。':'朗读请求已接受，等待实际接收与设备状态。'));
    }catch(e){if(active()){await stop(false);if(active())status(e.message+'；不会自动重新合成。');}}
    finally{if(active()){busy=false;draw();}}
  }
  function draw(){for(const [id,row] of rows){if(!row.node.isConnected){rows.delete(id);continue;}const own=[...operations.entries()].filter(([,v])=>v.messageId===id&&v.focus.contextId===getFocus()?.contextId).at(-1);const receipt=own?local.get(own[0]):null;const server=serverJobs.find(j=>j.messageId===id);
    row.read.disabled=busy||!getFocus()||!enabled;row.replay.disabled=busy||!enabled||!receipt?.cached;row.operation=own?.[0];
    row.state.textContent=[server?(serverLabels[server.state]||server.state)+(server.errorCode?' · '+server.errorCode:''):'尚无已保存的合成状态',receipt?(localLabels[receipt.state]||receipt.state)+(receipt.errorCode?' · '+receipt.errorCode:''):'本次选择尚无音频接收记录',receipt?.cached?'当前会话内存缓存可重播':''].filter(Boolean).join(' · ');
  }}
  async function poll(){const focus=getFocus();if(polling||!focus||!root?.isConnected)return;polling=true;const token=epoch;
    try{const r=await control(focus,'state');if(!current(token,focus))return;local.clear();for(const entry of r.entries||[]){local.set(entry.operationId,entry);if(entry.state==='FAILED')cancelServer(focus,entry.operationId);}draw();
      const latest=[...local.values()].at(-1);if(latest)status((localLabels[latest.state]||latest.state)+(latest.errorCode?' · '+latest.errorCode:'')+'。设备状态不证明玩家实际听到了声音。');
    }catch(e){if(current(token,focus))status('暂时无法读取音频设备状态；不会自动重发 TTS。');}finally{if(token===epoch)polling=false;}
  }
  function update(messages,jobs,allowed,latest){serverJobs=jobs||[];enabled=allowed!==false;
    if(!enabled&&(auto||[...local.values()].some(v=>['PLAYING','QUEUED','REQUESTED','RECEIVING'].includes(v.state))))stop();
    const checkbox=root?.querySelector('.voice-auto');if(checkbox)checkbox.disabled=!enabled||!getFocus();
    draw();poll();if(!auto||busy||!latest||!getFocus()||!enabled)return;
    const fresh=messages.filter(m=>m.role==='ASSISTANT'&&m.status==='COMPLETE'&&m.sequence>watermark);if(!fresh.length)return;
    const selected=fresh.reduce((a,b)=>a.sequence>b.sequence?a:b);watermark=selected.sequence;speak(selected,true);
  }
  function decorate(parent,message){if(message.role!=='ASSISTANT'||message.status!=='COMPLETE')return;const node=add('div',null,parent);node.className='conversation-voice-controls';const read=button('合成并私密朗读（请求 TTS）',node,()=>speak(message));
    const record={node,read,replay:null,state:null,operation:null};record.replay=button('重播本地缓存（不合成）',node,async()=>{const focus=getFocus(),operation=record.operation;if(!focus||!operation||busy)return;const token=epoch,actionToken=++actionGeneration;busy=true;try{await stop(false);if(!current(token,focus)||actionToken!==actionGeneration)return;await control(focus,'replay',operation);if(current(token,focus)&&actionToken===actionGeneration)await poll();}catch(e){if(current(token,focus)&&actionToken===actionGeneration)status(e.message+'；缓存缺失不会自动重新合成。');}finally{if(current(token,focus)&&actionToken===actionGeneration){busy=false;draw();}}});record.state=add('p','',node);record.state.className='muted';rows.set(message.messageId,record);draw();
  }
  function clear(){const focus=getFocus();if(focus){control(focus,'stop').catch(()=>{});for(const [operation,value] of operations)cancelServer(value.focus,operation);}epoch++;actionGeneration++;busy=false;polling=false;auto=false;watermark=Infinity;operations.clear();local.clear();serverJobs=[];cancelled.clear();const checkbox=root?.querySelector('.voice-auto');if(checkbox){checkbox.checked=false;checkbox.disabled=true;}status('自动朗读默认关闭；选择会话后可明确请求。');draw();}
  function mount(parent){clear();rows.clear();root=add('div',null,parent);root.className='settings-group';root.id='conversation-voice-controls';button('停止朗读',root,()=>stop());const label=add('label',null,root);label.className='settings-check';const checkbox=add('input',null,label);checkbox.type='checkbox';checkbox.className='voice-auto';checkbox.disabled=true;add('span','本次选择：自动朗读勾选后新发送的回复（请求 TTS，最新请求优先）',label);checkbox.onchange=()=>{auto=checkbox.checked;watermark=getSelected()?.messageCount??Infinity;if(!auto)stop();else status('仅朗读之后新发送的回复，不补播历史或当前已经在途的回复。');};add('p','自动朗读默认关闭。缓存仅在当前连接/会话内存中保留；重播不请求 TTS 服务。',root).className='voice-status muted';}
  return {mount,clear,update,decorate,poll,event(data){if(getFocus()?.contextId===data.contextId)status((serverLabels[data.status]||data.status)+(data.errorCode?' · '+data.errorCode:'')+'。请以本地接收/设备状态区分播放。');}};
}
