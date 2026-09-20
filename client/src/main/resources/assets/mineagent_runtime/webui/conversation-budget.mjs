import {serviceBudgetErrors} from './service-call-budget.mjs';
export const conversationFieldBounds={
  'conversation.contextTokenBudget':{min:1024,max:131072},
  'conversation.summary.maxCalls':{min:0,max:64},
  'conversation.summary.inputBudget':{min:2048,max:131072},
};
export function conversationBudgetError(code){
  if(serviceBudgetErrors[code])return serviceBudgetErrors[code]+'。原文保留，不自动重试或换服务。';
  return ({CURRENT_CONTEXT_EXCEEDS_BUDGET:'当前人设与本次消息已超出输入预算。不会截断人设或原文，也未开始摘要或回复生成。',
    AUDIT_TEXT_NOT_ORIGINAL:'这是已核验身份的旧审计片段；审计可能已经脱敏或截断，不代表完整原始聊天。',
    SUMMARY_DISABLED:'需要新的摘要，但本次发送的摘要预算为 0。已停止，原文保留。',
    SUMMARY_CALL_BUDGET_EXHAUSTED:'本次发送的摘要批次预算已耗尽，未继续发起回复。已经完成的有效摘要保留，可用于之后明确发送的新请求。',
    SUMMARY_INPUT_BUDGET_TOO_SMALL:'本批摘要输入预算容不下先前摘要和新的原文片段，已停止，不会丢弃原文继续回答。',
    SUMMARY_PROVIDER_FAILED:'摘要 Provider 请求失败，未自动重试；原文保留。',
    SUMMARY_OUTPUT_LIMIT:'摘要输出超过可保存预算，未把截断内容当作完整摘要。',
    SUMMARY_OUTPUT_INVALID:'摘要不是要求的有效结构或超出正文预算，未作为记忆使用。',
    SUMMARY_SOURCE_CHANGED:'摘要来源版本已变化，未作为当前上下文使用。',
    SUMMARY_OUTCOME_NOT_REPLAYABLE:'该摘要批次已有失败、中断或未知结果，不会重新调用 Provider。',
    SUMMARY_COVERAGE_INCOMPLETE:'摘要未可靠覆盖本次需要的历史，未继续回复。',
    SUMMARY_CONTEXT_BUDGET:'组合后的上下文超过输入预算，未继续回复。',
    RESTART_INTERRUPTED:'上次请求因重启中断，不会自动续发模型调用。',
    USER_CANCELLED:'玩家已停止该请求，不会自动继续。'})[code]||code;
}
export function budgetPolicyText(b){
  if(!b)return '没有记录当时的预算，不能用现在的设置代替。';
  return `配置 r${b.configRevision} · 对话输入上界 ${b.contextTokenBudget} · 每批摘要输入 ${b.summaryInputBudget} 字节 · 最多 ${b.summaryMaxCalls} 个新摘要请求`;
}
export function modelReceiptText(receipt){
  if(!receipt)return '没有保存模型回执；不使用当前配置补填历史。';
  const mode={PROVIDER_STREAM:'Provider 流式响应',BUFFERED_PROVIDER_REPLY:'Provider 完整响应后分发',NOT_STREAMED:'非流式响应'}[receipt.streamMode]||'传输模式未记录';
  return `Provider ${receipt.providerId||'未记录'} · 请求模型 ${receipt.requestedModel||'未记录/不能安全展示'} · 服务端声明模型 ${receipt.responseModel||'未报告/不能安全展示'} · 配置版本 ${receipt.providerConfigurationRevision>=0?'r'+receipt.providerConfigurationRevision:'未记录'} · ${mode}。服务端声明不是独立验证，模型别名可能与请求名不同。`;
}
export function conversationContextLines(data){
  if(!data)return ['选择会话后可查看预算。查看和刷新不会调用模型。'];
  const lines=[`下一次新发送：${budgetPolicyText(data.currentBudget)}。`,
    '估算采用保守 UTF-8 字节上界，不是精确 tokenizer 计数、Provider usage 或费用；对话输入预算不包含 Provider 回复额度。摘要是额外模型请求。'];
  const t=data.turn;if(!t){lines.push('所选消息没有普通对话生成记录，或这个会话尚未发送。');return lines;}
  lines.push(`本次请求 ${t.operationId} · ${t.requestState}。`);
  if(t.inputSource)lines.push(`输入来源 ${t.inputSource}${t.speechOperation?' · 已核验转写请求 '+t.speechOperation:''}。语音转写仍需玩家确认发送，不另建任务系统。`);
  lines.push(modelReceiptText(t.modelReceipt));
  if(t.budget){lines.push(`发送时固定：${budgetPolicyText(t.budget)}。`);
    if(t.budget.contextTokenBudget!==data.currentBudget.contextTokenBudget||t.budget.summaryInputBudget!==data.currentBudget.summaryInputBudget||t.budget.summaryMaxCalls!==data.currentBudget.summaryMaxCalls)lines.push('当前配置已改变，但不会改变这个已接受请求的预算。');
    lines.push(t.estimatedTokens>=0?`已保存的输入估算：${t.estimatedTokens} / ${t.budget.contextTokenBudget}；上下文状态 ${t.summaryStatus}。`:'尚未形成可发送的上下文。');
    if(t.summaryId)lines.push(`实际选用摘要 ${t.summaryId} r${t.summaryRevision}（来源可在“摘要与来源”查看）。`);
    else if(t.omittedThrough>0)lines.push(`早期原文 1–${t.omittedThrough} 需要有效摘要；尚未记录最终选用摘要。`);
  }else lines.push('旧请求没有预算快照，不倒填为当前预算。');
  if(data.summaryBatches){const s=data.summaryBatches;lines.push(`已持久预留摘要批次 ${s.reservedBatches}：成功 ${s.readyBatches}、处理中 ${s.pendingBatches}、失败 ${s.failedBatches}、取消/中断 ${s.interruptedBatches}。预留不代表已到达 HTTP 或实际计费。`);}
  if(t.errorCode)lines.push(`${conversationBudgetError(t.errorCode)} [${t.errorCode}]`);
  if(['FAILED','CANCELLED','INTERRUPTED'].includes(t.requestState))lines.push('修改配置或重新读取不会重试该请求。需要继续时，请明确发送一条新消息；可能产生新的模型费用。');
  return lines;
}
export function conversationDraftText(draft){
  const values={};for(const [key,bounds] of Object.entries(conversationFieldBounds)){
    const field=draft.snapshot?.fields.find(f=>f.key===key);if(!field)return '';
    const raw=String(draft.changes.get(key)??field.value),number=raw.trim()===''?NaN:Number(raw);
    if(!Number.isInteger(number)||number<bounds.min||number>bounds.max)return '请输入标注范围内的整数；无效值不会保存。';values[key]=number;
  }
  const calls=values['conversation.summary.maxCalls'];
  return `仅对下一次新发送生效，已接受请求的预算不变。输入采用保守 UTF-8 上界，当前人设与新消息不截断；原始历史始终保留。${calls===0?'摘要预算为 0：可复用已校验摘要，但需要生成新摘要时会停止，不丢弃历史继续回答。':`一次新发送最多额外生成 ${calls} 个摘要，再生成对话回复；摘要也可能计费。`}保存或读取不调用模型。这些数值不是实际 token 用量或费用上限，也不自动探测远程模型的容量。`;
}
