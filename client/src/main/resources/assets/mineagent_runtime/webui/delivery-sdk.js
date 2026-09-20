(function(){'use strict';
  if(window.mineagentDelivery)return;
  let pending=null,lastRevision=-1,wantedRevision=0,refreshing=false;
  function acknowledge(token){
    if(typeof token!=='string'||!token)return;
    const failed=code=>window.dispatchEvent(new CustomEvent('mineagent:delivery-error',{detail:{code:String(code||'DELIVERY_DATA_ACK_FAILED')}}));
    try{window.mineagentContentQuery({request:JSON.stringify({action:'delivery.dataRead',arguments:{token},operationId:token}),persistent:false,
      onSuccess(value){try{const receipt=JSON.parse(value);if(receipt.code!=='OBSERVED')failed(receipt.values?.errorCode||receipt.code);}catch(error){failed(error.message);}},onFailure(_,code){failed(code);}});}catch(error){failed(error.message);}
  }
  function read(){
    if(pending)return pending;
    pending=new Promise((resolve,reject)=>{
      let finished=false;const done=(value,error)=>{if(finished)return;finished=true;error?reject(error):resolve(value);};
      try{if(typeof window.mineagentContentQuery!=='function')throw new Error('DELIVERY_BRIDGE_UNAVAILABLE');
        window.mineagentContentQuery({request:JSON.stringify({action:'delivery.read',arguments:{},operationId:crypto.randomUUID()}),persistent:false,onSuccess(value){try{const receipt=JSON.parse(value);if(receipt.code!=='OBSERVED')throw new Error(receipt.values?.errorCode||receipt.code);const state=JSON.parse(receipt.values.state);if(!Number.isSafeInteger(state.revision)||state.revision<1)throw new Error('DELIVERY_REVISION');if(state.revision<lastRevision)throw new Error('DELIVERY_STALE_REVISION');lastRevision=state.revision;acknowledge(receipt.values.nativeDeliveryReadToken);done(state);}catch(error){done(null,error);}},onFailure(_,code){done(null,new Error(code||'DELIVERY_READ_FAILED'));}});
      }catch(error){done(null,error);}
    }).finally(()=>{pending=null;});return pending;
  }
  Object.defineProperty(window,'mineagentDelivery',{value:Object.freeze({version:1,read}),writable:false,configurable:false});
  window.addEventListener('mineagent:delivery-refresh',event=>{
    const revision=event.detail?.revision;if(!Number.isSafeInteger(revision)||revision<1)return;wantedRevision=Math.max(wantedRevision,revision);if(refreshing)return;refreshing=true;
    (async()=>{try{for(let attempt=0;attempt<3;attempt++){const state=await read();if(state.revision>=wantedRevision){window.dispatchEvent(new CustomEvent('mineagent:delivery-data',{detail:state}));return;}}throw new Error('DELIVERY_REFRESH_REQUIRED');}catch(error){window.dispatchEvent(new CustomEvent('mineagent:delivery-error',{detail:{code:error.message}}));}finally{refreshing=false;}})();
  });
  window.dispatchEvent(new Event('mineagent:delivery-ready'));
})();
