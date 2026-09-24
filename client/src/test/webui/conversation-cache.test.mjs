import test from 'node:test';import assert from 'node:assert/strict';
import {preloadMessages,cacheKey} from '../../main/resources/assets/mineagent_runtime/webui/conversation-cache.mjs';
test('parallel batches cache stable revisions and fetch only changed messages',async()=>{
 const stamp={conversationId:'c',agentId:'a'},cache=new Map();let calls=0,active=0,peak=0;
 const messages=Array.from({length:20},(_,i)=>({messageId:String(i),revision:1,textLength:10}));
 const request=async(kind,args)=>{assert.equal(kind,'messageBatch');calls++;active++;peak=Math.max(peak,active);await new Promise(r=>setTimeout(r,5));active--;return {state:{chunks:JSON.parse(args.messages).map(m=>({...m,total:10,text:'x'.repeat(10)}))}};};
 const load=()=>preloadMessages({messages,stamp,cache,request,current:()=>true});await load();assert.equal(calls,5);assert.equal(peak,5);await load();assert.equal(calls,5);messages[3].revision=2;await load();assert.equal(calls,6);assert.equal(cache.get(cacheKey(messages[3],stamp)),'xxxxxxxxxx');
});
test('late response cannot cache into a changed conversation',async()=>{let live=true;const cache=new Map();await preloadMessages({messages:[{messageId:'m',revision:1,textLength:1}],stamp:{conversationId:'old',agentId:'a'},cache,current:()=>live,request:async()=>{live=false;return {state:{chunks:[{messageId:'m',revision:1,offset:0,total:1,text:'x'}]}};}});assert.equal(cache.size,0);});

test('live replies over 4096 chars append from the accepted prefix; completion is revalidated without shrinking',async()=>{
 const cache=new Map(),streams=new Map(),stamp={agentId:'a',conversationId:'c'},offsets=[];let text='甲'.repeat(6000);let m={messageId:'m',revision:1,textLength:text.length,status:'GENERATING'};
 const request=async(_kind,args)=>({state:{chunks:JSON.parse(args.messages).map(r=>{offsets.push(r.offset);return {...r,total:text.length,text:text.slice(r.offset,r.offset+4096)};})}});
 const load=()=>preloadMessages({messages:[m],stamp,cache,streams,request,current:()=>true});
 await load();assert.equal(cache.get(cacheKey(m,stamp)).length,6000);assert.deepEqual(offsets,[0,4096]);offsets.length=0;
 text+='当前末尾';m={...m,revision:2,textLength:text.length};await load();assert.deepEqual(offsets,[6000]);assert.ok(cache.get(cacheKey(m,stamp)).endsWith('当前末尾'));
 offsets.length=0;text='最终校验'+text.slice(4);m={...m,revision:3,status:'COMPLETE',textLength:text.length};await load();assert.equal(offsets[0],0);assert.equal(cache.get(cacheKey(m,stamp)),text);
});
test('a stale revision cannot append incorrect data or cross a conversation',async()=>{
 const cache=new Map(),streams=new Map(),stamp={agentId:'a',conversationId:'c'};const messages=[{messageId:'m',revision:2,textLength:3,status:'GENERATING'}];
 await assert.rejects(preloadMessages({messages,stamp,cache,streams,current:()=>true,request:async()=>({state:{chunks:[{messageId:'m',revision:1,offset:0,total:3,text:'bad'}]}})}),/CONVERSATION_CHUNK_OFFSET/);assert.equal(streams.size,0);assert.equal(cache.size,0);
});
