// Local text operations only: no Provider, host execution, clipboard or filesystem access.
const JAVA_WORDS=new Set(('abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for goto if implements import instanceof int interface long native new package private protected public return short static strictfp super switch synchronized this throw throws transient try void volatile while record sealed permits non-sealed var yield true false null').split(' '));
const JS_WORDS=new Set(('await break case catch class const continue debugger default delete do else export extends false finally for function if import in instanceof let new null return static super switch this throw true try typeof undefined var void while with yield').split(' '));
const JAVA_COMPLETIONS=['public','private','protected','class','interface','record','extends','implements','return','new','import','package','static','final','void','int','String','Map','List'];
const JS_COMPLETIONS=['const','let','function','return','class','extends','new','if','else','for','while','try','catch','host','on','schedule','track','require'];
const identifierStart=c=>!!c&&/[$_\p{ID_Start}]/u.test(c);
const identifierPart=c=>!!c&&/[$_\u200c\u200d\p{ID_Continue}]/u.test(c);
const charAt=(text,index)=>index>=0&&index<text.length?String.fromCodePoint(text.codePointAt(index)):'';
const previous=(text,index)=>index>1&&/[\uDC00-\uDFFF]/.test(text[index-1])&&/[\uD800-\uDBFF]/.test(text[index-2])?index-2:Math.max(0,index-1);
export function lexicalTokens(text,java=false){
  const words=java?JAVA_WORDS:JS_WORDS,tokens=[];let i=0;
  while(i<text.length){
    const start=i,c=charAt(text,i),next=text[i+1];let kind='';
    if(c==='/'&&next==='/'){i=text.indexOf('\n',i+2);if(i<0)i=text.length;kind='comment';}
    else if(c==='/'&&next==='*'){const end=text.indexOf('*/',i+2);i=end<0?text.length:end+2;kind='comment';}
    else if(c==='"'||c==="'"||!java&&c===String.fromCharCode(96)){
      const triple=java&&text.slice(i,i+3)==='"""';i+=triple?3:1;
      while(i<text.length){
        if(text[i]==='\\'){i=Math.min(text.length,i+2);continue;}
        if(triple&&text.slice(i,i+3)==='"""'){i+=3;break;}
        if(!triple&&text[i]===c){i++;break;}i++;
      }
      kind='string';
    }else if(/[0-9]/.test(c)){
      i+=c.length;while(i<text.length&&/[A-Za-z0-9_.]/.test(text[i]))i++;kind='number';
    }else if(identifierStart(c)){
      i+=c.length;while(identifierPart(charAt(text,i)))i+=charAt(text,i).length;
      if(words.has(text.slice(start,i)))kind='keyword';
    }else i+=c.length||1;
    if(kind)tokens.push({start,end:i,kind});
  }
  return tokens;
}
export function literalMatches(text,query,caseSensitive=true){
  if(!query)return[];
  if(query.length>256)throw new Error('查找文本最多 256 字符。');
  const escaped=query.replace(/[.*+?^{}$()|[\]\\]/g,'\\$&');
  return Array.from(text.matchAll(new RegExp(escaped,caseSensitive?'gu':'giu')),m=>({start:m.index,end:m.index+m[0].length}));
}
export function attachStudioEditor({container,textarea,language,canEdit,notify}){
  const limit=16000,undo=[],redo=[],listeners=[],buttons=[];let disposed=false,composing=false,timer=0,last=textarea.value,suggestion=null,rendered='';
  const node=(tag,text,parent,cls)=>{const n=document.createElement(tag);if(text!=null)n.textContent=text;if(cls)n.className=cls;parent.append(n);return n;};
  const listen=(target,type,fn)=>{target.addEventListener(type,fn);listeners.push(()=>target.removeEventListener(type,fn));};
  const available=()=>!disposed&&textarea.isConnected&&!textarea.disabled;
  const writable=()=>available()&&!textarea.readOnly&&canEdit()&&!composing;
  const button=(title,parent,fn,mutates=false,enabled=()=>true)=>{
    const n=node('button',title,parent);n.type='button';const state={n,mutates,enabled};buttons.push(state);
    n.onclick=()=>{if(available()&&!n.disabled&&(!mutates||writable()))try{fn();}catch(e){notify(e.message);}};
    return n;
  };
  const bar=node('section',null,container,'studio-editor-tools');
  const title=node('p','本地编辑工具 · 不保存、不发布、不执行',bar,'muted');
  const row=node('div',null,bar,'event-toolbar');
  const field=(title,max,parent=row)=>{const label=node('label',title,parent),n=node('input',null,label);n.maxLength=max;return n;};
  const search=field('查找（literal）',256),replace=field('替换为（literal）',512);
  const sensitiveLabel=node('label',null,row),sensitive=node('input',null,sensitiveLabel);sensitive.type='checkbox';sensitive.checked=true;sensitiveLabel.append(document.createTextNode('区分大小写'));
  const actions=node('div',null,bar,'actions'),summary=node('p','',bar,'muted');summary.setAttribute('aria-live','polite');
  const matches=()=>literalMatches(textarea.value,search.value,sensitive.checked);
  const position=index=>{const prefix=textarea.value.slice(0,index),line=prefix.split('\n').length,start=prefix.lastIndexOf('\n')+1;return '行 '+line+' · 列 '+(Array.from(prefix.slice(start)).length+1);};
  function select(start,end){if(!available())return;textarea.focus();textarea.setSelectionRange(start,end);summary.textContent=position(start);refreshState();}
  function remember(value){undo.push(value);if(undo.length>64)undo.shift();redo.length=0;}
  function changed(){
    if(disposed||composing)return;const value=textarea.value;if(value!==last){remember(last);last=value;}
    clearCompletions();refreshState();schedulePreview();
  }
  function apply(text,start,end){
    if(!writable())return false;
    if(text.length>limit){notify('结果超过单文件 16000 字符上限，未修改源码。');return false;}
    if(text!==textarea.value){remember(textarea.value);textarea.value=text;last=text;textarea.dispatchEvent(new Event('input',{bubbles:true}));}
    select(Math.min(text.length,start),Math.min(text.length,end));schedulePreview();refreshState();return true;
  }
  function replaceRange(start,end,value){return apply(textarea.value.slice(0,start)+value+textarea.value.slice(end),start,start+value.length);}
  function find(direction){
    const found=matches();if(!found.length){summary.textContent='没有匹配；空查找不会修改源码。';return;}
    const cursor=direction>0?textarea.selectionEnd:textarea.selectionStart;
    const match=direction>0?(found.find(m=>m.start>=cursor)||found[0]):(found.findLast(m=>m.end<=cursor)||found.at(-1));
    select(match.start,match.end);summary.textContent=(found.indexOf(match)+1)+' / '+found.length+' · '+position(match.start);
  }
  function replaceSelected(){
    const found=matches(),match=found.find(m=>m.start===textarea.selectionStart&&m.end===textarea.selectionEnd);
    if(!match){find(1);notify('已定位匹配；再次点击替换才修改这处文本。');return;}
    if(replaceRange(match.start,match.end,replace.value))notify('已替换选中匹配，尚未保存。');
  }
  function replaceAll(){
    const text=textarea.value,found=matches();if(!found.length){summary.textContent='没有匹配，未修改。';return;}
    const length=text.length+found.reduce((n,m)=>n+replace.value.length-(m.end-m.start),0);
    if(length>limit){notify('全部替换会超过 16000 字符，未作部分替换。');return;}
    const pieces=[];let cursor=0;for(const m of found){pieces.push(text.slice(cursor,m.start),replace.value);cursor=m.end;}pieces.push(text.slice(cursor));
    if(apply(pieces.join(''),found[0].start,found[0].start+replace.value.length))notify('已替换 '+found.length+' 处（literal，未保存）。');
  }
  function undoRedo(back){
    if(!writable())return;const from=back?undo:redo,to=back?redo:undo;if(!from.length)return;
    to.push(textarea.value);if(to.length>64)to.shift();const cursor=textarea.selectionStart;textarea.value=from.pop();last=textarea.value;
    textarea.dispatchEvent(new Event('input',{bubbles:true}));select(Math.min(cursor,last.length),Math.min(cursor,last.length));schedulePreview();refreshState();
  }
  button('上一处',actions,()=>find(-1));button('下一处',actions,()=>find(1));
  button('替换选中匹配',actions,replaceSelected,true);button('全部替换',actions,replaceAll,true);
  button('撤销',actions,()=>undoRedo(true),true,()=>undo.length>0);button('重做',actions,()=>undoRedo(false),true,()=>redo.length>0);
  const gotoRow=node('div',null,bar,'event-toolbar'),line=field('跳转行',7,gotoRow);line.inputMode='numeric';
  button('跳转',gotoRow,()=>{
    const target=Number(line.value),lines=textarea.value.split('\n');
    if(!Number.isSafeInteger(target)||target<1||target>lines.length){notify('请输入已有行号 1–'+lines.length+'。');return;}
    const index=lines.slice(0,target-1).reduce((n,s)=>n+s.length+1,0);select(index,index);
  });
  function indent(remove){
    const text=textarea.value,start=textarea.selectionStart===0?0:text.lastIndexOf('\n',textarea.selectionStart-1)+1;
    let end=textarea.selectionEnd;if(end>start&&text[end-1]==='\n')end--;
    const lineEnd=text.indexOf('\n',end);end=lineEnd<0?text.length:lineEnd;
    const fragment=text.slice(start,end),parts=fragment.split('\n');
    const edited=parts.map(s=>{if(!remove)return '  '+s;const amount=s.startsWith('\t')?1:(s.match(/^ {1,2}/)?.[0].length||0);return s.slice(amount);}).join('\n');
    if(apply(text.slice(0,start)+edited+text.slice(end),start,start+edited.length))notify((remove?'减少':'增加')+'所选行缩进；这是文本编辑，不是 AST 格式化。');
  }
  button('缩进所选行',gotoRow,()=>indent(false),true);button('减少缩进',gotoRow,()=>indent(true),true);
  const tabLabel=node('label',null,gotoRow),tabs=node('input',null,tabLabel);tabs.type='checkbox';tabLabel.append(document.createTextNode('仅此编辑器：Tab 用于缩进（默认关闭）'));
  const completions=node('div',null,bar,'studio-completions');
  function clearCompletions(){suggestion=null;completions.replaceChildren();}
  function complete(){
    if(!writable())return;const text=textarea.value,cursor=textarea.selectionStart;if(cursor!==textarea.selectionEnd){notify('请先将光标放在一个标识符中，不选择文本。');return;}
    let start=cursor,end=cursor;while(start>0&&identifierPart(charAt(text,previous(text,start))))start=previous(text,start);while(identifierPart(charAt(text,end)))end+=charAt(text,end).length;
    const prefix=text.slice(start,cursor);if(!prefix){notify('先输入标识符前缀，再请求补全。');return;}
    const tokens=lexicalTokens(text,language()==='JAVA');
    if(tokens.some(t=>['comment','string'].includes(t.kind)&&cursor>t.start&&cursor<=t.end)){notify('不在注释或字符串内部提供标识符补全。');return;}
    const words=new Set(language()==='JAVA'?JAVA_COMPLETIONS:JS_COMPLETIONS);
    for(const match of text.matchAll(/[$_\p{ID_Start}][$_\u200c\u200d\p{ID_Continue}]*/gu))words.add(match[0]);
    const candidates=Array.from(words).filter(v=>v.startsWith(prefix)&&v!==text.slice(start,end)).sort().slice(0,20);
    clearCompletions();suggestion={text,start,end,cursor,language:language()};
    if(!candidates.length){notify('没有匹配的词法补全；Native 类型/方法请查看 Native API。');return;}
    node('p','关键词 / 本文件标识符；不是 Native 类型推断或运行兼容证明。',completions,'muted');
    for(const value of candidates){
      const n=node('button',value,completions);n.type='button';n.onclick=()=>{
        const s=suggestion;if(!s||!writable()||textarea.value!==s.text||textarea.selectionStart!==s.cursor||textarea.selectionEnd!==s.cursor||language()!==s.language){notify('源码或光标已变化，请重新获取补全。');clearCompletions();return;}
        replaceRange(s.start,s.end,value);notify('已采用补全，尚未保存。');clearCompletions();
      };
    }
  }
  button('光标处词法补全',gotoRow,complete,true);
  const preview=node('details',null,bar,'studio-syntax-view');node('summary','完整源码词法预览（不是语法检查）',preview);
  const pre=node('pre',null,preview,'studio-syntax-code');pre.tabIndex=0;pre.setAttribute('aria-label','只读源码词法预览');
  function drawPreview(){
    timer=0;if(disposed||!preview.open||!textarea.isConnected)return;
    const text=textarea.value,java=language()==='JAVA',signature=(java?'JAVA:':'RHINO:')+text;if(rendered===signature)return;rendered=signature;
    const fragment=document.createDocumentFragment();let cursor=0;
    for(const t of lexicalTokens(text,java)){fragment.append(document.createTextNode(text.slice(cursor,t.start)));const span=document.createElement('span');span.className='studio-token-'+t.kind;span.textContent=text.slice(t.start,t.end);fragment.append(span);cursor=t.end;}
    fragment.append(document.createTextNode(text.slice(cursor)));pre.replaceChildren(fragment);
  }
  function schedulePreview(){clearTimeout(timer);timer=setTimeout(drawPreview,100);}
  function refreshState(){
    if(disposed)return;
    for(const b of buttons){const enabled=available()&&(!b.mutates||writable())&&b.enabled();b.n.disabled=!enabled;b.n.dataset.studioAllowed=String(enabled);}
    for(const n of completions.querySelectorAll('button'))n.disabled=!writable();
    if(!composing)title.textContent='本地编辑工具 · '+textarea.value.length+' / '+limit+' UTF-16 字符 · '+position(textarea.selectionStart)+' · 未跨重载保留撤销栈';
  }
  listen(textarea,'input',changed);listen(textarea,'select',refreshState);listen(textarea,'keyup',refreshState);listen(textarea,'click',refreshState);
  listen(textarea,'compositionstart',()=>{composing=true;clearCompletions();refreshState();});
  listen(textarea,'compositionend',()=>{composing=false;changed();});
  listen(preview,'toggle',drawPreview);
  listen(search,'input',()=>{summary.textContent='查找条件已变化。';});listen(sensitive,'change',()=>{summary.textContent='查找条件已变化。';});
  listen(textarea,'keydown',event=>{
    if(event.isComposing||composing||!available())return;
    const command=event.ctrlKey||event.metaKey,key=event.key.toLowerCase();
    if(command&&key==='f'){event.preventDefault();event.stopPropagation();search.focus();}
    else if(command&&key==='h'){event.preventDefault();event.stopPropagation();replace.focus();}
    else if(command&&(key==='z'||key==='y')&&writable()){event.preventDefault();event.stopPropagation();undoRedo(key==='z'&&!event.shiftKey);}
    else if(event.ctrlKey&&event.code==='Space'&&writable()){event.preventDefault();event.stopPropagation();complete();}
    else if(event.key==='Tab'&&tabs.checked&&writable()){event.preventDefault();event.stopPropagation();indent(event.shiftKey);}
  });
  refreshState();
  return {refreshState,changed,schedulePreview,replaceDocument:text=>apply(text,0,0),dispose:()=>{disposed=true;clearTimeout(timer);listeners.forEach(fn=>fn());undo.length=redo.length=0;clearCompletions();}};
}
