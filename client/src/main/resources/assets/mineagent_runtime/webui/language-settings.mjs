import {t} from './i18n.mjs';
export function createLanguageSettings({windowFor,send,report,persist}){
 async function open(){const root=windowFor('runtime-language',t('语言设置'));root.replaceChildren();const add=(tag,text)=>{const n=document.createElement(tag);if(text)n.textContent=text;root.append(n);return n;};
  add('h2',t('界面语言'));const choices=add('select');choices.id='ui-language-choice';choices.setAttribute('aria-label',t('界面语言'));for(const [id,label]of [['auto',t('跟随游戏语言')],['zh_cn','简体中文'],['en_us','English']]){const option=document.createElement('option');option.value=id;option.textContent=label;choices.append(option);}choices.disabled=true;
  add('p',t('默认跟随游戏语言；仅影响本机界面，玩家输入、AI 回复和内容包不翻译。')).className='muted';add('p',t('应用将重新打开 F2。已保存的会话和布局保留，请先保存其它表单。')).className='muted';
  const status=add('p',t('读取设置…'));status.setAttribute('role','status');const apply=add('button',t('应用并重新打开 F2'));apply.id='ui-language-apply';apply.disabled=true;let revision;
  try{const state=await send('languageSettings',{action:'read'});if(!root.isConnected)return;if(state.error)throw new Error(state.error);choices.value=state.language;revision=state.revision;choices.disabled=apply.disabled=false;status.textContent='';}catch(e){status.textContent=t('读取失败，请重新打开设置。');report(e);}
  apply.onclick=async()=>{apply.disabled=choices.disabled=true;try{await persist();await send('languageSettings',{action:'set',language:choices.value,expectedRevision:revision});await send('languageSettings',{action:'reopen'});}catch(e){status.textContent=t('保存失败，请重新打开设置。');report(e);apply.disabled=choices.disabled=false;}};
 }
 return {open};
}
