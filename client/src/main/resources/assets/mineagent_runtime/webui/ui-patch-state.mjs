export class UiPatchDraft {
  constructor(){this.packageId='';this.packageRevision=0;this.agentId='';this.prompt='';this.operationId=null;this.edited=false;}
  choose(packageId,revision,agentId){if(!packageId||!agentId||!Number.isSafeInteger(revision)||revision<1)throw new Error('UI_PATCH_CONTEXT');if(this.packageId!==packageId||this.packageRevision!==revision||this.agentId!==agentId){this.packageId=packageId;this.packageRevision=revision;this.agentId=agentId;this.operationId=null;this.prompt='';}this.edited=true;}
  edit(prompt){if(typeof prompt!=='string'||prompt.length>8192)throw new Error('UI_PATCH_PROMPT');if(this.prompt!==prompt){this.prompt=prompt;this.operationId=null;}this.edited=true;}
  request(uuid){if(!this.packageId||!this.agentId||this.packageRevision<1||!this.prompt.trim())throw new Error('UI_PATCH_REQUIRED');this.operationId??=uuid();return this.snapshot();}
  snapshot(){return {packageId:this.packageId,packageRevision:this.packageRevision,agentId:this.agentId,prompt:this.prompt,operationId:this.operationId};}
  restore(value){if(this.edited||!value)return;if(typeof value.packageId!=='string'||typeof value.agentId!=='string'||!Number.isSafeInteger(value.packageRevision)||value.packageRevision<1||typeof value.prompt!=='string'||value.prompt.length>8192)return;Object.assign(this,{packageId:value.packageId,packageRevision:value.packageRevision,agentId:value.agentId,prompt:value.prompt,operationId:typeof value.operationId==='string'?value.operationId:null});}
}
export function uiPatchRebuildRequest(job,rawHash,confirmed){
  if(confirmed!==true||job.state!=='FAILED'||job.rebuildAllowed!==true)throw new Error('UI_PATCH_REBUILD_OPERATOR_REQUIRED');
  if(typeof rawHash!=='string'||!/^[a-f0-9]{64}$/.test(rawHash))throw new Error('UI_PATCH_REBUILD_HASH');
  if(job.rawOutputSha256&&job.rawOutputSha256!==rawHash)throw new Error('UI_PATCH_REBUILD_CHANGED');
  return {action:'patchRebuild',operationId:job.operationId,rawHash,confirmed:true};
}
