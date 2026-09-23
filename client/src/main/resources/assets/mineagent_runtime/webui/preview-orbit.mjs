/** Continuous mouse camera. Only the trusted preview canvas owns the pointer capture. */
export function createPreviewOrbit(redraw,viewId='runtime-preview'){
 let canvas=null,drag=null,yaw=-.6,pitch=.3,zoom=1,pan=[0,0];
 const state=()=>({yaw,pitch,zoom,pan:[...pan]});
 function release(){const held=drag;drag=null;if(canvas){canvas.dataset.dragging='false';if(held&&canvas.hasPointerCapture?.(held.pointerId))canvas.releasePointerCapture(held.pointerId);}}
 function down(e){if(![0,1,2].includes(e.button)||!Number.isFinite(e.clientX)||!Number.isFinite(e.clientY))return;release();e.preventDefault();drag={pointerId:e.pointerId,x:e.clientX,y:e.clientY,pan:e.button!==0};canvas.dataset.dragging='true';try{canvas.setPointerCapture(e.pointerId);}catch{release();}}
 function move(e){if(!drag||drag.pointerId!==e.pointerId||!Number.isFinite(e.clientX)||!Number.isFinite(e.clientY))return;const dx=e.clientX-drag.x,dy=e.clientY-drag.y;drag.x=e.clientX;drag.y=e.clientY;if(drag.pan){pan[0]+=dx;pan[1]+=dy;}else{yaw+=dx*.008;pitch=Math.max(-Math.PI/2+.001,Math.min(Math.PI/2-.001,pitch+dy*.008));}redraw();}
 function up(e){if(drag?.pointerId===e.pointerId)release();}
 function wheel(e){if(!Number.isFinite(e.deltaY))return;e.preventDefault();e.stopPropagation();const delta=e.deltaY*(e.deltaMode===1?16:e.deltaMode===2?Math.max(1,canvas.clientHeight):1);zoom=Math.max(.2,Math.min(6,zoom*Math.exp(-delta*.001)));redraw();}
 function detach(){release();if(!canvas)return;for(const [name,fn]of events)canvas.removeEventListener(name,fn);canvas=null;}
 const events=[['pointerdown',down],['pointermove',move],['pointerup',up],['pointercancel',up],['lostpointercapture',up],['wheel',wheel]];
 return {state,release,detach,reset(){release();yaw=-.6;pitch=.3;zoom=1;pan=[0,0];redraw();},attach(node){detach();canvas=node;canvas.dataset.dragging='false';for(const [name,fn]of events)canvas.addEventListener(name,fn,{passive:false});},capture(){return drag&&canvas?.isConnected&&canvas.hasPointerCapture?.(drag.pointerId)?{id:viewId,capture:canvas,pointerId:drag.pointerId}:null;}};
}
