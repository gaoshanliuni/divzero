(function (key, request) {
  'use strict';
  if (!Object.prototype.hasOwnProperty.call(window, key)) {
    const documentId = `${Date.now()}-${Math.random()}`;
    const refs = new WeakMap(), elements = new Map(), ledger = new Map(), expectedStates = new Map();
    let nextRef = 0, serial = 0, observationId = '', interrupted = false, captured = null;
    const emit = payload => window.mineagentPageAgentQuery({ request: JSON.stringify(payload), persistent: false, onSuccess() {}, onFailure() {} });
    const stop = event => {
      if (event.isTrusted && !interrupted) { interrupted = true;captured=null; emit({ event: 'interrupt', documentId }); }
    };
    for (const event of ['pointerdown', 'keydown', 'beforeinput']) document.addEventListener(event, stop, true);
    function ref(element) {
      if (!refs.has(element)) refs.set(element, `e${++nextRef}`);
      elements.set(refs.get(element), element); return refs.get(element);
    }
    function visible(element) {
      const r = element.getBoundingClientRect(), style = getComputedStyle(element);
      return r.width > 0 && r.height > 0 && r.bottom > 0 && r.right > 0 && r.top < innerHeight && r.left < innerWidth
        && style.visibility !== 'hidden' && style.display !== 'none' && style.opacity !== '0';
    }
    function relevant(element) {
      return JSON.stringify({tag:element.tagName,value:String(element.value ?? ''),checked:!!element.checked,disabled:!!element.disabled,
        label:(element.getAttribute('aria-label') || element.labels?.[0]?.textContent || element.textContent || '').slice(0,160)});
    }
    function viewportState(){const v=document.scrollingElement;return JSON.stringify({width:innerWidth,height:innerHeight,x:v?.scrollLeft??0,y:v?.scrollTop??0});}
    function bounds(element){const r=element.getBoundingClientRect();return {x:r.x,y:r.y,width:r.width,height:r.height};}
    function canvasState(element){
      if(element.tagName!=='CANVAS')return '';
      if(element.width<1||element.height<1||element.width*element.height>1048576)return null;
      try{const image=element.toDataURL('image/png');return image.length<=2796204?image:null;}catch{return null;}
    }
    function sealCapture(action){
      if(interrupted)return {status:'USER_INTERRUPTED'};
      if(!action||typeof action.captureId!=='string'||action.captureId.length>128||!action.captureId||action.documentId!==documentId||action.observationId!==observationId)return {status:'STALE_CAPTURE'};
      const snapshot=new Map();let pixels=0;
      for(const id of expectedStates.keys()){
        if(id==='viewport')continue;const element=elements.get(id);if(!element?.isConnected)continue;
        if(element.tagName==='CANVAS')pixels+=element.width*element.height;
        snapshot.set(element,{id,state:relevant(element),bounds:bounds(element),canvas:pixels<=1048576?canvasState(element):null});
      }
      captured={id:action.captureId,viewport:viewportState(),elements:snapshot,created:Date.now()};return {status:'CAPTURE_SEALED',captureId:action.captureId};
    }
    function coordinateTarget(action){
      if(interrupted)throw new Error('USER_INTERRUPTED');
      if(!captured||action.captureId!==captured.id||action.documentId!==documentId||Date.now()-captured.created>120000||captured.viewport!==viewportState())throw new Error('STALE_CAPTURE');
      const x=action.pageX,y=action.pageY;
      if(!Number.isFinite(x)||!Number.isFinite(y)||x<0||y<0||x>=innerWidth||y>=innerHeight)throw new Error('CAPTURE_COORDINATE_BOUNDS');
      let element=document.elementFromPoint(x,y);
      while(element&&!captured.elements.has(element)){if(element.type==='password'||element.type==='file')throw new Error('NOT_INTERACTABLE');element=element.parentElement;}
      const old=captured.elements.get(element);
      if(!old||!element.isConnected||!visible(element)||element.disabled||element.type==='password'||element.type==='file')throw new Error('NOT_INTERACTABLE');
      if(old.state!==relevant(element)||JSON.stringify(old.bounds)!==JSON.stringify(bounds(element)))throw new Error('CAPTURE_TARGET_CHANGED');
      if(element.tagName==='CANVAS'&&(old.canvas===null||old.canvas!==canvasState(element)))throw new Error('CAPTURE_TARGET_CHANGED');
      return {element,result:{status:'CAPTURE_TARGET',captureId:captured.id,elementRef:old.id,bounds:old.bounds}};
    }
    function clickAt(action,fingerprint){
      let target;try{target=coordinateTarget(action);}catch(e){return {status:e.message};}
      const record={fingerprint,result:{status:'IN_PROGRESS',executionMode:'DOM',operationId:action.operationId}};ledger.set(action.operationId,record);
      let sent=0;
      try{
        for(const name of ['pointerdown','mousedown','pointerup','mouseup','click']){
          const hit=document.elementFromPoint(action.pageX,action.pageY);
          if(!target.element.isConnected||target.element.disabled||!(hit===target.element||target.element.contains?.(hit)))throw new Error('CAPTURE_TARGET_CHANGED');
          const Type=name.startsWith('pointer')?PointerEvent:MouseEvent;
          target.element.dispatchEvent(new Type(name,{bubbles:true,composed:true,cancelable:true,clientX:action.pageX,clientY:action.pageY,button:0,buttons:name.endsWith('down')?1:0,pointerId:1,pointerType:'mouse',isPrimary:true}));sent++;
        }
        record.result={status:'APPLIED_DOM',executionMode:'DOM',inputMode:'DOM_COORDINATE_EVENTS',captureId:action.captureId,operationId:action.operationId,elementRef:target.result.elementRef,businessVerified:false,pageX:action.pageX,pageY:action.pageY,eventsDispatched:sent};
      }catch(e){record.result={status:'FAILED',executionMode:'DOM',diagnostic:e.message,eventsDispatched:sent,operationId:action.operationId};}
      captured=null;return record.result;
    }
    function inspect() {
      if (interrupted) return { status: 'USER_INTERRUPTED' };
      observationId = `${documentId}:${++serial}`;
      expectedStates.clear();elements.clear();
      const viewport=document.scrollingElement;
      if(viewport){elements.set('viewport',viewport);expectedStates.set('viewport',JSON.stringify({width:innerWidth,height:innerHeight,x:viewport.scrollLeft,y:viewport.scrollTop}));}
      const candidates = [...document.querySelectorAll('input:not([type=hidden]),textarea,button,select,[role=button],[contenteditable=true],[draggable=true],[data-ai-drop-target],canvas,[data-ai-coordinate-target],h1,h2,h3')].slice(0,2048);
      const nodes=[...candidates.filter(visible),...candidates.filter(e=>!visible(e))].slice(0,64);
      const sensitiveVisible=[...document.querySelectorAll('input[type=password],input[type=file]')].some(e=>(e.type==='password'||e.type==='file')&&visible(e));
      return { status: 'OBSERVED', documentId, observationId, title: document.title, viewport: {width:innerWidth,height:innerHeight,elementRef:'viewport',scrollX:viewport?.scrollLeft??0,scrollY:viewport?.scrollTop??0},
        visibleText: String(document.body?.innerText ?? '').slice(0,8192),sensitiveVisible,elementBudget:64,candidateElements:candidates.length,
        elements: nodes.map(element => {
          const tag=element.tagName.toLowerCase(), r=element.getBoundingClientRect();
          const label=element.getAttribute('aria-label') || element.labels?.[0]?.textContent || element.textContent || element.getAttribute('placeholder') || '';
          const secret=element.type==='password' || element.type==='file';
          if(!secret) expectedStates.set(ref(element),relevant(element));
          return { elementRef:ref(element), tag, role: element.getAttribute('role') || (/^h[123]$/.test(tag)?'heading':tag),
            dataAiId:element.getAttribute('data-ai-id') || '', dataSlotIndex:(element.getAttribute('data-slot-index')||'').slice(0,4), label:label.trim().slice(0,160),
            draggable:element.getAttribute('draggable')==='true',dropTarget:element.getAttribute('data-ai-drop-target')!=null,
            value:secret?null:String(element.value ?? '').slice(0,1024), checked:!!element.checked,
            visible:visible(element), disabled:!!element.disabled, secret,
            bounds:{x:r.x,y:r.y,width:r.width,height:r.height} };
        }) };
    }
    function act(action) {
      if (interrupted) return {status:'USER_INTERRUPTED'};
      if(!action||typeof action.operationId!=='string'||!action.operationId||action.operationId.length>128)return {status:'INVALID_REQUEST'};
      const fingerprint=JSON.stringify(action);
      if (ledger.has(action.operationId)) {
        const old=ledger.get(action.operationId); return old.fingerprint===fingerprint?old.result:{status:'OPERATION_ID_REUSED'};
      }
      if(ledger.size>=128)return {status:'LEDGER_FULL'};
      if(action.action==='clickAt')return clickAt(action,fingerprint);
      if (action.documentId!==documentId || action.observationId!==observationId) return {status:'STALE_VIEW'};
      if (ledger.size>=128) return {status:'LEDGER_FULL'};
      const elementRef=action.action==='scroll'&&!action.elementRef?'viewport':action.elementRef;
      const element=elements.get(elementRef);
      if (!element?.isConnected) return {status:'TARGET_NOT_FOUND'};
        const rootScroll=elementRef==='viewport'&&action.action==='scroll';
        const rootStyle=rootScroll?getComputedStyle(element):null;
        const canSee=rootScroll?innerWidth>0&&innerHeight>0&&rootStyle.display!=='none'&&rootStyle.visibility!=='hidden'&&Number(rootStyle.opacity)>0:visible(element);
        if (!canSee || (elementRef==='viewport'&&!rootScroll) || element.disabled || element.type==='password' || element.type==='file') return {status:'NOT_INTERACTABLE'};
      const currentState=elementRef==='viewport'?JSON.stringify({width:innerWidth,height:innerHeight,x:element.scrollLeft,y:element.scrollTop}):relevant(element);
      if(expectedStates.get(elementRef)!==currentState) return {status:'STATE_CONFLICT'};
      let target;
      if(action.action==='drag'){
        target=elements.get(action.targetRef);if(!target?.isConnected)return {status:'TARGET_NOT_FOUND'};
        if(!visible(target)||target.disabled||element.getAttribute('draggable')!=='true')return {status:'NOT_INTERACTABLE'};
        if(expectedStates.get(action.targetRef)!==relevant(target))return {status:'STATE_CONFLICT'};
      }
      const record={fingerprint,result:{status:'IN_PROGRESS',executionMode:'DOM'}};
      ledger.set(action.operationId,record); // reserve before dispatch so reentrant actions cannot duplicate effects
      try {
        switch(action.action) {
          case 'click': {
            const modifiers=['ctrlKey','shiftKey','altKey','metaKey'];for(const name of modifiers)if(action[name]!=null&&typeof action[name]!=='boolean')throw new Error('CLICK_MODIFIER');
            if(modifiers.some(name=>action[name]))element.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,composed:true,button:0,ctrlKey:!!action.ctrlKey,shiftKey:!!action.shiftKey,altKey:!!action.altKey,metaKey:!!action.metaKey}));
            else element.click();break;
          }
          case 'fill': {
            if (typeof action.value!=='string' || action.value.length>8192) throw new Error('VALUE_LIMIT');
            const proto=element instanceof HTMLInputElement?HTMLInputElement.prototype:element instanceof HTMLTextAreaElement?HTMLTextAreaElement.prototype:null;
            if(!proto) throw new Error('UNSUPPORTED_INPUT');
            // DOM mode updates the control/events without taking the human's keyboard focus in another window.
            if(!element.dispatchEvent(new InputEvent('beforeinput',{bubbles:true,cancelable:true,inputType:'insertReplacementText',data:action.value}))) throw new Error('INPUT_CANCELLED');
            Object.getOwnPropertyDescriptor(proto,'value').set.call(element,action.value);
            element.dispatchEvent(new InputEvent('input',{bubbles:true,composed:true,inputType:'insertReplacementText',data:action.value}));
            element.dispatchEvent(new Event('change',{bubbles:true})); break;
          }
          case 'select': {
            if(!(element instanceof HTMLSelectElement) || ![...element.options].some(o=>o.value===action.value&&!o.disabled)) throw new Error('INVALID_OPTION');
            element.value=action.value; element.dispatchEvent(new Event('input',{bubbles:true})); element.dispatchEvent(new Event('change',{bubbles:true})); break;
          }
          case 'toggle': {
            if(!['checkbox','radio'].includes(element.type) || typeof action.checked!=='boolean') throw new Error('INVALID_TOGGLE');
            if(element.checked!==action.checked) element.click(); break;
          }
          case 'scroll': {
            if(!Number.isFinite(action.x)||!Number.isFinite(action.y)||Math.abs(action.x)>5000||Math.abs(action.y)>5000) throw new Error('SCROLL_LIMIT');
            const mode=action.mode??'by';
            if(!['by','to'].includes(mode))throw new Error('SCROLL_MODE');
            element[mode==='to'?'scrollTo':'scrollBy']({left:action.x,top:action.y,behavior:'instant'}); break;
          }
          case 'key': {
            if(!['Enter','Escape','Tab','ArrowUp','ArrowDown','ArrowLeft','ArrowRight','Home','End','PageUp','PageDown','Backspace','Delete',' '].includes(action.key))throw new Error('UNSUPPORTED_KEY');
            for(const name of ['ctrlKey','shiftKey','altKey','metaKey'])if(action[name]!=null&&typeof action[name]!=='boolean')throw new Error('KEY_MODIFIER');
            const options={key:action.key,code:action.key===' '?'Space':action.key,bubbles:true,composed:true,cancelable:true,
              ctrlKey:!!action.ctrlKey,shiftKey:!!action.shiftKey,altKey:!!action.altKey,metaKey:!!action.metaKey};
            element.dispatchEvent(new KeyboardEvent('keydown',options));element.dispatchEvent(new KeyboardEvent('keyup',options));break;
          }
          case 'drag': {
            const transfer=new DataTransfer(),start=element.getBoundingClientRect(),end=target.getBoundingClientRect();
            const options=(r)=>({bubbles:true,cancelable:true,composed:true,dataTransfer:transfer,clientX:r.x+r.width/2,clientY:r.y+r.height/2});
            if(!element.dispatchEvent(new DragEvent('dragstart',options(start))))throw new Error('DRAG_CANCELLED');
            target.dispatchEvent(new DragEvent('dragenter',options(end)));target.dispatchEvent(new DragEvent('dragover',options(end)));
            if(!target.isConnected||!visible(target))throw new Error('DRAG_TARGET_CHANGED');
            target.dispatchEvent(new DragEvent('drop',options(end)));element.dispatchEvent(new DragEvent('dragend',options(end)));break;
          }
          default: throw new Error('UNSUPPORTED_ACTION');
        }
        record.result={status:'APPLIED_DOM',executionMode:'DOM',operationId:action.operationId,businessVerified:false,
          inputMode:action.action==='key'?'DOM_KEYBOARD_EVENTS':action.action==='drag'?'HTML5_DOM_DRAG':'DOM',defaultBrowserAction:false};
      } catch(error) { record.result={status:'FAILED',diagnostic:String(error.message).slice(0,200),executionMode:'DOM'}; }
      return record.result;
    }
    const api=Object.freeze({
      handle(r) {
        if(r.kind==='reset') { interrupted=false;captured=null; return {status:'READY',documentId}; }
        if(r.kind==='cancel') { interrupted=true;captured=null; return {status:'USER_INTERRUPTED'}; }
        if(r.kind==='identity')return {status:'OBSERVED',documentId};
        if(r.kind==='inspect') return inspect();
        if(r.kind==='sealCapture')return sealCapture(r.action);
        if(r.kind==='prepareClickAt'){try{return coordinateTarget(r.action).result;}catch(e){return {status:e.message};}}
        if(r.kind==='act') return act(r.action);
        return {status:'UNSUPPORTED'};
      }
    });
    Object.defineProperty(window,key,{value:api,writable:false,configurable:false});
  }
  const result=window[key].handle(request);
  if(request.requestId) window.mineagentPageAgentQuery({request:JSON.stringify({requestId:request.requestId,result}),persistent:false,onSuccess(){},onFailure(){}});
})
