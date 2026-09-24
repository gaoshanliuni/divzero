export const cacheKey=(m,stamp)=>`${stamp.conversationId}:${m.messageId}:${m.revision}`;
const streamKey=(m,stamp)=>`${stamp.conversationId}:${m.messageId}`;
const generating=m=>m.status==='GENERATING'||m.status==='PENDING';
/** Persisted GENERATING text is append-only. Terminal text is verified afresh once, without shortening a live view. */
export async function preloadMessages({messages,stamp,cache,request,current,streams=new Map(),expanded=new Set()}){
 const target=m=>generating(m)||expanded.has(m.messageId)||streams.has(streamKey(m,stamp))?m.textLength:Math.min(4096,m.textLength);
 const missing=messages.filter(m=>m.textLength&&(cache.get(cacheKey(m,stamp))?.length??0)<target(m));
 const batches=[];for(let i=0;i<missing.length;i+=4)batches.push(missing.slice(i,i+4));
 await Promise.all(batches.map(async group=>{
  const texts=new Map(group.map(m=>{const old=streams.get(streamKey(m,stamp));return [m.messageId,generating(m)&&old&&old.revision<=m.revision&&old.text.length<=m.textLength?old.text:''];}));
  const pending=()=>group.filter(m=>texts.get(m.messageId).length<target(m)).map(m=>({messageId:m.messageId,revision:m.revision,offset:texts.get(m.messageId).length}));
  while(pending().length){
   if(!current())return;const requested=pending();const r=await request('messageBatch',{agentId:stamp.agentId,conversationId:stamp.conversationId,messages:JSON.stringify(requested)});if(!current())return;
   if(!Array.isArray(r.state.chunks)||r.state.chunks.length!==requested.length)throw new Error('CONVERSATION_CHUNK_COUNT');
   const seen=new Set();for(const c of r.state.chunks){const m=group.find(m=>m.messageId===c.messageId),before=texts.get(c.messageId);
    if(!m||seen.has(c.messageId)||c.revision!==m.revision||c.offset!==before.length||c.total!==m.textLength||typeof c.text!=='string'||(!c.text.length&&c.offset<c.total)||c.offset+c.text.length>c.total)throw new Error('CONVERSATION_CHUNK_OFFSET');
    seen.add(c.messageId);const text=before+c.text;texts.set(c.messageId,text);
    if(generating(m))streams.set(streamKey(m,stamp),{revision:m.revision,text});
   }
  }
  if(!current())return;for(const m of group){cache.set(cacheKey(m,stamp),texts.get(m.messageId));if(!generating(m))streams.delete(streamKey(m,stamp));}
 }));
 while(cache.size>256)cache.delete(cache.keys().next().value);
}
