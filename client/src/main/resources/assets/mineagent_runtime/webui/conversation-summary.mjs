export function summaryLabel(s){
 if(!s||s.state==='NOT_NEEDED_YET')return '尚无摘要 · 原文完整保留';
 if(s.state==='PENDING')return `摘要生成中 · r${s.revision??'?'}`;
 if(s.state==='READY'&&s.sourceValid===true&&s.end){const range=s.end.offset>0?`1–${s.end.sequence-1}，第${s.end.sequence}条部分至${s.end.offset}`:`1–${s.end.sequence-1}`;return `摘要 r${s.revision} 已校验 · 消息${range}`;}
 return `摘要不可用 · ${s.state||'UNKNOWN'}${s.error?' · '+s.error:''}；不会当作已知记忆`;
}
// Only an explicitly chosen older version is pinned. Opening the current summary is not a pause.
export function summaryRefreshRequired(rendered,next,historical,open){
 return (!open||!historical)&&rendered!==JSON.stringify(next??null);
}
