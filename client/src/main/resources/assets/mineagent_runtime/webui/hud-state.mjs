export function restoreHudLayout(state,id,layout,humanEdited,width,height){
  const view=state.views.get(id);
  if(!view||view.mode!=='PASSIVE_HUD'||humanEdited)return false;
  if(!layout||typeof layout.minimized!=='boolean')throw new Error('HUD_LAYOUT_INVALID');
  const bounds={};for(const key of ['x','y','width','height']){
    if(!Number.isFinite(layout[key])||Math.abs(layout[key])>32768)throw new Error('HUD_LAYOUT_INVALID');bounds[key]=layout[key];
  }
  if(bounds.width<1||bounds.height<1)throw new Error('HUD_LAYOUT_INVALID');
  state.layout(id,bounds,width,height);state.minimize(id,layout.minimized);return true;
}
