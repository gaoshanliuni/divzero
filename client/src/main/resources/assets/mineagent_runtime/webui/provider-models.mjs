/** Metadata only: the bridge keeps credentials outside the browser. */
export const modelError=code=>({MODELS_KEY_REQUIRED:'请先保存 API Key',MODELS_URL_REQUIRED:'请先保存 API URL',MODELS_AUTH_FAILED:'Key 无效或无权读取模型',MODELS_UNSUPPORTED:'该地址不支持 models，请检查 API URL',MODELS_EMPTY:'接口没有返回可选模型',MODELS_RATE_LIMITED:'服务限流，请稍后刷新',MODELS_REDIRECT_DENIED:'请保存重定向后的最终 API URL',MODELS_FORBIDDEN:'没有管理 Provider 的权限',MODELS_TIMEOUT:'获取超时，请点击刷新'})[code]||'模型列表读取失败，请点击刷新';
export class ModelCatalog {
 constructor(send,update,{schedule=setTimeout,cancel=clearTimeout}={}){Object.assign(this,{send,update,schedule,cancel});this.epoch=0;this.state=null;this.timer=null;}
 reset(){this.epoch++;if(this.timer!==null)this.cancel(this.timer);this.timer=null;this.state=null;}
 async load({refresh=false,offset=0,query=''}={}){
  this.reset();const ticket=this.epoch;this.state={status:'LOADING',models:[]};this.update(this.state);
  const poll=async()=>{try{const r=await this.send('settingsAction',{kind:'models',refresh,offset,query});if(ticket!==this.epoch)return;
   if(r.code!=='OBSERVED')throw new Error('MODELS_UNAVAILABLE');const state=JSON.parse(r.values.state);
   if(!['READY','LOADING','ERROR','NOT_CONFIGURED'].includes(state.status)||!Array.isArray(state.models))throw new Error('MODELS_INVALID_RESPONSE');
   this.state=state;this.update(state);if(state.status==='LOADING'){refresh=false;this.timer=this.schedule(poll,800);}
  }catch{if(ticket===this.epoch){this.state={status:'ERROR',error:'MODELS_UNAVAILABLE',models:[]};this.update(this.state);}}};
  await poll();
 }
}

export function validCustomModel(value){return typeof value==='string'&&value.trim().length>0&&value.length<=256&&!/[\u0000-\u001f\u007f-\u009f]/u.test(value);}
