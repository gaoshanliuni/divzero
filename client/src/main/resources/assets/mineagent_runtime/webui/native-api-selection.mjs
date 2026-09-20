export class NativeApiSelection {
  constructor(maximum=16){if(!Number.isInteger(maximum)||maximum<1||maximum>32)throw new Error('NATIVE_CODER_SELECTION_LIMIT');this.maximum=maximum;this.snapshot='';this.items=[];this.overlays=[];}
  reset(snapshot=''){if(snapshot!==''&&!/^[a-f0-9]{64}$/.test(snapshot))throw new Error('NATIVE_CODER_SNAPSHOT');this.snapshot=snapshot;this.items=[];this.overlays=[];}
  add(module,className){
    if(!/^[A-Za-z0-9_.-]{1,160}$/.test(module)||typeof className!=='string'||className.length<1||className.length>512||className.includes('/')||className.includes('\\')||className.includes('..'))throw new Error('NATIVE_CODER_TYPE');
    const key=module+'\n'+className;if(this.items.some(v=>v.module+'\n'+v.class===key))return false;this._capacity();this.items.push(Object.freeze({module,class:className}));return true;
  }
  addOverlay(id){if(typeof id!=='string'||!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(id))throw new Error('NATIVE_CODER_OVERLAY');if(this.overlays.includes(id))return false;this._capacity();this.overlays.push(id);return true;}
  remove(module,className){const before=this.items.length;this.items=this.items.filter(v=>v.module!==module||v.class!==className);return this.items.length!==before;}
  removeOverlay(id){const before=this.overlays.length;this.overlays=this.overlays.filter(v=>v!==id);return this.overlays.length!==before;}
  clear(){this.items=[];this.overlays=[];}
  has(module,className){return this.items.some(v=>v.module===module&&v.class===className);}
  hasOverlay(id){return this.overlays.includes(id);}
  size(){return this.items.length+this.overlays.length;}
  _capacity(){if(this.size()>=this.maximum)throw new Error('NATIVE_CODER_SELECTION_LIMIT');}
  wire(){return this.snapshot&&this.size()?{snapshot:this.snapshot,items:this.items.map(v=>({...v})),overlays:[...this.overlays]}:{snapshot:'',items:[],overlays:[]};}
}
