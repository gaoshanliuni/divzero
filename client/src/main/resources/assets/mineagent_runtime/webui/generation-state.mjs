export class GenerationDraft {
  agentId = ''; prompt = ''; operationId = null; edited = false; purpose='UI_PACKAGE';
  edit(agentId, prompt,purpose='UI_PACKAGE') {
    if(!['UI_PACKAGE','WORLD_CONTENT'].includes(purpose))throw new Error('GENERATION_PURPOSE');
    if (typeof agentId !== 'string' || agentId.length > 36 || typeof prompt !== 'string' || prompt.length > 8192) throw new Error('GENERATION_DRAFT_INVALID');
    if (agentId !== this.agentId || prompt !== this.prompt||purpose!==this.purpose) this.operationId = null;
    this.agentId = agentId; this.prompt = prompt; this.purpose=purpose;this.edited = true;
  }
  request(uuid) {
    if (!this.agentId || !this.prompt.trim()) throw new Error('GENERATION_INPUT_REQUIRED');
    this.operationId ??= uuid();
    return { agentId: this.agentId, prompt: this.prompt, operationId: this.operationId,...(this.purpose==='WORLD_CONTENT'?{purpose:this.purpose}:{}) };
  }
  snapshot() { return { agentId: this.agentId, prompt: this.prompt, operationId: this.operationId,...(this.purpose==='WORLD_CONTENT'?{purpose:this.purpose}:{}) }; }
  restore(value) {
    if (this.edited || !value || typeof value.agentId !== 'string' || value.agentId.length > 36 || typeof value.prompt !== 'string' || value.prompt.length > 8192
        || (value.operationId != null && (typeof value.operationId !== 'string' || value.operationId.length > 36))) return;
    if(!['UI_PACKAGE','WORLD_CONTENT'].includes(value.purpose??'UI_PACKAGE'))return;
    this.agentId = value.agentId; this.prompt = value.prompt; this.operationId = value.operationId;this.purpose=value.purpose??'UI_PACKAGE';
  }
}
export class BindingDrafts {
  values = new Map(); edited = new Set();
  source(packageId) { return this.values.get(packageId)?.sourceId ?? ''; }
  select(packageId, sourceId) {
    if(typeof packageId!=='string'||packageId.length>36||typeof sourceId!=='string'||sourceId.length>36)throw new Error('BINDING_DRAFT_INVALID');
    if(!this.values.has(packageId)&&this.values.size>=64)throw new Error('BINDING_DRAFT_BUDGET');
    const old=this.values.get(packageId);
    this.values.set(packageId,old?.sourceId===sourceId?old:{sourceId,operationId:null,title:''});this.edited.add(packageId);
  }
  request(packageId,title,uuid) {
    const value=this.values.get(packageId);if(!value?.sourceId||typeof title!=='string'||title.length>2048)throw new Error('BINDING_SOURCE_REQUIRED');
    if(value.title!==title){value.title=title;value.operationId=null;}
    value.operationId??=uuid();return {sourceId:value.sourceId,title,operationId:value.operationId};
  }
  snapshot(){return [...this.values].map(([packageId,value])=>({packageId,...value}));}
  restore(values){
    if(!Array.isArray(values)||values.length>64)return;
    for(const value of values){
      if(!value||typeof value.packageId!=='string'||value.packageId.length>36||this.edited.has(value.packageId)
        ||typeof value.sourceId!=='string'||value.sourceId.length>36||typeof value.title!=='string'||value.title.length>2048
        ||(value.operationId!=null&&(typeof value.operationId!=='string'||value.operationId.length>36)))continue;
      this.values.set(value.packageId,{sourceId:value.sourceId,title:value.title,operationId:value.operationId});
    }
  }
}

const repairUuid=value=>typeof value==='string'&&/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value);
const repairSourceValid=value=>value&&repairUuid(value.sourceOperationId)&&repairUuid(value.agentId)&&Number.isSafeInteger(value.jobRevision)&&value.jobRevision>0&&/^[a-f0-9]{64}$/.test(value.rawOutputSha256)&&['UI_PACKAGE','WORLD_CONTENT'].includes(value.purpose);
export class GenerationRepairDrafts {
  values=new Map(); edited=new Set();
  matches(job,value=this.values.get(job?.operationId)){
    return !!(value&&job?.state==='FAILED'&&job.repairable===true&&job.operationId===value.sourceOperationId&&job.agentId===value.agentId&&job.jobRevision===value.jobRevision&&job.rawOutputSha256===value.rawOutputSha256&&job.purpose===value.purpose);
  }
  begin(job){
    const source={sourceOperationId:job?.operationId,agentId:job?.agentId,jobRevision:job?.jobRevision,rawOutputSha256:job?.rawOutputSha256,purpose:job?.purpose};
    if(!repairSourceValid(source)||job?.state!=='FAILED'||job.repairable!==true)throw new Error('GENERATION_REPAIR_UNAVAILABLE');
    const old=this.values.get(source.sourceOperationId);if(old){if(!this.matches(job,old))throw new Error('GENERATION_REPAIR_SOURCE_CHANGED');return old;}
    if(this.values.size>=32)throw new Error('GENERATION_REPAIR_DRAFT_BUDGET');
    const value={...source,prompt:'',operationId:null};this.values.set(source.sourceOperationId,value);return value;
  }
  edit(sourceOperationId,prompt){
    const value=this.values.get(sourceOperationId);if(!value||typeof prompt!=='string'||prompt.length>8192)throw new Error('GENERATION_REPAIR_DRAFT_INVALID');
    if(value.prompt!==prompt)value.operationId=null;value.prompt=prompt;this.edited.add(sourceOperationId);
  }
  request(job,confirmed,uuid){
    const value=this.values.get(job?.operationId);if(!this.matches(job,value))throw new Error('GENERATION_REPAIR_SOURCE_CHANGED');
    if(confirmed!==true||!value.prompt.trim())throw new Error('GENERATION_REPAIR_CONSENT_AND_PROMPT_REQUIRED');
    const id=value.operationId??uuid();if(!repairUuid(id)||id===value.sourceOperationId)throw new Error('GENERATION_REPAIR_NEW_OPERATION_REQUIRED');value.operationId=id;
    return {agentId:value.agentId,prompt:value.prompt,operationId:id,repairSourceOperationId:value.sourceOperationId,repairSourceRevision:value.jobRevision,repairSourceSha256:value.rawOutputSha256,confirmed:true};
  }
  snapshot(){return [...this.values.values()].map(v=>({...v}));}
  restore(values){
    if(!Array.isArray(values)||values.length>32)return;
    for(const v of values){if(!repairSourceValid(v)||this.edited.has(v.sourceOperationId)||typeof v.prompt!=='string'||v.prompt.length>8192||(v.operationId!=null&&(!repairUuid(v.operationId)||v.operationId===v.sourceOperationId)))continue;
      if(!this.values.has(v.sourceOperationId)&&this.values.size>=32)break;
      this.values.set(v.sourceOperationId,{sourceOperationId:v.sourceOperationId,agentId:v.agentId,jobRevision:v.jobRevision,rawOutputSha256:v.rawOutputSha256,purpose:v.purpose,prompt:v.prompt,operationId:v.operationId??null});
    }
  }
}
