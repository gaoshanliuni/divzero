export function resolveInputTarget(request,doc,cssWidth,cssHeight){
  if(!request||!['pointer','keyboard','motion','release'].includes(request.kind)||!Number.isFinite(request.width)||request.width<=0||!Number.isFinite(request.height)||request.height<=0
    ||!Number.isFinite(cssWidth)||cssWidth<=0||!Number.isFinite(cssHeight)||cssHeight<=0)throw new Error('UI_INPUT_TARGET_INVALID');
  let element;
  if(request.kind==='keyboard')element=doc.activeElement;
  else{
    if(!Number.isFinite(request.x)||!Number.isFinite(request.y))throw new Error('UI_INPUT_COORDINATES');
    element=doc.elementFromPoint(request.x*cssWidth/request.width,request.y*cssHeight/request.height);
  }
  const window=element?.closest('.window');
  return window?.dataset.viewId??'';
}
