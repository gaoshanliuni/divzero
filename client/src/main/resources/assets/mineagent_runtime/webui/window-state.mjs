const modes = new Set(['PASSIVE_HUD', 'INTERACTIVE_FLOATING', 'MODAL', 'PREVIEW', 'CONTENT']);
export function taskbarAction(view,focused){return view.minimized?'RESTORE':view.mode!=='PASSIVE_HUD'&&focused!==view.id?'FOCUS':'HIDE';}
export function containerBounds(width,height){return {x:18,y:80,width:Math.max(1,Math.min(960,width-36)),height:Math.max(1,Math.min(620,height-100))};}
export class WindowState {
  constructor(limit = 12) { this.limit = limit; this.views = new Map(); this.focused = null; this.z = 0; this.interacting = true; this.workspaceVisible = true; }
  interaction(active) { this.interacting = active === true; if (!this.interacting) this.focused = null; }
  workspace(active) { this.workspaceVisible=active===true;if(this.focused&&!this.visible(this.focused))this.focused=null; }
  visible(id) { const view = this.views.get(id); return !!view && !view.minimized && (view.pinned || this.interacting && (this.workspaceVisible || !['INTERACTIVE_FLOATING','MODAL'].includes(view.mode))); }
  pin(id, value) { const view=this.views.get(id);if(!view||view.mode==='MODAL'||typeof value!=='boolean')throw new Error('WINDOW_PIN_UNAVAILABLE');if(view.pinned!==value){view.pinned=value;view.windowRevision++;}if(this.focused===id&&!this.visible(id))this.focused=null; }
  open(id, mode) {
    if (typeof id !== 'string' || !id || !modes.has(mode)) throw new Error('INVALID_VIEW');
    if (this.views.has(id)) { this.minimize(id, false); return this.views.get(id); }
    if (this.views.size >= this.limit) throw new Error('UI_WINDOW_BUDGET');
    const view = { id, mode, pinned:mode==='PASSIVE_HUD', windowRevision:1, minimized: false, z: ++this.z, bounds: { x: 32, y: 64, width: mode==='PASSIVE_HUD'?420:520, height: mode==='PASSIVE_HUD'?300:420 } };
    this.views.set(id, view); return view;
  }
  focus(id) {
    const view = this.views.get(id);
    if (!view || view.mode === 'PASSIVE_HUD' || !this.visible(id)) return;
    this.focused = id; view.z = ++this.z;
  }
  minimize(id, value) {
    const view = this.views.get(id); if (!view) return;
    if(view.minimized!==value)view.windowRevision++;view.minimized = value;
    if (value && this.focused === id) this.focused = null;
  }
  close(id) { this.views.delete(id); if (this.focused === id) this.focused = null; }
  replace(oldId,newId) {
    const old=this.views.get(oldId),next=this.views.get(newId);
    if(!old||!next||oldId===newId||old.mode!==next.mode)throw new Error('WINDOW_REPLACEMENT_CONTEXT');
    const changedDefault=old.layoutSource==='PACKAGE'&&next.layoutSource==='PACKAGE'&&JSON.stringify(old.placement)!==JSON.stringify(next.placement);
    if(!changedDefault)next.bounds={...old.bounds};if(['PLAYER','SAVED','AGENT'].includes(old.layoutSource))next.layoutSource=old.layoutSource;
    if(old.layoutSource==='AGENT'&&old.agentPlacement)next.agentPlacement={...old.agentPlacement};
    next.minimized=old.minimized;next.pinned=old.pinned;next.windowRevision=old.windowRevision+1;next.z=old.z;
    if(this.focused===oldId)this.focused=newId;
    this.views.delete(oldId);return next;
  }
  layout(id, bounds, vw, vh) {
    const view = this.views.get(id); if (!view) return;
    for (const n of [...Object.values(bounds), vw, vh]) if (!Number.isFinite(n)) throw new Error('INVALID_BOUNDS');
    const width = Math.min(Math.max(240, bounds.width), Math.max(1, vw));
    const height = Math.min(Math.max(100, bounds.height), Math.max(1, vh));
    view.bounds = { x: Math.max(0, Math.min(bounds.x, vw - width)), y: Math.max(0, Math.min(bounds.y, vh - height)), width, height };
  }
}
