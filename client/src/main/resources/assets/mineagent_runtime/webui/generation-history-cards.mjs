const categories={GENERATION:'内容生成',UI_PATCH:'网页改版',WORLD_PATCH:'世界内容改版',LINK:'任务与生成关联'};
const states=['ALL','GENERATING','PUBLISHED','PENDING','READY','APPLYING','APPLIED','ROLLING_BACK','ROLLED_BACK','SUBMITTING','WAITING','WAITING_APPLY','COMPLETED','FAILED','STALE','CANCELLED','INTERRUPTED'];
export function createGenerationHistory({windowFor,send,openGeneration,openOperation}){
  let root=null,ready=false,scope='',epoch=0,listSerial=0,detailSerial=0,category='GENERATION',state='ALL',archive='ALL',offset=0,page=null,selected=null;
  const reads=new WeakMap();
  const el=(tag,text,parent,cls)=>{const n=document.createElement(tag);if(text!=null)n.textContent=text;if(cls)n.className=cls;parent?.append(n);return n;};
  const button=(text,parent,fn,allowed=true)=>{const n=el('button',text,parent);n.type='button';n.disabled=!ready||!allowed;n.onclick=()=>{if(ready&&allowed)fn();};return n;};
  const q=id=>root.querySelector(id);
  const message=(text,bad=false)=>{if(!root?.isConnected)return;const n=q('#generation-history-status');n.textContent=text;n.className=bad?'error':'muted';};
  async function request(args){const r=await send('generationHistory',args);if(r.code!=='OBSERVED'||r.values?.errorCode)throw new Error(r.values?.errorCode||r.code||'PACKAGE_HISTORY_UNAVAILABLE');return JSON.parse(r.values.state);}
  function renderList(){const list=q('#generation-history-list');list.replaceChildren();const usage=page?.usage;
    q('#generation-history-usage').textContent=usage?'本人此类记录：活跃 '+usage.active+' · 历史 '+usage.archived+' · 总计 '+usage.total+' · payload '+(usage.payloadBytes/1048576).toFixed(2)+' MiB · 结果预留 '+(usage.reservedBytes/1048576).toFixed(2)+' MiB':'';
    if(!page?.items.length)el('p','没有符合筛选的本人记录。历史为空不代表没有外部副作用。',list,'muted');
    for(const item of page?.items||[]){const card=el('article',null,list,'event-card');el('strong',item.promptPreview||item.operationId,card);el('p',item.state+' · '+(item.archived?'历史区':'活跃区')+' · r'+item.revision,card,'muted');el('p',item.operationId,card,'muted');if(item.error)el('p',item.error,card,'warning');button('查看记录',card,()=>void detail(item.operationId,item.revision));}
    const paging=q('#generation-history-paging');paging.replaceChildren();el('span',page?'匹配 '+page.total+' 条 · 本页 '+page.items.length+' 条':'',paging,'muted');button('上一页',paging,()=>{offset=Math.max(0,offset-8);void list();},offset>0);button('下一页',paging,()=>{offset=page.nextOffset;void list();},!!page?.more);
  }
  async function list(){if(!ready)return;const generation=epoch,serial=++listSerial,current=root,query={kind:'jobs',category,state,archive,offset:String(offset)};message('读取持久生成历史，不调用模型…');
    try{const data=await request(query);if(generation!==epoch||serial!==listSerial||current!==root||!root?.isConnected)return;page=data;renderList();message('历史已更新；期间记录变化可能改变后续页，旧操作不会重新执行。');}
    catch(error){if(generation===epoch&&serial===listSerial)message(error.message,true);}
  }
  async function detail(operation,revision=0){if(!ready)return;const ticket={operation:operation.toLowerCase(),revision,category},generation=epoch,serial=++detailSerial;selected=ticket;const pane=q('#generation-history-detail');pane.replaceChildren();el('p','读取原记录…',pane,'muted');
    try{const data=await request({kind:'job',category:ticket.category,operation:ticket.operation,revision:String(revision)});if(generation!==epoch||serial!==detailSerial||selected!==ticket||!pane.isConnected)return;ticket.revision=data.revision;pane.replaceChildren();el('h3',categories[ticket.category]+' · '+data.state,pane);el('p','这是持久记录，不代表当前包版本、任务或页面仍可使用。此处不恢复、应用、回滚或调用 Provider。',pane,'muted');el('pre',JSON.stringify(data,null,2),pane,'event-json');
      button('重新读取当前版本',pane,()=>void detail(ticket.operation,0));button('前往内容生成',pane,openGeneration);if(openOperation&&ticket.category!=='LINK')button('在原操作面板定位此记录',pane,()=>openOperation(ticket.category,ticket.operation));
      const prompt=el('section',null,pane,'event-card');button('分页查看完整原请求',prompt,()=>void text(ticket,'jobPrompt',0,prompt));
      if(data.rawHash){const raw=el('section',null,pane,'event-card');el('p','原始输出只按文本显示；读取时校验 SHA-256，不解释或执行其中代码。',raw,'muted');button('分页查看原始输出',raw,()=>void text(ticket,'jobRaw',0,raw));}
      else el('p','此记录没有保存 raw 输出引用，不能据此断定 Provider 未执行。',pane,'muted');
    }catch(error){if(generation===epoch&&serial===detailSerial&&pane.isConnected){pane.replaceChildren();el('p',error.message,pane,'error');button('读取当前记录',pane,()=>void detail(ticket.operation,0));}}
  }
  async function text(ticket,kind,position,box){const generation=epoch,serial=(reads.get(box)||0)+1;reads.set(box,serial);box.replaceChildren();el('p','校验并读取文本…',box,'muted');
    try{const data=await request({kind,category:ticket.category,operation:ticket.operation,revision:String(ticket.revision),offset:String(position)});if(generation!==epoch||selected!==ticket||reads.get(box)!==serial||!box.isConnected)return;box.replaceChildren();el('p',(kind==='jobRaw'?'原始输出':'原请求')+' · 字符 '+data.offset+'–'+data.nextOffset+' / '+data.length,box,'muted');el('pre',data.text,box,'event-json');button('前一段',box,()=>void text(ticket,kind,Math.max(0,position-1024),box),position>0);button('后一段',box,()=>void text(ticket,kind,data.nextOffset,box),data.more);}
    catch(error){if(generation===epoch&&selected===ticket&&reads.get(box)===serial&&box.isConnected){box.replaceChildren();el('p',error.message,box,'error');button('重新读取记录版本',box,()=>void detail(ticket.operation,0));}}
  }
  function changed(){offset=0;selected=null;detailSerial++;q('#generation-history-detail').replaceChildren();void list();}
  function build(){if(root.childElementCount)return;el('p','全量分页查看本人生成、改版和关联任务。历史区不会占用活跃扫描，但原 ID、请求、回执、候选与 raw 引用保留；不会删除记录来重新获得模型额度。',root,'muted');const status=el('p','等待可信会话…',root,'muted');status.id='generation-history-status';status.setAttribute('aria-live','polite');el('p','',root,'event-retention').id='generation-history-usage';
    const tools=el('div',null,root,'event-toolbar');for(const [label,entries,value,set] of [['类别',Object.entries(categories),category,v=>category=v],['状态',states.map(v=>[v,v==='ALL'?'全部状态':v]),state,v=>state=v],['区域',[['ALL','全部'],['ACTIVE','活跃区'],['ARCHIVED','历史区']],archive,v=>archive=v]]){const wrap=el('label',label,tools),select=el('select',null,wrap);select.disabled=!ready;for(const [key,title] of entries)el('option',title,select).value=key;select.value=value;select.onchange=()=>{set(select.value);changed();};}
    button('刷新第一页',tools,()=>{offset=0;void list();});const lookup=el('label','按当前类别精确定位 operation UUID',root),input=el('input',null,lookup);input.maxLength=36;input.disabled=!ready;button('读取操作',lookup,()=>{if(!/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(input.value.trim())){message('请输入完整 operation UUID。',true);return;}void detail(input.value.trim(),0);});
    el('div',null,root,'actions').id='generation-history-paging';const grid=el('div',null,root,'event-management-grid');el('section',null,grid).id='generation-history-list';el('section',null,grid).id='generation-history-detail';
    el('p','上限按每 world 的每类表、全部 Owner 合计：活跃 4096、保留 131072、payload+结果预留 512 MiB；本页统计只是本人用量，不是个人可用额度。历史区不是磁盘压缩；活跃生成/改版的原并发限制仍有效。',root,'muted');
  }
  function open(){const next=windowFor('runtime-generation-history','本人生成与改版历史');if(root!==next){root=next;epoch++;selected=null;}build();if(ready)void list();}
  function reset(){epoch++;listSerial++;detailSerial++;ready=false;selected=null;page=null;root?.replaceChildren();if(root?.isConnected)build();}
  return {open,reset,session(value){const key=JSON.stringify([value?.binding?.worldId,value?.binding?.viewerPlayerId]);epoch++;listSerial++;detailSerial++;ready=!!value;if(scope!==key||!ready){page=null;selected=null;offset=0;}scope=key;if(root?.isConnected){root.replaceChildren();build();if(ready)void list();}}};
}
