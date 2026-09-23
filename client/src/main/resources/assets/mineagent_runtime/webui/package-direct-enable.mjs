// A catalog click authorizes exactly this signed package/version, not future revisions or retries.
export async function enableReloadPackage({head,send,current,uuid,sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms))}){
 const check=()=>{if(!current())throw new Error('PACKAGE_ENABLE_CONTEXT_CHANGED');};
 async function request(channel,args){check();const result=await send(channel,args);check();return result;}
 function observed(r){if(r.code!=='OBSERVED'||r.values?.errorCode)throw new Error(r.values?.errorCode||r.code||'PACKAGE_ENABLE_READ_FAILED');return JSON.parse(r.values.state);}
 const actual=observed(await request('packageCatalog',{kind:'package',packageId:head.packageId,headRevision:String(head.revision),headHash:head.canonicalSha256}));
 if(actual.revision!==head.revision||actual.canonicalSha256!==head.canonicalSha256||actual.activationMode!==head.activationMode)throw new Error('PACKAGE_ENABLE_SOURCE_CHANGED');
 if(head.activationMode==='RESOURCE_RELOAD'){
  const downloaded=await request('resourcePack',{kind:'download',packageId:head.packageId,packageRevision:String(head.revision),canonical:head.canonicalSha256});
  if(!['ACCEPTED','DOWNLOADED_NOT_APPROVED'].includes(downloaded.code))throw new Error(downloaded.code||'RESOURCE_PACK_DOWNLOAD_FAILED');
  if(downloaded.code==='ACCEPTED'){
   const deadline=Date.now()+120000;
   while(true){const progress=await request('resourcePack',{kind:'read',offset:'0'});if(progress.code)throw new Error(progress.code);
    if(progress.download?.packageId&&progress.download.packageId!==head.packageId)throw new Error('RESOURCE_PACK_DOWNLOAD_CONTEXT_CHANGED');
    if(!progress.download?.packageId&&progress.downloadOutcome==='DOWNLOADED_NOT_APPROVED')break;
    if(progress.downloadOutcome!=='DOWNLOADING')throw new Error(progress.downloadOutcome||'RESOURCE_PACK_DOWNLOAD_UNKNOWN');
    if(Date.now()>=deadline)throw new Error('RESOURCE_PACK_DOWNLOAD_WAIT_TIMEOUT');await sleep(500);check();
   }
  }
  let view,asset,offset=0;
  do{view=await request('resourcePack',{kind:'read',offset:String(offset)});if(view.code)throw new Error(view.code);asset=view.items?.find(a=>a.packageId===head.packageId&&a.canonical===head.canonicalSha256&&(!downloaded.filename||a.filename===downloaded.filename));if(asset)break;if(!view.more)throw new Error('RESOURCE_PACK_ASSET_NOT_FOUND');if(!Number.isSafeInteger(view.nextOffset)||view.nextOffset<=offset)throw new Error('RESOURCE_PACK_CURSOR');offset=view.nextOffset;}while(true);
  if(asset.packageId!==head.packageId||asset.canonical!==head.canonicalSha256)throw new Error('RESOURCE_PACK_SOURCE_CHANGED');
  if(asset.loadedNow)return {code:'ALREADY_ACTIVE'};if(!asset.canEnable)throw new Error(asset.error||'RESOURCE_PACK_NOT_READY');
  const result=await request('resourcePack',{kind:'change',operationId:uuid(),filename:asset.filename,action:'ENABLE',revision:String(asset.revision),selection:view.selection,environment:view.environment,confirmed:'true'});
  if(result.code!=='ACCEPTED')throw new Error(result.code||'RESOURCE_PACK_ENABLE_UNKNOWN');return result;
 }
 if(!['DATA_RELOAD','WORLD_REOPEN'].includes(head.activationMode))throw new Error('PACKAGE_ENABLE_LIFECYCLE');
 const view=observed(await request('dataPack',{kind:'read',packageId:head.packageId,packageRevision:String(head.revision),offset:'0',operationId:''}));
 if(view.packageRevision!==head.revision||view.canonical!==head.canonicalSha256||view.mode!==head.activationMode)throw new Error('PACKAGE_ENABLE_SOURCE_CHANGED');
 if(view.loadedNow)return {code:'ALREADY_ACTIVE'};const reopen=head.activationMode==='WORLD_REOPEN';
 if(reopen?!!view.reopenPlan?.operation:!view.canEnable){if(reopen&&view.reopenPlan?.operation)return {code:'REOPEN_PLAN_EXISTS'};throw new Error(view.error||'DATA_PACK_NOT_READY');}
 if(reopen&&!view.canStageReopen)throw new Error(view.error||'WORLD_REOPEN_NOT_READY');
 const result=await request('dataPack',{kind:'change',operationId:uuid(),packageId:head.packageId,packageRevision:String(head.revision),canonical:view.canonical,selection:view.selection,environment:view.environment,action:reopen?'ENABLE_AT_REOPEN':'ENABLE',confirmed:'true',reopenOperation:''});
 if(result.code!=='ACCEPTED'||result.values?.errorCode)throw new Error(result.values?.errorCode||result.code||'DATA_PACK_ENABLE_UNKNOWN');return result;
}
