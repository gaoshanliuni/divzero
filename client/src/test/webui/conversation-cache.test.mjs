import test from 'node:test';import assert from 'node:assert/strict';
import {preloadMessages,cacheKey} from '../../main/resources/assets/mineagent_runtime/webui/conversation-cache.mjs';
test('parallel batches cache stable revisions and fetch only changed messages',async()=>{
 const stamp={conversationId:'c',agentId:'a'},cache=new Map();let calls=0,active=0,peak=0;
 const messages=Array.from({length:20},(_,i)=>({messageId:String(i),revision:1,textLength:10}));
 const request=async(kind,args)=>{assert.equal(kind,'messageBatch');calls++;active++;peak=Math.max(peak,active);await new Promise(r=>setTimeout(r,5));active--;return {state:{chunks:JSON.parse(args.messages).map(m=>({...m,total:10,text:'x'.repeat(10)}))}};};
 const load=()=>preloadMessages({messages,stamp,cache,request,current:()=>true});await load();assert.equal(calls,5);assert.equal(peak,5);await load();assert.equal(calls,5);messages[3].revision=2;await load();assert.equal(calls,6);assert.equal(cache.get(cacheKey(messages[3],stamp)),'xxxxxxxxxx');
});
test('late response cannot cache into a changed conversation',async()=>{let live=true;const cache=new Map();await preloadMessages({messages:[{messageId:'m',revision:1,textLength:1}],stamp:{conversationId:'old',agentId:'a'},cache,current:()=>live,request:async()=>{live=false;return {state:{chunks:[{messageId:'m',revision:1,offset:0,total:1,text:'x'}]}};}});assert.equal(cache.size,0);});
