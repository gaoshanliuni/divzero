const uuid=value=>typeof value==='string'&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
export function createConversationSpeech({send,request,getFocus,getSelected,getDraft,apply,persist}){
  const reviews=new Map(),sources=new Map();let root=null;
  const add=(tag,text,parent)=>{const n=document.createElement(tag);if(text!=null)n.textContent=text;parent.append(n);return n;};
  const button=(text,parent,fn)=>{const b=add('button',text,parent);b.type='button';b.onclick=fn;return b;};
  const status=text=>{const n=root?.querySelector('.speech-status');if(n)n.textContent=text;};
  const ordinary=()=>!document.querySelector('#chat-decision')?.value;
  function update(){if(!root?.isConnected)return;const c=getSelected(),focus=getFocus(),r=focus?reviews.get(c?.conversationId):null,area=root.querySelector('.speech-review');
    root.querySelector('.speech-open').disabled=!focus||c?.state!=='ACTIVE'||!ordinary()||!!r||reviews.size>=4;
    root.querySelector('.speech-unlink').hidden=!sources.has(c?.conversationId);area.hidden=!r;
    if(r){const text=area.querySelector('textarea');if(area.dataset.operation!==r.speechOperation){area.dataset.operation=r.speechOperation;text.value=r.text;area.querySelector('input').checked=false;}
      for(const b of area.querySelectorAll('button'))b.disabled=!focus||c?.state!=='ACTIVE'||!ordinary();
    }
  }
  function receive(data){const focus=getFocus(),c=getSelected();if(!focus||focus.contextId!==data.contextId||focus.agentId!==data.agentId||c?.conversationId!==data.conversationId||!uuid(data.speechOperation)||typeof data.text!=='string'||data.text.length>16384)return;
    if(reviews.has(c.conversationId)||reviews.size>=4){status('已有未处理转写，未覆盖；请先处理已有草稿。');return;}
    reviews.set(c.conversationId,{agentId:data.agentId,speechOperation:data.speechOperation,text:data.text});persist();update();status('完整转写已保存在独立待确认草稿中。校对后选择追加或替换；尚未发送聊天。');
  }
  function adopt(mode){const c=getSelected(),r=reviews.get(c?.conversationId);if(!r||!getFocus()||c.state!=='ACTIVE'||!ordinary())return;const area=root.querySelector('.speech-review');
    if(!area.querySelector('input').checked){status('请确认已校对文本和当前会话；不会自动发送。');return;}
    const text=area.querySelector('textarea').value,existing=getDraft();const merged=mode==='append'&&existing?existing+'\n'+text:text;
    if(!text.trim()||merged.length>16384){status('合并后为空或超过 16384 字符。两份草稿都保留，请修改后再采用。');return;}
    apply(merged);sources.set(c.conversationId,r.speechOperation);reviews.delete(c.conversationId);persist();update();status('已加入当前会话草稿。只有你点击原有发送按钮后才会发送。');
  }
  function mount(parent){root=add('div',null,parent);root.id='conversation-speech';root.className='settings-group';button('录音并转写…（打开原生录音页）',root,async()=>{const f=getFocus();if(!f||!ordinary())return;try{await send('conversationSpeech',f);}catch{status('录音页未打开。请确认当前为普通会话且没有其它原生界面。');}}).className='speech-open';
    add('p','ASR 使用独立端点与 Key。打开录音页不会录音，确认上传才会请求转写服务。',root).className='speech-status muted';
    const area=add('div',null,root);area.className='speech-review';area.hidden=true;const label=add('label','完整转写（可修改，原聊天草稿尚未改变）',area),text=add('textarea',null,label);text.rows=5;text.maxLength=16384;text.oninput=()=>{const r=reviews.get(getSelected()?.conversationId);if(r){r.text=text.value;persist();}area.querySelector('input').checked=false;};
    const consent=add('label',null,area);consent.className='settings-check';const check=add('input',null,consent);check.type='checkbox';add('span','已校对，并确认放入当前会话草稿（不发送）。',consent);
    button('追加到原草稿',area,()=>adopt('append'));button('替换原草稿',area,()=>adopt('replace'));
    button('丢弃本次转写',area,async()=>{const c=getSelected(),r=reviews.get(c?.conversationId);if(!r)return;try{await request('speechDiscard',{agentId:r.agentId,conversationId:c.conversationId,speechOperation:r.speechOperation,requestId:crypto.randomUUID()});if(reviews.get(c.conversationId)===r)reviews.delete(c.conversationId);persist();update();}catch(e){status(e.message+'；本地文本保留，未自动重试删除。');}});
    button('改为普通文字草稿（保留文字）',root,()=>{const c=getSelected();if(c){sources.delete(c.conversationId);persist();update();}}).className='speech-unlink';update();
  }
  return {mount,update,receive,source:id=>sources.get(id),clearSource(id){sources.delete(id);update();},snapshot:()=>({reviews:Object.fromEntries(reviews),sources:Object.fromEntries(sources)}),restore(value){
    for(const [id,r] of Object.entries(value?.reviews||{}).slice(0,4))if(uuid(id)&&uuid(r?.agentId)&&uuid(r?.speechOperation)&&typeof r.text==='string'&&r.text.length<=16384&&!reviews.has(id))reviews.set(id,r);
    for(const [id,op] of Object.entries(value?.sources||{}).slice(0,128))if(uuid(id)&&uuid(op)&&!sources.has(id))sources.set(id,op);update();
  }};
}
