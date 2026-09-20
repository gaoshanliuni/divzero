(function(){
  'use strict';
  if(window.mineagentWorld?.version===1&&typeof window.mineagentWorld.subscribe==='function')return;
  let pending=0,listenRevision=0,enabled=false,refreshing=false,wanted=false,lastState=null;
  const listeners=new Set();
  function request(action,args,operationId){return new Promise((resolve,reject)=>{
    if(pending>=16)return reject(new Error('WORLD_UI_PENDING_BUDGET'));
    if(typeof window.mineagentContentQuery!=='function')return reject(new Error('CONTENT_BRIDGE_UNAVAILABLE'));
    const id=operationId??crypto.randomUUID();if(typeof id!=='string'||!/^[a-f\d]{8}-[a-f\d]{4}-[a-f\d]{4}-[a-f\d]{4}-[a-f\d]{12}$/i.test(id))return reject(new Error('WORLD_UI_OPERATION_ID'));
    const message=JSON.stringify({action,arguments:args,operationId:id});if(message.length>24000)return reject(new Error('WORLD_UI_MESSAGE_SIZE'));
    pending++;let done=false;const finish=(value,error)=>{if(done)return;done=true;pending--;error?reject(error):resolve(value);};
    try{window.mineagentContentQuery({request:message,persistent:false,onSuccess(value){try{const receipt=JSON.parse(value);if(!['OBSERVED','APPLIED'].includes(receipt.code))throw new Error(receipt.values?.errorCode||receipt.code||'INVALID_RECEIPT');finish(JSON.parse(receipt.values.state));}catch(error){finish(null,error);}},onFailure(_,code){finish(null,new Error(code||'WORLD_UI_REQUEST_FAILED'));}});}catch(error){finish(null,error);}
  });}
  function report(error){for(const listener of [...listeners]){try{listener.error?.(error);}catch(_){}}window.dispatchEvent(new CustomEvent('mineagent:world-error',{detail:{code:String(error?.message||error)}}));}
  function publish(state){lastState=state;for(const listener of [...listeners]){if(!listeners.has(listener))continue;try{listener.state(state);}catch(error){try{listener.error?.(error);}catch(_){}}}}
  async function refresh(){if(refreshing||!enabled||!listeners.size)return;refreshing=true;const generation=listenRevision;
    try{do{wanted=false;const state=await request('worldui.read',{refresh:'true'});if(generation!==listenRevision||!enabled||!listeners.size)return;publish(state);}while(wanted&&generation===listenRevision&&enabled&&listeners.size);}
    catch(error){if(generation===listenRevision&&listeners.size)report(error);}finally{refreshing=false;if(wanted&&enabled&&listeners.size&&generation!==listenRevision)void refresh();}
  }
  function subscribe(onState,onError){
    if(typeof onState!=='function'||onError!==undefined&&typeof onError!=='function')throw new TypeError('WORLD_UI_SUBSCRIBER');
    if(listeners.size>=16)throw new Error('WORLD_UI_SUBSCRIBER_BUDGET');const listener={state:onState,error:onError};listeners.add(listener);
    if(listeners.size===1){const generation=++listenRevision;enabled=false;
      request('worldui.read',{listen:'true',listenRevision:String(generation)}).then(state=>{if(generation!==listenRevision||!listeners.size)return;enabled=true;publish(state);if(wanted)void refresh();}).catch(error=>{if(generation===listenRevision&&listeners.size){enabled=false;report(error);}});
    }
    if(listeners.size>1&&enabled&&lastState!==null){const generation=listenRevision;queueMicrotask(()=>{if(generation===listenRevision&&listeners.has(listener)){try{listener.state(lastState);}catch(error){try{listener.error?.(error);}catch(_){}}}});}
    let stopped=false;return ()=>{if(stopped)return;stopped=true;listeners.delete(listener);if(!listeners.size){enabled=false;wanted=false;const generation=++listenRevision;request('worldui.read',{listen:'false',listenRevision:String(generation)}).catch(()=>{});}};
  }
  window.addEventListener('mineagent:world-refresh',()=>{if(!listeners.size)return;wanted=true;void refresh();});
  const api=Object.freeze({version:1,read:()=>request('worldui.read',{}),subscribe,action(expectedRevision,name,payload={},operationId){
    try{if(!Number.isSafeInteger(expectedRevision)||expectedRevision<1||typeof name!=='string'||!/^[A-Za-z][A-Za-z\d_.-]{0,63}$/.test(name))throw new Error('WORLD_UI_ACTION_INPUT');const data=JSON.stringify(payload);if(typeof data!=='string'||data.length>8192)throw new Error('WORLD_UI_DATA_INVALID');return request('worldui.action',{expectedRevision:String(expectedRevision),name,payload:data},operationId);}catch(error){return Promise.reject(error);}
  }});
  Object.defineProperty(window,'mineagentWorld',{value:api,configurable:true,writable:false});window.dispatchEvent(new CustomEvent('mineagent:world-ready'));
})();
