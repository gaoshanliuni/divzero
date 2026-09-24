import test from 'node:test';
import assert from 'node:assert/strict';
import {createThinkingHistory}from '../../main/resources/assets/mineagent_runtime/webui/conversation-thinking.mjs';
function fixture(t){
 class Node{constructor(tag){this.tag=tag;this.children=[];this.dataset={};this.listeners={};this.isConnected=true;this.open=false;}append(n){this.children.push(n);n.parentNode=this;}addEventListener(e,fn){this.listeners[e]=fn;}}
 const old=globalThis.document;t.after(()=>{globalThis.document=old;});globalThis.document={createElement:tag=>new Node(tag)};
 const calls=[],stamp={agentId:'a',conversationId:'c'};let live=true,resolve;
 const view=createThinkingHistory({request:(kind,args)=>{calls.push({kind,args});return new Promise(r=>{resolve=r;});},current:()=>live,notice(){}});
 const row=new Node('article'),meta={messageId:'m',revision:2,textLength:5000,active:true};view.decorate(row,meta,stamp);
 return {view,row,meta,stamp,calls,node:row.children[0],finish:text=>resolve({state:{revision:2,offset:calls.at(-1).args.offset,text}}),stop(){live=false;},newRow:()=>new Node('article')};
}
const tick=()=>new Promise(r=>setImmediate(r));
test('collapsed by default, lazy typed reads, literal content, paged expansion',async t=>{
 const f=fixture(t),d=f.node;assert.equal(d.open,false);assert.equal(f.calls.length,0);d.open=true;d.listeners.toggle();assert.equal(f.calls[0].kind,'thinking');f.finish('<script>literal</script>'+'x'.repeat(4096-24));await tick();
 assert.ok(d.children[1].textContent.startsWith('<script>'));assert.equal(d.children[2].hidden,false);d.children[2].onclick();assert.equal(f.calls.length,2);f.finish('x'.repeat(904));await tick();assert.equal(d.children[2].hidden,true);
});
test('explicit expansion survives refresh but clears on conversation change',t=>{
 const f=fixture(t);f.node.open=true;f.node.listeners.toggle();const row=f.newRow();f.view.decorate(row,f.meta,f.stamp);assert.equal(row.children[0].open,true);f.view.clear();const other=f.newRow();f.view.decorate(other,f.meta,f.stamp);assert.equal(other.children[0].open,false);
});
test('late response cannot paint another selection or disconnected history',async t=>{
 const f=fixture(t);f.node.open=true;f.node.listeners.toggle();f.stop();f.finish('private');await tick();assert.equal(f.node.children[1].textContent,'展开查看');
});
test('no thinking metadata adds no empty disclosure',t=>{const f=fixture(t),row=f.newRow();f.view.decorate(row,null,f.stamp);assert.equal(row.children.length,0);});

test('updating thinking reuses the same disclosure and preserves user expansion',t=>{const f=fixture(t),d=f.node;d.open=true;f.view.decorate(f.row,{...f.meta,revision:3,textLength:6000},f.stamp);assert.equal(f.row.children.length,1);assert.equal(f.row.children[0],d);assert.equal(d.open,true);});
