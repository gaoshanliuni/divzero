(function(){
  'use strict';
  if(window.mineagentState)return;
  let active=0;
  function call(kind,key,expectedRevision,value){return new Promise((resolve,reject)=>{
    try{
      if(typeof key!=='string'||!/^[A-Za-z0-9_.-]{1,64}$/.test(key)||key.includes('..')||key==='.')throw new Error('UI_STATE_KEY');
      if(active>=8)throw new Error('UI_STATE_BUSY');
      const data={kind,key};
      if(kind!=='get'){if(!Number.isSafeInteger(expectedRevision)||expectedRevision<0)throw new Error('UI_STATE_REVISION');data.expectedRevision=expectedRevision;}
      if(kind==='put'){const json=JSON.stringify(value);if(typeof json!=='string'||new TextEncoder().encode(json).length>32768)throw new Error('UI_STATE_VALUE_BUDGET');data.valueJson=json;}
      if(typeof window.mineagentStateQuery!=='function')throw new Error('UI_STATE_BRIDGE_UNAVAILABLE');
      active++;let done=false;
      const finish=fn=>{if(done)return;done=true;active--;fn();};
      try{window.mineagentStateQuery({request:JSON.stringify(data),persistent:false,
        onSuccess(text){finish(()=>{try{const result=JSON.parse(text);if(result.code!=='OK')throw new Error(result.code||'UI_STATE_FAILED');const s=result.snapshot;
          if(!Number.isSafeInteger(s?.revision)||typeof s.exists!=='boolean'||typeof s.valueJson!=='string')throw new Error('UI_STATE_REPLY');
          resolve(Object.freeze({revision:s.revision,exists:s.exists,value:s.exists?JSON.parse(s.valueJson):null,packageRevision:s.packageRevision}));}catch(e){reject(e);}});},
        onFailure(code,message){finish(()=>reject(new Error(message||'UI_STATE_FAILED')));}
      });}catch(error){finish(()=>reject(error));}
    }catch(error){reject(error);}
  });}
  Object.defineProperty(window,'mineagentState',{value:Object.freeze({get:key=>call('get',key),put:(key,revision,value)=>call('put',key,revision,value),remove:(key,revision)=>call('remove',key,revision)}),writable:false,configurable:false});
  window.dispatchEvent(new CustomEvent('mineagent:state-ready'));
})();
