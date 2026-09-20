export function feedbackRecoveryText(value){
  if(value?.verified===true&&value.status==='APPLIED')return '共享事务已有 APPLIED 回执：该笔写入已提交。执行流程状态仍独立保留；不会重放，当前值需另行读取。';
  if(value?.verified===true&&value.status==='CONFLICT')return '共享事务已有 CONFLICT 回执：该笔条件事务未写入。不会自动重试，不能据此推断当前值。';
  if(value?.status==='NOT_RECORDED')return '未找到与原计划和身份匹配的共享事务回执。不自动重放，也不据此保证任意 Native 副作用没有发生。';
  if(value?.status==='NO_PLAN_RECORDED')return '没有持久化的确定性计划；执行结果未确认，不自动重放。';
  if(value?.status==='NOT_APPLICABLE')return '此反馈不是确定性共享事务；请查看已有回复或记录状态。';
  return '当前授权或资源不足以重新核验事务回执；历史记录保留，不自动恢复执行。';
}
export function renderDeliveryDraftStatus(node,data,send,report){
  if(!node)return;node.dataset.deliveryDraftStatus=data.status;
  let group=node.querySelector('[data-delivery-draft-controls]');if(!group){group=document.createElement('span');group.dataset.deliveryDraftControls='true';group.className='delivery-draft-controls';node.querySelector('.titlebar')?.append(group);}
  group.replaceChildren();const label=document.createElement('span');label.textContent=({LOADING:'草稿读取中',READY:'本地草稿',SAVED:'草稿已保存',AVAILABLE:'有待恢复草稿',RESTORING:'恢复中',RESTORED:'草稿已恢复',FAILED:'草稿未确认',SESSION_ONLY:'本次不保存草稿'})[data.status]||'草稿状态未知';group.append(label);
  label.title='仅本机普通表单值，不含密码/隐藏字段，不恢复 JS 状态或旧提交；不保证任意框架状态同步。';
  for(const [action,text,show] of [['restore','恢复草稿',data.restoreAvailable&&data.status!=='RESTORING'],['continue','用当前表单',['AVAILABLE','FAILED','LOADING'].includes(data.status)]]){
    if(!show)continue;const b=document.createElement('button');b.textContent=text;b.dataset.action=action==='restore'?'restore-delivery-draft':'continue-delivery-draft';b.title=action==='restore'?'明确以旧草稿替换当前普通表单值；不触发 input/change/submit。':'保留当前表单继续；本次窗口不再恢复或保存本地草稿。';b.onclick=async()=>{b.disabled=true;try{const r=await send('deliveryDraftAction',{viewId:data.viewId,action});if(r.code!=='APPLIED')throw new Error(r.values?.errorCode||r.code);}catch(e){report(e);}finally{b.disabled=false;}};group.append(b);
  }
}
