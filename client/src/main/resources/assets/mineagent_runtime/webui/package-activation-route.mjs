// Lifecycle dispatch is not execution: local code, resource reloads and restarts keep their own review.
export function packageActivationRoute(head){
 if(head?.activationMode==='BOOT_EXTENSION')return 'boot';
 if(head?.activationMode==='WORLD_REOPEN'||head?.activationMode==='DATA_RELOAD')return 'data';
 if(head?.activationMode==='RESOURCE_RELOAD')return 'resource';
 if(head?.activationMode==='HOT_RUNTIME'&&head.worldAvailable)return 'world';
 if(head?.clientScriptAvailable||head?.clientJavaAvailable)return 'client';
 if(head?.javaAvailable)return 'java';
 if(head?.scriptAvailable)return 'script';
 return 'tools';
}
