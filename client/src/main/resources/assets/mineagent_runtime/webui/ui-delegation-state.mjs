export class UiDelegationDraft {
  agentId='';goal='';expectedTitle='';confirmed=false;operationId=null;presentationOnly=false;
  edit(agentId,goal,expectedTitle,confirmed,presentationOnly=false){
    if(typeof agentId!=='string'||agentId.length>36||typeof goal!=='string'||goal.length>8192||typeof expectedTitle!=='string'||expectedTitle.length>256)throw new Error('UI_AGENT_INTENT_INVALID');
    if(agentId!==this.agentId||goal!==this.goal||expectedTitle!==this.expectedTitle||confirmed!==this.confirmed||presentationOnly!==this.presentationOnly)this.operationId=null;
    Object.assign(this,{agentId,goal,expectedTitle,confirmed:confirmed===true,presentationOnly:presentationOnly===true});
  }
  request(uuid){if(!this.confirmed||!this.agentId||!this.goal.trim()||!this.expectedTitle.trim())throw new Error('EXPLICIT_UI_DELEGATION_REQUIRED');this.operationId??=uuid();return {agentId:this.agentId,goal:this.goal,expectedTitle:this.expectedTitle,confirmed:true,operationId:this.operationId,...(this.presentationOnly?{presentationOnly:true}:{})};}
}
export class WorldUiDelegationDraft {
  agentId='';goal='';expected='';confirmed=false;operationId=null;
  edit(agentId,goal,expected,confirmed){
    if(typeof agentId!=='string'||agentId.length>36||typeof goal!=='string'||goal.length>8192||typeof expected!=='string'||expected.length>8192)throw new Error('WORLD_UI_AGENT_INPUT');
    if(agentId!==this.agentId||goal!==this.goal||expected!==this.expected||confirmed!==this.confirmed)this.operationId=null;
    Object.assign(this,{agentId,goal,expected,confirmed:confirmed===true});
  }
  request(uuid){if(!this.agentId||!this.goal.trim()||!this.expected.trim()||!this.confirmed)throw new Error('EXPLICIT_UI_DELEGATION_REQUIRED');
    let data;try{data=JSON.parse(this.expected);}catch{throw new Error('WORLD_UI_EXPECTATION');}if(!data||Array.isArray(data)||typeof data.text!=='string'||!data.text.trim()||!data.equals||Array.isArray(data.equals)||typeof data.equals!=='object'||!Object.keys(data.equals).length)throw new Error('WORLD_UI_EXPECTATION');
    this.operationId??=uuid();return {agentId:this.agentId,goal:this.goal,expected:this.expected,confirmed:true,operationId:this.operationId};}
}
export function canDelegateView(state){return state?.rendered===true&&state.actorKind==='PLAYER'&&Array.isArray(state.capabilities)&&
  (state.pageOnly===true?state.capabilities.length===2&&state.capabilities.includes('ui.observe')&&state.capabilities.includes('ui.act'):state.capabilities.includes('scoreview.patch')||state.capabilities.length===2&&state.capabilities.includes('worldui.read')&&state.capabilities.includes('worldui.action'));}
