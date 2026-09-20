export class AppearanceDraft {
  constructor(agentId){this.agentId=agentId;this.revision=null;this.model='';this.texture='';this.animation='';this.dirty=false;this.requestId=null;}
  observe(s){
    if(s?.agentId!==this.agentId||!Number.isSafeInteger(s.revision)||s.revision<0||this.dirty||this.revision!==null&&s.revision<this.revision)return false;
    this.revision=s.revision;this.model=s.model??'';this.texture=s.texture??'';this.animation=s.animation??'';return true;
  }
  edit(model,texture,animation){if([model,texture,animation].some(v=>typeof v!=='string'||v.length>128))throw new Error('APPEARANCE_INPUT_LIMIT');if(this.model!==model||this.texture!==texture||this.animation!==animation){this.model=model;this.texture=texture;this.animation=animation;this.dirty=true;this.requestId=null;}}
  request(id){if(this.revision===null||!this.model.trim())throw new Error('APPEARANCE_NOT_READY');return {agentId:this.agentId,expectedRevision:this.revision,model:this.model,texture:this.texture,animation:this.animation,requestId:this.requestId??=id()};}
  choiceRequest(id){
    if(this.revision===null)throw new Error('APPEARANCE_NOT_READY');
    const data={agentId:this.agentId,expectedRevision:this.revision,model:this.model,texture:this.texture,animation:this.animation};
    const key=JSON.stringify(data);if(this.choiceKey!==key){this.choiceKey=key;this.choiceId=id();}
    return {...data,requestId:this.choiceId};
  }
  accept(r){if(r?.agentId!==this.agentId||r.requestId!==this.requestId)return false;if(r.accepted){if(!Number.isSafeInteger(r.revision)||r.revision<this.revision)throw new Error('APPEARANCE_REVISION');this.revision=r.revision;this.dirty=false;this.requestId=null;}return true;}
}
