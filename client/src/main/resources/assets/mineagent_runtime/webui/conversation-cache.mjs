export const cacheKey=(m,stamp)=>`${stamp.conversationId}:${m.messageId}:${m.revision}`;
export async function preloadMessages({messages,stamp,cache,request,current}){
  const missing=messages.filter(m=>m.textLength&&!cache.has(cacheKey(m,stamp)));
  const batches=[];for(let i=0;i<missing.length;i+=4)batches.push(missing.slice(i,i+4));
  await Promise.all(batches.map(async group=>{
   let pending=group.map(m=>({messageId:m.messageId,revision:m.revision,offset:0}));const texts=new Map(group.map(m=>[m.messageId,'']));
   while(pending.length){if(!current())return;
    const r=await request('messageBatch',{agentId:stamp.agentId,conversationId:stamp.conversationId,messages:JSON.stringify(pending)});
    if(!current())return;
    for(const c of r.state.chunks){const text=texts.get(c.messageId);if(typeof text!=='string'||c.offset!==text.length||!c.text.length&&c.offset<c.total)throw new Error('CONVERSATION_CHUNK_OFFSET');texts.set(c.messageId,text+c.text);}
    pending=group.filter(m=>texts.get(m.messageId).length<Math.min(4096,m.textLength)).map(m=>({messageId:m.messageId,revision:m.revision,offset:texts.get(m.messageId).length}));
   }
   for(const m of group)cache.set(cacheKey(m,stamp),texts.get(m.messageId));
  }));
  while(cache.size>256)cache.delete(cache.keys().next().value);

}
