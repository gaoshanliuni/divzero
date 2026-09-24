import {t as __uiT,tf as __uiF} from './i18n.mjs';
export function canRecover(conversation,message,turn){
 return conversation?.state==='ACTIVE'&&!conversation.activeOperation&&message?.role==='ASSISTANT'&&!!message.errorCode&&message.messageId===turn?.assistantMessageId&&['FAILED','INTERRUPTED'].includes(turn.requestState);
}
export function recoveryText(turn){
 const op=turn?.operationId;if(typeof op!=='string'||!/^[a-f0-9-]{36}$/i.test(op))throw new Error('RECOVERY_OPERATION_INVALID');
 return __uiF("请核对上次请求 {0} 后继续原需求：先inspect_operations读取持久回执，再观察实际状态。不要重放已执行或UNKNOWN的写操作；明确拒绝且未执行的步骤可以修正参数后重新提出，继续尚未开始的部分。若状态仍不明，只说明待核对项，不猜测完成。",op);
}
