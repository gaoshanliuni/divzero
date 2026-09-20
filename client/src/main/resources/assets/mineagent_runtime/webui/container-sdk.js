(function(){
  'use strict';let pending=0;
  function failure(code){const error=new Error(code);error.code=code;return error;}
  function request(action,args,operationId){return new Promise((resolve,reject)=>{
    if(pending>=16)return reject(new Error('CONTAINER_PENDING_BUDGET'));
    if(typeof window.mineagentContentQuery!=='function')return reject(new Error('CONTAINER_BRIDGE_UNAVAILABLE'));
    const message=JSON.stringify({action,arguments:args,operationId:operationId||crypto.randomUUID()});if(message.length>65536)return reject(new Error('CONTAINER_MESSAGE_SIZE'));
    pending++;let done=false;const finish=(value,error)=>{if(done)return;done=true;pending--;error?reject(error):resolve(value);};
    try{window.mineagentContentQuery({request:message,persistent:false,onSuccess(value){try{const r=JSON.parse(value);if(!['OBSERVED','APPLIED'].includes(r.code))throw failure(r.values?.errorCode||r.code);finish(JSON.parse(r.values.state));}catch(e){finish(null,e);}},onFailure(_,code){finish(null,failure(code||'CONTAINER_FAILED'));}});}catch(e){finish(null,e);}
  });}
  const act=(revision,action,operation)=>request('container.act',{expectedRevision:String(revision),action:JSON.stringify(action)},operation);
  Object.defineProperty(window,'mineagentContainer',{value:Object.freeze({version:1,read:()=>request('container.read',{}),
    click:(revision,slot,button=0,clickType='PICKUP',operation)=>act(revision,{kind:'CLICK',slot,button,clickType,slots:[]},operation),
    drag:(revision,slots,mode=0,operation)=>act(revision,{kind:'DRAG',slot:-999,button:mode,clickType:'QUICK_CRAFT',slots},operation)}),configurable:true,writable:false});
  window.dispatchEvent(new CustomEvent('mineagent:container-ready'));
})();
