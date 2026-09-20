export const bodyLabels={RESTORING:'正在加载身体',RETURNING:'等待原生返回',SPECTATOR:'观战 · 暂不可行动',BUSY:'执行任务中',READY:'可以行动',STOPPING:'正在保存退出',UNAVAILABLE:'身体暂不可用'};
export const modeLabel=value=>({CREATOR:'创造',SURVIVAL:'生存',creative:'创造',survival:'生存',spectator:'观战',adventure:'冒险'}[value]||'未就绪');
export function agentError(code){return ({STALE_AGENT_REVISION:'资料已被更新，草稿仍保留。请重新读取后再修改。',AGENT_CREATION_REMOVED:'这个创建请求对应的 AI 已被删除，不会重新创建。',AGENT_LIMIT:'AI 数量已达上限。',AGENT_NAME_EXISTS:'已有同名 AI，请换一个名称。',AGENT_NAME_INVALID:'请填写 1–32 个字符的单行名称。',PERMISSION_DENIED:'没有执行此操作的权限。',FORBIDDEN:'没有执行此操作的权限。',BODY_UNAVAILABLE:'身体尚不可行动，请先等待加载或调整模式。',STALE_TASK_REVISION:'原任务已变化，请关闭此确认并重新查看任务。',TASK_REPLAN_NOT_AVAILABLE:'当前任务不能从这里重新规划。'})[code]||code;}
export class OperationDraft {
  fingerprint='';operationId=null;
  request(args,uuid){const key=JSON.stringify(args);if(key!==this.fingerprint){this.fingerprint=key;this.operationId=null;}this.operationId??=uuid();return {...args,operationId:this.operationId};}
  reset(){this.fingerprint='';this.operationId=null;}
}
export class AgentEditDraft {
  revision=0;name='';mode='CREATOR';stale=false;nameDirty=false;modeDirty=false;baseName='';baseMode='CREATOR';
  get dirty(){return this.nameDirty||this.modeDirty;}
  set dirty(value){this.nameDirty=this.modeDirty=!!value;}
  update(value){if(value.revision<this.revision)return false;this.stale=this.dirty&&this.revision!==value.revision;if(!this.dirty)this.revision=value.revision;if(!this.stale){if(!this.nameDirty)this.name=this.baseName=value.name;if(!this.modeDirty)this.mode=this.baseMode=value.requestedMode;}return true;}
  edit(name,mode){this.name=name;this.mode=mode;this.nameDirty=name!==this.baseName;this.modeDirty=mode!==this.baseMode;}
  request(kind,id,uuid,operation,confirmed=false){
    if(this.stale)throw new Error('STALE_AGENT_REVISION');
    if(kind==='rename'&&(!this.name.trim()||[...this.name.trim()].length>32||/[\u0000-\u001f\u007f]/.test(this.name)))throw new Error('AGENT_NAME_INVALID');
    if(kind==='mode'&&!confirmed)throw new Error('AGENT_CONFIRM_REQUIRED');
    return operation.request({kind,agentId:id,expectedRevision:this.revision,...(kind==='rename'?{name:this.name.trim()}:{mode:this.mode,confirmed:true})},uuid);
  }
  committed(revision,kind){this.revision=Number(revision);this.stale=false;if(kind==='rename'){this.nameDirty=false;this.baseName=this.name.trim();}if(kind==='mode'){this.modeDirty=false;this.baseMode=this.mode;}}
}
