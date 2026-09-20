import {resolveInputTarget} from './input-target.mjs';

// Runs only in the trusted root. The request contains no typed text or page-supplied coordinates.
export function resolveAtlasInput(request,doc,host,frame,token,rootCapture=null){
  if(!frame||!request.atlasToken||request.atlasToken!==token||host.innerWidth!==frame.width||host.innerHeight!==frame.height)throw new Error('STALE_ATLAS_INPUT');
  let view;
  if(request.held&&rootCapture?.id===request.atlasView&&rootCapture.capture?.hasPointerCapture(rootCapture.pointerId))view=request.atlasView;
  else view=resolveInputTarget(request,doc,host.innerWidth,host.innerHeight);
  if(request.kind!=='keyboard'&&view!==(request.atlasView==='chrome:atlas'?'':request.atlasView))throw new Error('STALE_ATLAS_TARGET');
  if(view){const surface=frame.surfaces.find(s=>s.id===view);if(!surface||Math.round(surface.opacity*255)===0)throw new Error('VIEW_NOT_RENDERED');}
  return view;
}
