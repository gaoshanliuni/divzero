import {t as __uiT,tf as __uiF} from './i18n.mjs';
export function summaryLabel(s){
 if(!s||s.state==='NOT_NEEDED_YET')return __uiT("尚无摘要 · 原文完整保留");
 if(s.state==='PENDING')return __uiF("摘要生成中 · r{0}",s.revision??'?');
 if(s.state==='READY'&&s.sourceValid===true&&s.end){const range=s.end.offset>0?__uiF("1–{0}，第{1}条部分至{2}",s.end.sequence-1,s.end.sequence,s.end.offset):`1–${s.end.sequence-1}`;return __uiF("摘要 r{0} 已校验 · 消息{1}",s.revision,range);}
 return __uiF("摘要不可用 · {0}{1}；不会当作已知记忆",s.state||'UNKNOWN',s.error?' · '+s.error:'');
}
// Only an explicitly chosen older version is pinned. Opening the current summary is not a pause.
export function summaryRefreshRequired(rendered,next,historical,open){
 return (!open||!historical)&&rendered!==JSON.stringify(next??null);
}
