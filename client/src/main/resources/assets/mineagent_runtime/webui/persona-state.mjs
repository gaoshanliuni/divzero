export function editablePersonaAgents(agents){return Array.isArray(agents)?agents.filter(a=>a?.personaEditable===true):[];}
export class PersonaDraft {
  constructor(agentId){this.agentId=agentId;this.revision=null;this.text='';this.dirty=false;this.editRevision=0;this.requestId=null;this.requestKey=null;}
  observe(p){if(p?.agentId!==this.agentId||!Number.isSafeInteger(p.revision)||p.revision<0||typeof p.text!=='string'||p.text.length>8192||this.dirty||this.revision!==null&&p.revision<this.revision)return false;this.revision=p.revision;this.text=p.text;return true;}
  edit(text){if(typeof text!=='string'||text.length>8192)throw new Error('PERSONA_INPUT_LIMIT');if(text!==this.text){this.text=text;this.dirty=true;this.editRevision++;this.requestId=null;this.requestKey=null;}}
  request(uuid,reset=false){if(this.revision===null)throw new Error('PERSONA_NOT_READY');const text=reset?'':this.text;const key=JSON.stringify([this.agentId,this.revision,text]);if(key!==this.requestKey){this.requestKey=key;this.requestId=uuid();}return {agentId:this.agentId,expectedRevision:this.revision,text,requestId:this.requestId,editRevision:this.editRevision,reset};}
  accept(request,result){const p=result?.persona;if(!result?.accepted||request.agentId!==this.agentId||p?.agentId!==this.agentId||!Number.isSafeInteger(p.revision)||p.revision<0||result.appliedRevision!==p.revision||request.expectedRevision!==this.revision)return false;
    this.revision=p.revision;if(request.editRevision===this.editRevision){this.text=p.text;this.dirty=false;}this.requestId=null;this.requestKey=null;return true;}
  snapshot(){return {version:1,agentId:this.agentId,revision:this.revision,text:this.text,dirty:this.dirty,editRevision:this.editRevision,requestId:this.requestId,requestKey:this.requestKey};}
  static restore(agentId,value){const d=new PersonaDraft(agentId);if(value?.version!==1||value.agentId!==agentId||typeof value.text!=='string'||value.text.length>8192||!(value.revision===null||Number.isSafeInteger(value.revision)&&value.revision>=0))return d;
    d.revision=value.revision;d.text=value.text;d.dirty=value.dirty===true;d.editRevision=Number.isSafeInteger(value.editRevision)&&value.editRevision>=0?value.editRevision:0;
    if(typeof value.requestId==='string'&&value.requestId.length<=64&&typeof value.requestKey==='string'&&value.requestKey.length<=12000){d.requestId=value.requestId;d.requestKey=value.requestKey;}return d;}
}
