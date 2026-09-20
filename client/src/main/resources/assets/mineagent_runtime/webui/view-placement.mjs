export function opacityHidden(value){return value!==undefined&&Math.round(validateOpacity(value)*255)===0;}
export function validateOpacity(value){if(value!==undefined&&(!Number.isFinite(value)||value<0||value>1))throw new Error('UI_VIEW_SETTINGS_OPACITY');return value;}
const anchors=new Set(['TOP_LEFT','TOP_RIGHT','BOTTOM_LEFT','BOTTOM_RIGHT','CENTER']);
export function reflowAnchoredLayout(view,area,dragging){
 if(dragging)return null;
 const placement=view?.layoutSource==='PACKAGE'?view.placement:view?.layoutSource==='AGENT'?view.agentPlacement:null;
 return placement?resolvePlacement(placement,area).bounds:null;
}
export function savedLayouts(views,restored){
 const layouts={...restored};for(const [id,view]of views){const value={bounds:view.bounds,minimized:view.minimized};if(typeof view.pinned==='boolean')value.pinned=view.pinned;if(view.opacity!==undefined)value.opacity=validateOpacity(view.opacity);if(view.layoutSource==='AGENT'&&view.agentPlacement)value.agentPlacement={...view.agentPlacement};if(!view.layoutKey||view.layoutSource!=='PACKAGE')layouts[view.layoutKey||id]=value;if(view.mode==='PASSIVE_HUD')layouts[id]=value;}return layouts;
}
export function resolvePlacement(request,area){
 const {anchor,width,height,offsetX=0,offsetY=0}=request;validateOpacity(request.opacity);
 if(!anchors.has(anchor)||![width,height,offsetX,offsetY,area.x,area.y,area.width,area.height].every(Number.isFinite)||width<64||height<64||width>8192||height>8192||Math.abs(offsetX)>32768||Math.abs(offsetY)>32768||area.width<=0||area.height<=0)throw new Error('UI_VIEW_SETTINGS_VALUE');
 const w=Math.min(Math.max(240,width),area.width),h=Math.min(Math.max(100,height),area.height);
 const x=area.x+(anchor.endsWith('RIGHT')?area.width-w:anchor==='CENTER'?(area.width-w)/2:0)+offsetX;
 const y=area.y+(anchor.startsWith('BOTTOM')?area.height-h:anchor==='CENTER'?(area.height-h)/2:0)+offsetY;
 const bounds={x:Math.max(area.x,Math.min(x,area.x+area.width-w)),y:Math.max(area.y,Math.min(y,area.y+area.height-h)),width:w,height:h};
 return {bounds,adjusted:bounds.x!==x||bounds.y!==y||w!==width||h!==height,coordinateSpace:'VIEWPORT_CSS_PIXELS',anchorSpace:'AVAILABLE_AREA'};
}
export function chooseInitialLayout(defaults,saved,area,edited){
 if(edited)return null;
 const opacity=saved?.opacity!==undefined?{opacity:validateOpacity(saved.opacity)}:{};
 if(saved?.agentPlacement)return {...opacity,...(typeof saved.pinned==='boolean'?{pinned:saved.pinned}:{}),...resolvePlacement(saved.agentPlacement,area),agentPlacement:{...saved.agentPlacement},minimized:saved.minimized===true,source:'AGENT'};
 if(saved?.bounds)return {...saved,source:'SAVED'};
 return defaults?{...resolvePlacement(defaults,area),minimized:false,source:'PACKAGE'}:null;
}
export function applyLivePlacement(view,request,area,visible,dragging,opacitySupported=false){
 if(!view||!visible||dragging)throw new Error('NOT_INTERACTABLE');
 if(!Number.isSafeInteger(request.expectedLayoutRevision)||view.layoutRevision!==request.expectedLayoutRevision)throw new Error('STALE_LAYOUT');
 validateOpacity(request.placement.opacity);if(request.placement.opacity!==undefined&&!opacitySupported)throw new Error('UI_OPACITY_BACKEND_REQUIRED');
 const result=resolvePlacement(request.placement,area);if(request.placement.opacity!==undefined)view.opacity=request.placement.opacity;view.bounds={...result.bounds};view.agentPlacement={...request.placement,...(view.opacity!==undefined?{opacity:view.opacity}:{})};view.layoutSource='AGENT';return result;
}
