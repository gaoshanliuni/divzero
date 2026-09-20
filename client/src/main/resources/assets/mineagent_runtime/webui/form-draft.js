(function(request){
  'use strict';
  const limit=49152;
  function eligible(e){
    const identity=[e.getAttribute('id'),e.getAttribute('name'),e.getAttribute('data-ai-id'),e.getAttribute('autocomplete')].join(' ');
    return !e.disabled&&!e.readOnly&&!['password','file','hidden','submit','button','reset','image'].includes(e.type)
      &&e.getAttribute('data-mineagent-private')==null&&!/password|secret|token|api.?key|cc-number|cc-csc|one-time-code/i.test(identity);
  }
  function describe(e){
    const attribute=['data-ai-id','id','name'].find(a=>e.getAttribute(a));if(!attribute)return null;
    return {attribute,locator:e.getAttribute(attribute),tag:e.tagName.toLowerCase(),type:e.type||'',name:e.getAttribute('name')||''};
  }
  function matches(e,c){return e.getAttribute(c.attribute)===c.locator&&e.tagName.toLowerCase()===c.tag&&(e.type||'')===c.type&&(e.getAttribute('name')||'')===c.name;}
  function snapshot(fields){
      const controls=[];
      for(const e of fields){
        if(!eligible(e))continue;const key=describe(e);if(!key)continue;
        if(key.locator.length>256||fields.filter(f=>matches(f,key)).length!==1)return {status:'DRAFT_AMBIGUOUS_TARGET'};
        const value=String(e.value??'');if(value.length>8192||controls.length>=64)return {status:'DRAFT_BUDGET'};
        controls.push({...key,value,checked:!!e.checked,selected:e.tagName==='SELECT'?[...e.options].filter(o=>o.selected).map(o=>o.value):[]});
      }
      const draft={status:'DRAFT_CAPTURED',version:1,controls};if(JSON.stringify(draft).length>limit)return {status:'DRAFT_BUDGET'};return draft;
  }
  function run(){
    const fields=[...document.querySelectorAll('input,textarea,select')];
    if(request.kind==='capture'){
      if(request.freeze===true&&typeof window.__mineagentSealedDraft==='string')return JSON.parse(window.__mineagentSealedDraft);
      const draft=snapshot(fields);if(draft.status!=='DRAFT_CAPTURED')return draft;
      if(request.freeze===true){Object.defineProperty(window,'__mineagentSealedDraft',{value:JSON.stringify(draft),writable:false,configurable:false});for(const e of fields)e.disabled=true;}return draft;
    }
    if(request.kind!=='restore')return {status:'UNSUPPORTED'};
    if(request.expectedCurrent){const actual=snapshot(fields);if(actual.status!=='DRAFT_CAPTURED'||JSON.stringify(actual.controls)!==JSON.stringify(request.expectedCurrent.controls))return {status:'DRAFT_CURRENT_CHANGED'};}
    const draft=request.draft;if(!draft||draft.version!==1||!Array.isArray(draft.controls)||draft.controls.length>64||JSON.stringify(draft).length>limit)return {status:'DRAFT_BUDGET'};
    const resolved=[],keys=new Set();
    for(const c of draft.controls){
      if(!['data-ai-id','id','name'].includes(c.attribute)||typeof c.locator!=='string'||!c.locator||c.locator.length>256||!['input','textarea','select'].includes(c.tag)
        ||typeof c.type!=='string'||typeof c.name!=='string'||typeof c.checked!=='boolean'||typeof c.value!=='string'||c.value.length>8192
        ||!Array.isArray(c.selected)||c.selected.length>256||c.selected.some(v=>typeof v!=='string'||v.length>8192))return {status:'DRAFT_BUDGET'};
      const key=c.attribute+'\0'+c.locator;if(keys.has(key))return {status:'DRAFT_AMBIGUOUS_TARGET'};keys.add(key);
      const found=fields.filter(e=>matches(e,c));if(found.length!==1||!eligible(found[0]))return {status:'DRAFT_TARGET_CHANGED'};
      if(c.tag==='select'&&c.selected.some(v=>![...found[0].options].some(o=>o.value===v)))return {status:'DRAFT_TARGET_CHANGED'};
      resolved.push([found[0],c]);
    }
    for(const [e,c] of resolved){
      if(request.silent!==true&&!e.dispatchEvent(new InputEvent('beforeinput',{bubbles:true,cancelable:true,inputType:'insertReplacementText',data:c.value})))return {status:'DRAFT_INPUT_CANCELLED'};
      if(c.tag==='select'){for(const o of e.options)o.selected=c.selected.includes(o.value);}
      else if(['checkbox','radio'].includes(c.type)){e.checked=c.checked;}
      else{const prototype=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;Object.getOwnPropertyDescriptor(prototype,'value').set.call(e,c.value);}
      if(request.silent!==true){e.dispatchEvent(new InputEvent('input',{bubbles:true,composed:true,inputType:'insertReplacementText',data:c.value}));e.dispatchEvent(new Event('change',{bubbles:true}));}
    }
    return {status:'DRAFT_RESTORED',count:resolved.length};
  }
  let result;try{result=run();}catch(error){result={status:'DRAFT_FAILED'};}
  window.mineagentDraftQuery({request:JSON.stringify({requestId:request.requestId,result}),persistent:false,onSuccess(){},onFailure(){}});
})
