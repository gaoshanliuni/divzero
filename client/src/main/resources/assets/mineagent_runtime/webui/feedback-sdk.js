(function(){'use strict';
  if(window.mineagentFeedback)return;
  const uuid=value=>typeof value==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(value);
  const known=new Map(),wanted=new Map(),refreshing=new Set();
  function request(action,args,operationId,read){return new Promise((resolve,reject)=>{
    let finished=false;const done=(value,error)=>{if(finished)return;finished=true;error?reject(error):resolve(value);};
    try{if(typeof window.mineagentContentQuery!=='function')throw new Error('FEEDBACK_BRIDGE_UNAVAILABLE');
      window.mineagentContentQuery({request:JSON.stringify({action,arguments:args,operationId}),persistent:false,
        onSuccess(text){try{const r=JSON.parse(text);if(!(read?r.code==='OBSERVED':['ACCEPTED','APPLIED'].includes(r.code)))throw new Error(r.values?.errorCode||r.code);const value=JSON.parse(r.values.feedback);if(!uuid(value.feedbackId)||typeof value.state!=='string')throw new Error('FEEDBACK_RECEIPT_INVALID');const revision=Number.isSafeInteger(value.revision)?value.revision:0;if(read&&revision<(known.get(value.feedbackId)||0))throw new Error('FEEDBACK_STALE_REVISION');if(!known.has(value.feedbackId)&&known.size>=64){const oldest=known.keys().next().value;known.delete(oldest);wanted.delete(oldest);}known.set(value.feedbackId,Math.max(revision,known.get(value.feedbackId)||0));if(read){value.payload=JSON.parse(r.values.payload);value.result=JSON.parse(r.values.result);value.recovery=JSON.parse(r.values.recovery||"{}");}done(value);}catch(error){done(null,error);}},
        onFailure(_,code){done(null,new Error(code||'FEEDBACK_REQUEST_FAILED'));}});
    }catch(error){done(null,error);}
  });}
  async function submit(event,payload,operationId){
    if(arguments.length!==3||!uuid(operationId)||typeof event!=='string'||!/^[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(event)||!payload||Object.prototype.toString.call(payload)!=='[object Object]')throw new Error('FEEDBACK_ARGUMENTS');
    const entries=Object.entries(payload);if(entries.length>16||entries.some(([k,v])=>['__proto__','constructor','prototype'].includes(k)||!['string','boolean','number'].includes(typeof v)||typeof v==='number'&&!Number.isFinite(v)))throw new Error('FEEDBACK_PAYLOAD');
    const data=JSON.stringify(Object.fromEntries(entries));if(new TextEncoder().encode(data).length>8192)throw new Error('FEEDBACK_PAYLOAD_BUDGET');
    return request('feedback.submit',{event,payload:data},operationId,false);
  }
  async function inspect(feedbackId){if(arguments.length!==1||!uuid(feedbackId))throw new Error('FEEDBACK_ID');return request('feedback.read',{feedbackId},crypto.randomUUID(),true);}
  async function readShared(feedbackId){
    if(arguments.length!==1||!uuid(feedbackId))throw new Error('FEEDBACK_ID');
    return new Promise((resolve,reject)=>{let done=false;const fail=e=>{if(!done){done=true;reject(e);}};try{
      if(typeof window.mineagentContentQuery!=='function')throw new Error('FEEDBACK_BRIDGE_UNAVAILABLE');
      window.mineagentContentQuery({request:JSON.stringify({action:'feedback.stateRead',arguments:{feedbackId},operationId:crypto.randomUUID()}),persistent:false,onSuccess(text){try{const r=JSON.parse(text);if(r.code!=='OBSERVED')throw new Error(r.values?.errorCode||r.code);const state=JSON.parse(r.values.state);if(!done){done=true;resolve(state);}}catch(e){fail(e);}},onFailure(_,code){fail(new Error(code||'FEEDBACK_STATE_READ_FAILED'));}});
    }catch(e){fail(e);}});
  }
  Object.defineProperty(window,'mineagentFeedback',{value:Object.freeze({version:1,submit,inspect,readShared}),writable:false,configurable:false});
  window.addEventListener('mineagent:feedback-refresh',event=>{
    const id=event.detail?.feedbackId,revision=event.detail?.revision;if(!uuid(id)||!known.has(id)||!Number.isSafeInteger(revision)||revision<=known.get(id))return;
    wanted.set(id,Math.max(revision,wanted.get(id)||0));if(refreshing.has(id))return;refreshing.add(id);
    (async()=>{try{for(let attempt=0;attempt<3;attempt++){const state=await inspect(id);if(state.revision>=wanted.get(id)){window.dispatchEvent(new CustomEvent('mineagent:feedback-data',{detail:state}));return;}}throw new Error('FEEDBACK_REFRESH_REQUIRED');}catch(error){window.dispatchEvent(new CustomEvent('mineagent:feedback-error',{detail:{feedbackId:id,code:error.message}}));}finally{refreshing.delete(id);}})();
  });
  window.dispatchEvent(new Event('mineagent:feedback-ready'));
})();
